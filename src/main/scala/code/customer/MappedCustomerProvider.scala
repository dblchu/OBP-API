package code.customer

import java.util.Date

import code.model.{BankId, User}
import code.model.dataAccess.ResourceUser
import code.users.Users
import code.util.{DefaultStringField, MappedUUID}
import net.liftweb.common.Box
import net.liftweb.mapper._

object MappedCustomerProvider extends CustomerProvider {


  override def checkCustomerNumberAvailable(bankId : BankId, customerNumber : String) : Boolean = {
    val customers  = MappedCustomer.findAll(
      By(MappedCustomer.mBank, bankId.value),
      By(MappedCustomer.mNumber, customerNumber)
    )

    val available: Boolean = customers.size match {
      case 0 => true
      case _ => false
    }

    available
  }

  override def getCustomer(bankId : BankId, user: User): Box[Customer] = {
    MappedCustomer.find(
      By(MappedCustomer.mUser, user.resourceUserId.value),
      By(MappedCustomer.mBank, bankId.value))
  }

  override def getCustomerByCustomerId(customerId: String): Box[Customer] = {
    MappedCustomer.find(
      By(MappedCustomer.mCustomerId, customerId)
    )
  }

  override def getBankIdByCustomerId(customerId: String): Box[String] = {
    val customer: Box[MappedCustomer] = MappedCustomer.find(
      By(MappedCustomer.mCustomerId, customerId)
    )
    for (c <- customer) yield {c.mBank.get}
  }

  override def getCustomer(customerId: String, bankId : BankId): Box[Customer] = {
    MappedCustomer.find(
      By(MappedCustomer.mCustomerId, customerId),
      By(MappedCustomer.mBank, bankId.value)
    )
  }

  override def getUser(bankId: BankId, customerNumber: String): Box[User] = {
    MappedCustomer.find(
      By(MappedCustomer.mBank, bankId.value),
      By(MappedCustomer.mNumber, customerNumber)
    ).flatMap(x => Users.users.vend.getResourceUserByResourceUserId(x.mUser.get))
  }

  override def addCustomer(bankId: BankId,
                           user : User,
                           number : String,
                           legalName : String,
                           mobileNumber : String,
                           email : String,
                           faceImage: CustomerFaceImage,
                           dateOfBirth: Date,
                           relationshipStatus: String,
                           dependents: Int,
                           dobOfDependents: List[Date],
                           highestEducationAttained: String,
                           employmentStatus: String,
                           kycStatus: Boolean,
                           lastOkDate: Date,
                           creditRating: Option[CreditRating],
                           creditLimit: Option[AmountOfMoney]
                          ) : Box[Customer] = {

    val cr = creditRating match {
      case Some(c) => MockCreditRating(rating = c.rating, source = c.source)
      case _       => MockCreditRating(rating = "", source = "")
    }

    val cl = creditLimit match {
      case Some(c) => MockCreditLimit(currency = c.currency, amount = c.amount)
      case _       => MockCreditLimit(currency = "", amount = "")
    }

    val createdCustomer = MappedCustomer.create
      .mBank(bankId.value)
      .mEmail(email)
      .mFaceImageTime(faceImage.date)
      .mFaceImageUrl(faceImage.url)
      .mLegalName(legalName)
      .mMobileNumber(mobileNumber)
      .mNumber(number)
      .mUser(user.resourceUserId.value)
      .mDateOfBirth(dateOfBirth)
      .mRelationshipStatus(relationshipStatus)
      .mDependents(dependents)
      .mHighestEducationAttained(highestEducationAttained)
      .mEmploymentStatus(employmentStatus)
      .mKycStatus(kycStatus)
      .mLastOkDate(lastOkDate)
      .mCreditRating(cr.rating)
      .mCreditSource(cr.source)
      .mCreditLimitCurrency(cl.currency)
      .mCreditLimitAmount(cl.amount)
      .saveMe()

    Some(createdCustomer)
  }

}

class MappedCustomer extends Customer with LongKeyedMapper[MappedCustomer] with IdPK with CreatedUpdated {

  def getSingleton = MappedCustomer

  object mCustomerId extends MappedUUID(this)

  object mUser extends MappedLongForeignKey(this, ResourceUser)
  object mBank extends DefaultStringField(this)

  object mNumber extends DefaultStringField(this)
  object mMobileNumber extends DefaultStringField(this)
  object mLegalName extends DefaultStringField(this)
  object mEmail extends MappedEmail(this, 200)
  object mFaceImageUrl extends DefaultStringField(this)
  object mFaceImageTime extends MappedDateTime(this)
  object mDateOfBirth extends MappedDateTime(this)
  object mRelationshipStatus extends DefaultStringField(this)
  object mDependents extends MappedInt(this)
  object mHighestEducationAttained  extends DefaultStringField(this)
  object mEmploymentStatus extends DefaultStringField(this)
  object mCreditRating extends MappedString(this, 100)
  object mCreditSource extends MappedString(this, 100)
  object mCreditLimitCurrency extends MappedString(this, 100)
  object mCreditLimitAmount extends MappedString(this, 100)
  object mKycStatus extends MappedBoolean(this)
  object mLastOkDate extends MappedDateTime(this)

  override def customerId: String = mCustomerId.get // id.toString
  override def bank: String = mBank.get
  override def number: String = mNumber.get
  override def mobileNumber: String = mMobileNumber.get
  override def legalName: String = mLegalName.get
  override def email: String = mEmail.get
  override def faceImage: CustomerFaceImage = new CustomerFaceImage {
    override def date: Date = mFaceImageTime.get
    override def url: String = mFaceImageUrl.get
  }
  override def dateOfBirth: Date = mDateOfBirth.get
  override def relationshipStatus: String = mRelationshipStatus.get
  override def dependents: Int = mDependents.get
  override def dobOfDependents: List[Date] = List(createdAt.get)
  override def highestEducationAttained: String = mHighestEducationAttained.get
  override def employmentStatus: String = mEmploymentStatus.get
  override def creditRating: CreditRating = new CreditRating {
    override def rating: String = mCreditRating.get
    override def source: String = mCreditSource.get
  }
  override def creditLimit: AmountOfMoney = new AmountOfMoney {
    override def currency: String = mCreditLimitCurrency.get
    override def amount: String = mCreditLimitAmount.get
  }
  override def kycStatus: Boolean = mKycStatus.get
  override def lastOkDate: Date = mLastOkDate.get
}

object MappedCustomer extends MappedCustomer with LongKeyedMetaMapper[MappedCustomer] {
  //one customer info per bank for each api user
  override def dbIndexes = UniqueIndex(mCustomerId) :: super.dbIndexes
}