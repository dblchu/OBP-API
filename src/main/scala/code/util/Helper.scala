package code.util

import net.liftweb.common._
import net.liftweb.util.{Mailer, Props}
import net.liftweb.util.Helpers._

import net.liftweb.json.JsonAST._
import net.liftweb.json.Extraction._
import net.liftweb.json.Printer._


object Helper{

  /**
    *
    *
    */

  // If we need to return a string and all good, return an empty string
  // rule of silence http://www.linfo.org/rule_of_silence.html
  val SILENCE_IS_GOLDEN = ""


  /**
   * A css selector that will (unless you have a template containing an element
   * name i_am_an_id_that_should_never_exist) have no effect. Useful when you have
   * a method that needs to return a CssSel but in some code paths don't want to do anything.
   */
  val NOOP_SELECTOR = "#i_am_an_id_that_should_never_exist" #> ""

  def generatePermalink(name: String): String = {
    name.trim.toLowerCase.replace("-","").replaceAll(" +", " ").replaceAll(" ", "-")
  }

  /**
   * Useful for integrating failure message in for comprehensions.
   *
   * Normally a for comprehension might look like:
   *
   * for {
   *   account <- Account.find(...) ?~ "Account not found"
   *   if(account.isPublic)
   * } yield account
   *
   * The issue here is that we can't easily add an error message to describe why this might fail (i.e
   * if the account not public)
   *
   * Using this function, we can instead write
   *
   * for {
   *   account <- Account.find(...) ?~ "Account not found"
   *   accountIsPublic <- booleanToBox(account.isPublic, "Account is not public")
   * } yield account
   *
   * It's not ideal, but it works.
   *
   * @param statement A boolean condition
   * @param msg The message to give the Failure option if "statement" is false
   * @return A box that is Full if the condition was met, and a Failure(msg) if not
   */
  def booleanToBox(statement: => Boolean, msg: String): Box[Unit] = {
    if(statement)
      Full()
    else
      Failure(msg)
  }

  def booleanToBox(statement: => Boolean): Box[Unit] = {
    if(statement)
      Full()
    else
      Empty
  }

  val deprecatedJsonGenerationMessage = "json generation handled elsewhere as it changes from api version to api version"

  /**
   * Converts a number representing the smallest unit of a currency into a big decimal formatted according to the rules of
   * that currency. E.g. JPY: 1000 units (yen) => 1000, EUR: 1000 units (cents) => 10.00
   */
  def smallestCurrencyUnitToBigDecimal(units : Long, currencyCode : String) = {
    BigDecimal(units, currencyDecimalPlaces(currencyCode))
  }

  /**
   * Returns the number of decimal places a currency has. E.g. "EUR" -> 2, "JPY" -> 0
   * @param currencyCode
   * @return
   */
  def currencyDecimalPlaces(currencyCode : String) = {
    //this data was sourced from Wikipedia, so it might not all be correct,
    //and some banking systems may still retain different units (e.g. CZK?)
    //notable it doesn't cover non-traditional currencies (e.g. cryptocurrencies)
    currencyCode match {
      //TODO: handle MRO and MGA, which are non-decimal
      case "CZK" | "JPY" | "KRW" => 0
      case "KWD" | "OMR" => 3
      case _ => 2
    }
  }

  /**
   * E.g.
   * amount: BigDecimal("12.45"), currencyCode : "EUR" => 1245
   * amount: BigDecimal("9034"), currencyCode : "JPY" => 9034
   */
  def convertToSmallestCurrencyUnits(amount : BigDecimal, currencyCode : String) : Long = {
    val decimalPlaces = Helper.currencyDecimalPlaces(currencyCode)

    (amount * BigDecimal("10").pow(decimalPlaces)).toLong
  }


  /*
  Returns a pretty json representation of the input
   */
  def prettyJson(input: JValue) : String = {
    implicit val formats = net.liftweb.json.DefaultFormats
    pretty(render(decompose(input)))
  }

  /**
    * extract clean redirect url from input value, because input may have some parameters, such as the following examples  <br/> 
    * eg1: http://localhost:8082/oauthcallback?....--> http://localhost:8082 <br/> 
    * eg2: http://localhost:8016?oautallback?=3NLMGV ...--> http://localhost:8016
    *
    * @param input a long url with parameters 
    * @return clean redirect url
    */
  def extractCleanRedirectURL(input: String): Box[String] = {
    /**
      * pattern eg1: http://xxxxxx?oautxxxx  -->http://xxxxxx
      * pattern eg2: https://xxxxxx/oautxxxx -->http://xxxxxx
      */
    //Note: the pattern should be : val  pattern = "(https?):\\/\\/(.*)(?=((\\/)|(\\?))oauthcallback*)".r, but the OAuthTest is different, so add the following logic
    val pattern = "(https?):\\/\\/(.*)(?=((\\/)|(\\?))oauth*)".r
    val validRedirectURL = pattern findFirstIn input
    // Now for the OAuthTest, the redirect format is : http://localhost:8016?oauth_token=G5AEA2U1WG404EGHTIGBHKRR4YJZAPPHWKOMNEEV&oauth_verifier=53018
    // It is not the normal case: http://localhost:8082/oauthcallback?oauth_token=LUDKELGJXRDOC1AK1X1TOYIXM5W1AORFJT5KE43B&oauth_verifier=14062
    // So add the split function to select the first value; eg: Array(http://localhost:8082, thcallback) --> http://localhost:8082
    val extractCleanURL = validRedirectURL.getOrElse("").split("/oauth")(0) 
    Full(extractCleanURL)
  }

  /**
    * check the redirect url is valid with default values.
    */
  def isValidInternalRedirectUrl(url: String) : Boolean = {
    //set the default value is "/" and "/oauth/authorize"
    val validUrls = List("/","/oauth/authorize")

    //case1: OBP-API login: url = "/"
    //case2: API-Explore oauth login: url = "/oauth/authorize?oauth_token=V0JTCDYXWUNTXDZ3VUDNM1HE3Q1PZR2WJ4PURXQA&logUserOut=false"
    val extractCleanURL = url.split("\\?oauth_token")(0)

    validUrls.contains(extractCleanURL)
  }
}