package controllers

import javax.inject.Inject

import controllers.routes.{Users => self}
import db.ModelService
import db.impl.service.UserBase
import db.impl.service.UserBase.ORDER_PROJECTS
import form.OreForms
import forums.{DiscourseApi, DiscourseSSO}
import play.api.i18n.MessagesApi
import play.api.mvc.{Security, _}
import util.{FakeUser, OreConfig}
import views.{html => views}

class Users @Inject()(val fakeUser: FakeUser,
                      val forms: OreForms,
                      val auth: DiscourseSSO,
                      implicit override val messagesApi: MessagesApi,
                      implicit override val config: OreConfig,
                      implicit override val forums: DiscourseApi,
                      implicit override val service: ModelService) extends BaseController {

  /**
    * Redirect to forums for SSO authentication and then back here again.
    *
    * @param sso  Incoming payload from forums
    * @param sig  Incoming signature from forums
    * @return     Logged in home
    */
  def logIn(sso: Option[String], sig: Option[String], returnPath: Option[String]) = Action { implicit request =>
    val baseUrl = this.config.app.getString("baseUrl").get
    if (this.fakeUser.isEnabled) {
      users.getOrCreate(this.fakeUser)
      redirectBack(returnPath.getOrElse(request.path), this.fakeUser.username)
    } else if (sso.isEmpty || sig.isEmpty) {
      Redirect(this.auth.toForums(baseUrl + "/login")).flashing("url" -> returnPath.getOrElse(request.path))
    } else {
      // Decode SSO payload and get Ore user
      val user = this.auth.authenticate(sso.get, sig.get)
      redirectBack(request2flash.get("url").get, user.username)
    }
  }

  private def redirectBack(url: String, username: String) = {
    Redirect(config.app.getString("baseUrl").get + url).withSession(Security.username -> username)
  }

  /**
    * Clears the current session.
    *
    * @return Home page
    */
  def logOut(returnPath: Option[String]) = Action { implicit request =>
    Redirect(config.app.getString("baseUrl").get + returnPath.getOrElse(request.path))
      .withNewSession.flashing("noRedirect" -> "true")
  }

  /**
    * Shows the User page for the user with the specified username.
    *
    * @param username   Username to lookup
    * @return           View of user page
    */
  def show(username: String) = Action { implicit request =>
    users.withName(username).map(u => Ok(views.user(u))).getOrElse(NotFound)
  }

  /**
    * Submits a change to the specified user's tagline.
    *
    * @param username   User to update
    * @return           View of user page
    */
  def saveTagline(username: String) = {
    Authenticated { implicit request =>
      val user = request.user
      val tagline = this.forms.UserTagline.bindFromRequest.get.trim
      if (tagline.length > this.config.users.getInt("max-tagline-len").get) {
        Redirect(self.show(user.username)).flashing("error" -> "Tagline is too long.")
      } else {
        user.tagline = tagline
        Redirect(self.show(user.username))
      }
    }
  }

  /**
    * Shows a list of [[models.user.User]]s that have created a
    * [[models.project.Project]].
    */
  def showAuthors(sort: Option[String], page: Option[Int]) = Action { implicit request =>
    val ordering = sort.getOrElse(ORDER_PROJECTS)
    val p = page.getOrElse(1)
    Ok(views.authors(this.users.authors(ordering, p), ordering, p))
  }

}
