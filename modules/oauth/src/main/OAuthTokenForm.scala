package lila.oauth

import org.joda.time.DateTime
import play.api.data.*
import play.api.data.Forms.*
import reactivemongo.api.bson.BSONObjectID

import lila.common.Bearer
import lila.common.Form.cleanText
import lila.user.User

object OAuthTokenForm:

  private val scopesField = list(nonEmptyText.verifying(OAuthScope.byKey.contains))

  private val descriptionField = cleanText(minLength = 3, maxLength = 140)

  def create = Form(
    mapping(
      "description" -> descriptionField,
      "scopes"      -> scopesField
    )(Data.apply)(unapply)
  )

  case class Data(description: String, scopes: List[String])

  def adminChallengeTokens = Form(
    mapping(
      "description" -> descriptionField,
      "users" -> cleanText
        .verifying("No more than 500 users", _.split(',').size <= 500)
    )(AdminChallengeTokensData.apply)(unapply)
  )

  case class AdminChallengeTokensData(description: String, usersStr: String):

    def usernames = usersStr.split(',').flatMap(UserStr.read).distinct.filter(User.couldBeUsername).toList
