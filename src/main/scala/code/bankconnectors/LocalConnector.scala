package code.bankconnectors

import net.liftweb.common.Box
import scala.concurrent.ops.spawn
import code.model.Bank
import code.model.BankAccount
import code.model.dataAccess.HostedBank
import code.model.dataAccess.Account
import code.model.dataAccess.ViewImpl
import net.liftweb.mapper.By
import net.liftweb.common.Full
import code.model.User
import code.model.dataAccess.HostedAccount
import code.model.dataAccess.APIUser
import net.liftweb.common.Loggable
import org.bson.types.ObjectId
import net.liftweb.util.Helpers._
import code.model.OtherBankAccount
import code.model.ModeratedOtherBankAccount
import code.model.dataAccess.OBPEnvelope
import code.model.dataAccess.OBPEnvelope
import code.model.dataAccess.OBPAccount
import code.model.OtherBankAccountMetadata
import code.model.dataAccess.Metadata
import code.model.GeoTag
import code.model.dataAccess.OBPGeoTag
import code.model.ModeratedTransaction
import code.model.Transaction
import code.model.dataAccess.OBPEnvelope.OBPQueryParam
import net.liftweb.util.Props
import com.tesobe.model.UpdateBankAccount
import code.model.TransactionMetadata
import code.model.dataAccess.OBPTransaction
import code.model.dataAccess.UpdatesRequestSender

object LocalConnector extends Connector with Loggable {

  def getBank(permalink: String): Box[Bank] =
    for{
      bank <- getHostedBank(permalink)
    } yield {
      createBank(bank)
    }
  
  //gets banks handled by this connector
  def getBanks : List[Bank] =
    HostedBank.findAll.map(createBank)
  
  def getBankAccount(bankPermalink : String, accountId : String) : Box[BankAccount] = {
    for{
      bank <- getHostedBank(bankPermalink)
      account <- bank.getAccount(accountId)
    } yield Account toBankAccount account
  }
  
  def getAllPublicAccounts() : List[BankAccount] = {
    
    //TODO: remove ViewImpl and replace it with it's interface: view interface needs attributes for bankPermalink + accountPermalink
    ViewImpl.findAll(By(ViewImpl.isPublic_, true)). 
      map{_.account.obj}.
      collect{case Full(a) => a.theAccount}.
      collect{case Full(a) => Account.toBankAccount(a)}
  }

  def getPublicBankAccounts(bank : Bank) : List[BankAccount] = {
    
    //TODO: remove ViewImpl and replace it with it's interface: view interface needs attributes for bankPermalink + accountPermalink
    ViewImpl.findAll(By(ViewImpl.isPublic_, true)). //TODO: this should be a Metadata.vend() type thing to remove the hardcoded ViewImpl reference
      map{_.account.obj}.
      collect{case Full(a) if a.bank==bank.fullName => a.theAccount}.
      collect{case Full(a) => Account.toBankAccount(a)}
  }
  
  /**
   * @param user
   * @return the bank accounts the @user can see (public + private if @user is Full, public if @user is Empty)
   */
  def getAllAccountsUserCanSee(user : Box[User]) : List[BankAccount] = {
    user match {
      case Full(u) => {
        val moreThanAnonHosted = moreThanAnonHostedAccounts(u)
        val mongoIds = moreThanAnonHosted.map(hAcc => new ObjectId(hAcc.accountID.get))
        val moreThanAnonAccounts = Account.findAll(mongoIds).map(Account.toBankAccount)

        val publicAccountsThatUserDoesNotHaveMoreThanAnon = ViewImpl.findAll(By(ViewImpl.isPublic_, true)).
          map{_.account.obj}.
          collect{case Full(a) => a.theAccount}.
          collect{case Full(a)
          //Throw out those that are already counted in moreThanAnonAccounts
          if(!moreThanAnonAccounts.exists(x => sameAccount(a, x))) => Account.toBankAccount(a)
        }

        moreThanAnonAccounts ++ publicAccountsThatUserDoesNotHaveMoreThanAnon
      }
      case _ => getAllPublicAccounts()
    }
  }
  
