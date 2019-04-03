package controllers.project

import java.nio.charset.StandardCharsets
import javax.inject.Inject

import scala.concurrent.ExecutionContext

import play.api.cache.AsyncCacheApi
import play.api.libs.json.JsValue
import play.api.mvc.{Action, AnyContent}
import play.utils.UriEncoding

import controllers.OreBaseController
import controllers.sugar.Bakery
import db.{Model, ModelService}
import db.access.ModelView
import db.impl.OrePostgresDriver.api._
import db.impl.schema.PageTable
import discourse.OreDiscourseApi
import form.OreForms
import form.project.PageSaveForm
import models.project.{Page, Project}
import models.user.{LoggedAction, UserActionLogger}
import ore.permission.Permission
import ore.{OreConfig, OreEnv, StatTracker}
import security.spauth.{SingleSignOnConsumer, SpongeAuthApi}
import util.StringUtils._
import views.html.projects.{pages => views}

import cats.data.OptionT
import cats.effect.IO
import cats.instances.option._
import cats.syntax.all._
import cats.instances.option._

/**
  * Controller for handling Page related actions.
  */
class Pages @Inject()(forms: OreForms, stats: StatTracker)(
    implicit val ec: ExecutionContext,
    bakery: Bakery,
    cache: AsyncCacheApi,
    sso: SingleSignOnConsumer,
    env: OreEnv,
    config: OreConfig,
    service: ModelService,
    auth: SpongeAuthApi,
    forums: OreDiscourseApi
) extends OreBaseController {

  private val self = controllers.project.routes.Pages

  private def PageEditAction(author: String, slug: String) =
    AuthedProjectAction(author, slug, requireUnlock = true).andThen(ProjectPermissionAction(Permission.EditPage))

  private val childPageQuery = {
    def childPageQueryFunction(parentSlug: Rep[String], childSlug: Rep[String]) = {
      val q           = TableQuery[PageTable]
      val parentPages = q.filter(_.slug.toLowerCase === parentSlug.toLowerCase).map(_.id)
      val childPage =
        q.filter(page => (page.parentId in parentPages) && page.slug.toLowerCase === childSlug.toLowerCase)
      childPage.take(1)
    }

    Compiled(childPageQueryFunction _)
  }

  def pageParts(page: String): List[String] =
    page.split("/").map(page => UriEncoding.decodePathSegment(page, StandardCharsets.UTF_8)).toList

  /**
    * Return the best guess of the page
    */
  def findPage(project: Model[Project], page: String): OptionT[IO, Model[Page]] = pageParts(page) match {
    case parent :: child :: Nil => OptionT(service.runDBIO(childPageQuery((parent, child)).result.headOption))
    case single :: Nil =>
      project.pages(ModelView.now(Page)).find(p => p.slug.toLowerCase === single.toLowerCase && p.parentId.isEmpty)
    case _ => OptionT.none[IO, Model[Page]]
  }

  def queryProjectPagesAndFindSpecific(
      project: Model[Project],
      page: String
  ): OptionT[IO, (Seq[(Model[Page], Seq[Model[Page]])], Model[Page])] =
    OptionT(
      projects.queryProjectPages(project).map { pages =>
        def pageEqual(name: String): Model[Page] => Boolean = _.slug.toLowerCase == name.toLowerCase
        def findUpper(name: String)                         = pages.find(t => pageEqual(name)(t._1))

        val res = pageParts(page) match {
          case parent :: child :: Nil => findUpper(parent).map(_._2).flatMap(_.find(pageEqual(child)))
          case single :: Nil          => findUpper(single).map(_._1)
          case _                      => None
        }

        res.tupleLeft(pages)
      }
    )

  /**
    * Displays the specified page.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @param page   Page name
    * @return View of page
    */
  def show(author: String, slug: String, page: String): Action[AnyContent] = ProjectAction(author, slug).asyncF {
    implicit request =>
      queryProjectPagesAndFindSpecific(request.project, page)
        .semiflatMap {
          case (pages, p) =>
            val pageCount = pages.size + pages.map(_._2.size).sum
            val parentPage =
              if (pages.map(_._1).contains(p)) None
              else pages.collectFirst { case (pp, subPage) if subPage.contains(p) => pp }
            this.stats.projectViewed(
              Ok(
                views.view(
                  request.data,
                  request.scoped,
                  Model.unwrapNested[Seq[(Model[Page], Seq[Page])]](pages),
                  p,
                  Model.unwrapNested(parentPage),
                  pageCount
                )
              )
            )
        }
        .getOrElse(notFound)
  }

  /**
    * Displays the documentation page editor for the specified project and page
    * name.
    *
    * @param author Owner name
    * @param slug   Project slug
    * @param pageName   Page name
    * @return Page editor
    */
  def showEditor(author: String, slug: String, pageName: String): Action[AnyContent] =
    PageEditAction(author, slug).asyncF { implicit request =>
      queryProjectPagesAndFindSpecific(request.project, pageName).fold(notFound) {
        case (pages, p) =>
          val pageCount  = pages.size + pages.map(_._2.size).sum
          val parentPage = pages.collectFirst { case (pp, page) if page.contains(p) => pp }
          Ok(
            views.view(
              request.data,
              request.scoped,
              Model.unwrapNested[Seq[(Model[Page], Seq[Page])]](pages),
              p,
              Model.unwrapNested(parentPage),
              pageCount,
              editorOpen = true
            )
          )
      }
    }

  /**
    * Renders the submitted page content and returns the result.
    *
    * @return Rendered content
    */
  def showPreview(): Action[JsValue] = Action(parse.json) { implicit request =>
    Ok(Page.render((request.body \ "raw").as[String]))
  }

  /**
    * Saves changes made on a documentation page.
    *
    * @param author Owner name
    * @param slug   Project slug
    * @param page   Page name
    * @return Project home
    */
  def save(author: String, slug: String, page: String): Action[PageSaveForm] =
    PageEditAction(author, slug).asyncF(parse.form(forms.PageEdit, onErrors = FormError(self.show(author, slug, page)))) {
      implicit request =>
        val pageData = request.body
        val content  = pageData.content
        val project  = request.project
        val parentId = pageData.parentId

        //noinspection ComparingUnrelatedTypes
        service.runDBIO(project.rootPages(ModelView.raw(Page)).result).flatMap { rootPages =>
          if (parentId.isDefined && !rootPages
                .filter(_.name != Page.homeName)
                .exists(p => parentId.contains(p.id.value))) {
            IO.pure(BadRequest("Invalid parent ID."))
          } else {
            if (page == Page.homeName && (!content.exists(_.length >= Page.minLength))) {
              IO.pure(Redirect(self.show(author, slug, page)).withError("error.minLength"))
            } else {
              val parts = page.split("/")

              val created = if (parts.size == 2) {
                service
                  .runDBIO(
                    project
                      .pages(ModelView.later(Page))
                      .find(equalsIgnoreCase(_.slug, parts(0)))
                      .map(_.id)
                      .result
                      .headOption
                  )
                  .flatMap { parentId =>
                    val pageName = pageData.name.getOrElse(parts(1))
                    project.getOrCreatePage(pageName, parentId, content)
                  }
              } else {
                val pageName = pageData.name.getOrElse(parts(0))
                project.getOrCreatePage(pageName, parentId, content)
              }

              created
                .flatMap { createdPage =>
                  content.fold(IO.pure(createdPage)) { newPage =>
                    val oldPage = createdPage.contents
                    UserActionLogger.log(
                      request.request,
                      LoggedAction.ProjectPageEdited,
                      createdPage.id,
                      newPage,
                      oldPage
                    ) *> createdPage.updateContentsWithForum(newPage)
                  }
                }
                .as(Redirect(self.show(author, slug, page)))
            }
          }
        }
    }

  /**
    * Irreversibly deletes the specified Page from the specified Project.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @param page   Page name
    * @return Redirect to Project homepage
    */
  def delete(author: String, slug: String, page: String): Action[AnyContent] =
    PageEditAction(author, slug).asyncF { request =>
      findPage(request.project, page).value.flatMap { optionPage =>
        optionPage
          .fold(IO.unit)(p => service.delete(p).void)
          .as(Redirect(routes.Projects.show(author, slug)))
      }
    }

}