  /**
  * @param user
  * @return the bank accounts at @bank the @user can see (public + private if @user is Full, public if @user is Empty)
  */
  def getAllAccountsUserCanSee(bank: Bank, user : Box[User]) : Box[List[BankAccount]] = {
    user match {
      case Full(u) => {
        //TODO: this could be quite a bit more efficient...
        for {
          bankObjectId <- tryo{new ObjectId(bank.id)}
        } yield {
          def sameBank(account : Account) : Boolean =
            account.bankID.get == bankObjectId

          val moreThanAnonHosted = moreThanAnonHostedAccounts(u)
          val mongoIds = moreThanAnonHosted.map(hAcc => new ObjectId(hAcc.accountID.get))
          val moreThanAnonAccounts = Account.findAll(mongoIds).filter(sameBank).map(Account.toBankAccount)

          val publicAccountsThatUserDoesNotHaveMoreThanAnon = ViewImpl.findAll(By(ViewImpl.isPublic_, true)).
            map{_.account.obj}.
            collect{case Full(a) if a.bank==bank.fullName => a.theAccount}. //throw out with the wrong bank
            collect{case Full(a)
              //Throw out those that are already counted in moreThanAnonAccounts
              if(!moreThanAnonAccounts.exists(x => sameAccount(a, x))) => Account.toBankAccount(a)
          }

          moreThanAnonAccounts ++ publicAccountsThatUserDoesNotHaveMoreThanAnon
        }
      }
      case _ => Full(getPublicBankAccounts(bank))
    }
  }
  
  /**
  * @return the bank accounts where the user has at least access to a non public view (is_public==false)
  */
  def getNonPublicBankAccounts(user : User) :  Box[List[BankAccount]] = {

    val accountsList =
      user match {
        case u : APIUser => {
          val moreThanAnon = moreThanAnonHostedAccounts(u)
          val mongoIds = moreThanAnon.map(hAcc => new ObjectId(hAcc.accountID.get))
          Account.findAll(mongoIds).map(Account.toBankAccount)
        }
        case u: User => {
          logger.error("APIUser instance not found, could not find the non public accounts")
          Nil
        }
      }
    Full(accountsList)
  }
  
    /**
  * @return the bank accounts where the user has at least access to a non public view (is_public==false) for a specific bank
  */
  def getNonPublicBankAccounts(user : User, bankID : String) :  Box[List[BankAccount]] = {
    user match {
      case u : APIUser => {
        for {
          bankObjectId <- tryo{new ObjectId(bankID)}
        } yield {
          def sameBank(account : Account) : Boolean =
            account.bankID.get == bankObjectId

          val moreThanAnon = moreThanAnonHostedAccounts(u)
          val mongoIds = moreThanAnon.map(hAcc => new ObjectId(hAcc.accountID.get))
          Account.findAll(mongoIds).filter(sameBank).map(Account.toBankAccount)
        }
      }
      case u : User => {
        logger.error("APIUser instance not found, could not find the non public account ")
        Full(Nil)
      }
    }
  }
  
  def getModeratedOtherBankAccount(accountID : String, otherAccountID : String)
  (moderate: OtherBankAccount => Option[ModeratedOtherBankAccount]): Box[ModeratedOtherBankAccount] = {
      for{
        id <- tryo{new ObjectId(accountID)} ?~ {"account " + accountID + " not found"}
        account <- Account.find("_id",id)
        otherAccountmetadata <- account.otherAccountsMetadata.objs.find(_.id.get.equals(otherAccountID))
      } yield{
          val otherAccountFromTransaction : OBPAccount = OBPEnvelope.find("obp_transaction.other_account.metadata",otherAccountmetadata.id.is) match {
            case Full(envelope) => envelope.obp_transaction.get.other_account.get
            case _ => {
              logger.warn("no other account found")
              OBPAccount.createRecord
            }
          }
          moderate(createOtherBankAccount(otherAccountmetadata, otherAccountFromTransaction)).get
        }
  }

  def getModeratedOtherBankAccounts(accountID : String)
  (moderate: OtherBankAccount => Option[ModeratedOtherBankAccount]): Box[List[ModeratedOtherBankAccount]] = {
    for{
      id <- tryo{new ObjectId(accountID)} ?~ {"account " + accountID + " not found"}
      account <- Account.find("_id",id)
    } yield{
        val otherBankAccounts = account.otherAccountsMetadata.objs.map(otherAccount => {
          //for legacy reasons some of the data about the "other account" are stored only on the transactions
          //so we need first to get a transaction that match to have the rest of the data
          val otherAccountFromTransaction : OBPAccount = OBPEnvelope.find("obp_transaction.other_account.holder",otherAccount.holder.get) match {
              case Full(envelope) =>
                envelope.obp_transaction.get.other_account.get
              case _ => {
                logger.warn(s"envelope not found for other account ${otherAccount.id.get}")
                OBPAccount.createRecord
              }
            }
          createOtherBankAccount(otherAccount, otherAccountFromTransaction)
        })

        (otherBankAccounts.map(moderate)).collect{case Some(t) => t}
      }
  }
  
  def getModeratedTransactions(permalink: String, bankPermalink: String, queryParams: OBPQueryParam*)
  (moderate: Transaction => ModeratedTransaction): Box[List[ModeratedTransaction]] = {
    for{
      rawTransactions <- getTransactions(permalink, bankPermalink, queryParams: _*)
    } yield rawTransactions.map(moderate)
  }
  
  def getModeratedTransaction(id : String, bankPermalink : String, accountPermalink : String)
  (moderate: Transaction => ModeratedTransaction) : Box[ModeratedTransaction] = {
    for{
      transaction <- getTransaction(id,bankPermalink,accountPermalink)
    } yield moderate(transaction)
  }
  
  
  private def getTransactions(permalink: String, bankPermalink: String, queryParams: OBPQueryParam*): Box[List[Transaction]] = {
      logger.debug("getTransactions for " + bankPermalink + "/" + permalink)
      for{
        bank <- getHostedBank(bankPermalink)
        account <- bank.getAccount(permalink)
      } yield {
        updateAccountTransactions(bank, account)
        account.envelopes(queryParams: _*).flatMap(createTransaction(_, account))
      }
  }
  
  private def getTransaction(id : String, bankPermalink : String, accountPermalink : String) : Box[Transaction] = {
    for{
      bank <- getHostedBank(bankPermalink) ?~! s"Transaction not found: bank $bankPermalink not found"
      account  <- bank.getAccount(accountPermalink) ?~! s"Transaction not found: account $accountPermalink not found"
      objectId <- tryo{new ObjectId(id)} ?~ {"Transaction "+id+" not found"}
      envelope <- OBPEnvelope.find(account.transactionsForAccount.put("_id").is(objectId).get)
      transaction <- createTransaction(envelope,account)
    } yield {
      updateAccountTransactions(bank, account)
      transaction
    }
  }
    private def createTransaction(env: OBPEnvelope, theAccount: Account): Option[Transaction] = {
    val transaction: OBPTransaction = env.obp_transaction.get
    val otherAccount_ = transaction.other_account.get

    otherAccount_.metadata.obj match {
      case Full(oaccMetadata) =>{
        val thisBankAccount = Account.toBankAccount(theAccount)
        val id = env.id.is.toString()
        val uuid = id
        val otherAccountMetadata = createOtherBankAccountMetadata(oaccMetadata)

        val otherAccount = new OtherBankAccount(
            id = oaccMetadata.id.is.toString,
            label = otherAccount_.holder.get,
            nationalIdentifier = otherAccount_.bank.get.national_identifier.get,
            swift_bic = None, //TODO: need to add this to the json/model
            iban = Some(otherAccount_.bank.get.IBAN.get),
            number = otherAccount_.number.get,
            bankName = otherAccount_.bank.get.name.get,
            metadata = otherAccountMetadata,
            kind = ""
          )
        val transactionType = transaction.details.get.kind.get
        val amount = transaction.details.get.value.get.amount.get
        val currency = transaction.details.get.value.get.currency.get
        val label = Some(transaction.details.get.label.get)
        val startDate = transaction.details.get.posted.get
        val finishDate = transaction.details.get.completed.get
        val balance = transaction.details.get.new_balance.get.amount.get
        val t =
          new Transaction(
            uuid,
            id,
            thisBankAccount,
            otherAccount,
            transactionType,
            amount,
            currency,
            label,
            startDate,
            finishDate,
            balance
          )
        Some(t)
      }
      case _ => {
        logger.warn(s"no metadata reference found for envelope ${env.id.get}")
        None
      }
    }
  }
    
    private def createOtherBankAccountMetadata(otherAccountMetadata : Metadata): OtherBankAccountMetadata = {
    new OtherBankAccountMetadata(
      publicAlias = otherAccountMetadata.publicAlias.get,
      privateAlias = otherAccountMetadata.privateAlias.get,
      moreInfo = otherAccountMetadata.moreInfo.get,
      url = otherAccountMetadata.url.get,
      imageURL = otherAccountMetadata.imageUrl.get,
      openCorporatesURL = otherAccountMetadata.openCorporatesUrl.get,
      corporateLocation = locatationTag(otherAccountMetadata.corporateLocation.get),
      physicalLocation = locatationTag(otherAccountMetadata.physicalLocation.get),
      addMoreInfo = (text => {
        otherAccountMetadata.moreInfo(text).save
        //the save method does not return a Boolean to inform about the saving state,
        //so we a true
        true
      }),
      addURL = (text => {
        otherAccountMetadata.url(text).save
        //the save method does not return a Boolean to inform about the saving state,
        //so we a true
        true
      }),
      addImageURL = (text => {
        otherAccountMetadata.imageUrl(text).save
        //the save method does not return a Boolean to inform about the saving state,
        //so we a true
        true
      }),
      addOpenCorporatesURL = (text => {
        otherAccountMetadata.openCorporatesUrl(text).save
        //the save method does not return a Boolean to inform about the saving state,
        //so we a true
        true
      }),
      addCorporateLocation = otherAccountMetadata.addCorporateLocation,
      addPhysicalLocation = otherAccountMetadata.addPhysicalLocation,
      addPublicAlias = (alias => {
        otherAccountMetadata.publicAlias(alias).save
        //the save method does not return a Boolean to inform about the saving state,
        //so we a true
        true
      }),
      addPrivateAlias = (alias => {
        otherAccountMetadata.privateAlias(alias).save
        //the save method does not return a Boolean to inform about the saving state,
        //so we a true
        true
      }),
      deleteCorporateLocation = otherAccountMetadata.deleteCorporateLocation _,
      deletePhysicalLocation = otherAccountMetadata.deletePhysicalLocation _
    )
  }
  
  /**
  *  Checks if the last update of the account was made more than one hour ago.
  *  if it is the case we put a message in the message queue to ask for
  *  transactions updates
  *
  *  It will be used each time we fetch transactions from the DB. But the test
  *  is performed in a different thread.
  */

  private def updateAccountTransactions(bank: HostedBank, account: Account): Unit = {
    spawn{
      val useMessageQueue = Props.getBool("messageQueue.updateBankAccountsTransaction", false)
      val outDatedTransactions = now after time(account.lastUpdate.get.getTime + hours(1))
      if(outDatedTransactions && useMessageQueue) {
        UpdatesRequestSender.sendMsg(UpdateBankAccount(account.number.get, bank.national_identifier.get))
      }
    }
  }

  
  private def createOtherBankAccount(otherAccount : Metadata, otherAccountFromTransaction : OBPAccount) : OtherBankAccount = {
    val metadata =
      new OtherBankAccountMetadata(
        publicAlias = otherAccount.publicAlias.get,
        privateAlias = otherAccount.privateAlias.get,
        moreInfo = otherAccount.moreInfo.get,
        url = otherAccount.url.get,
        imageURL = otherAccount.imageUrl.get,
        openCorporatesURL = otherAccount.openCorporatesUrl.get,
        corporateLocation = locatationTag(otherAccount.corporateLocation.get),
        physicalLocation = locatationTag(otherAccount.physicalLocation.get),
        addMoreInfo = (text => {
          otherAccount.moreInfo(text).save
          //the save method does not return a Boolean to inform about the saving state,
          //so we a true
          true
        }),
        addURL = (text => {
          otherAccount.url(text).save
          //the save method does not return a Boolean to inform about the saving state,
          //so we a true
          true
        }),
        addImageURL = (text => {
          otherAccount.imageUrl(text).save
          //the save method does not return a Boolean to inform about the saving state,
          //so we a true
          true
        }),
        addOpenCorporatesURL = (text => {
          otherAccount.openCorporatesUrl(text).save
          //the save method does not return a Boolean to inform about the saving state,
          //so we a true
          true
        }),
        addCorporateLocation = otherAccount.addCorporateLocation,
        addPhysicalLocation = otherAccount.addPhysicalLocation,
        addPublicAlias = (alias => {
          otherAccount.publicAlias(alias).save
          //the save method does not return a Boolean to inform about the saving state,
          //so we a true
          true
        }),
        addPrivateAlias = (alias => {
          otherAccount.privateAlias(alias).save
          //the save method does not return a Boolean to inform about the saving state,
          //so we a true
          true
        }),
        deleteCorporateLocation = otherAccount.deleteCorporateLocation _,
        deletePhysicalLocation = otherAccount.deletePhysicalLocation _
      )

    new OtherBankAccount(
      id = otherAccount.id.is.toString,
      label = otherAccount.holder.get,
      nationalIdentifier = otherAccountFromTransaction.bank.get.national_identifier.get,
      swift_bic = None, //TODO: need to add this to the json/model
      iban = Some(otherAccountFromTransaction.bank.get.IBAN.get),
      number = otherAccountFromTransaction.number.get,
      bankName = otherAccountFromTransaction.bank.get.name.get,
      metadata = metadata,
      kind = ""
    )
  }
  
  private def locatationTag(loc: OBPGeoTag): Option[GeoTag]={
    if(loc.longitude==0 && loc.latitude==0 && loc.userId.get.isEmpty)
      None
    else
      Some(loc)
  }
  
  private def moreThanAnonHostedAccounts(user : User) : List[HostedAccount] = {
    user match {
      //TODO: what's up with this?
      case u : APIUser => {
        u.views_.toList.
        filterNot(_.isPublic_).
        map(_.account.obj.get)
      }
      case _ => {
        logger.error("APIUser instance not found, could not find the accounts")
        Nil
      }
    }
  }
  
  /**
   * Checks if an Account and BankAccount represent the same thing (to avoid converting between the two if
   * it's not required)
   */
  private def sameAccount(account : Account, bankAccount : BankAccount) : Boolean = {
    //important: account.permalink.get (if you just use account.permalink it compares a StringField
    // to a String, which will always be false
    (account.bankPermalink == bankAccount.bankPermalink) && (account.permalink.get == bankAccount.permalink)
  }
  
  private def getHostedBank(permalink : String) : Box[HostedBank] = {
    for{
      bank <- HostedBank.find("permalink", permalink) ?~ {"bank " + permalink + " not found"}
    } yield bank
  }
  
  private def createBank(bank : HostedBank) : Bank = {
    new Bank(
      bank.id.is.toString,
      bank.alias.is,
      bank.name.is,
      bank.permalink.is,
      bank.logoURL.is,
      bank.website.is
    )
  }
}