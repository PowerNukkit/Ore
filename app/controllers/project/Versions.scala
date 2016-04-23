package controllers.project

import javax.inject.Inject

import controllers.BaseController
import controllers.project.routes.{Versions => self}
import models.project.Project.PendingProject
import models.project.{Channel, Project, Version}
import ore.Statistics
import ore.permission.EditVersions
import ore.project.{InvalidPluginFileException, ProjectFactory, ProjectFiles}
import play.api.i18n.MessagesApi
import play.api.libs.ws.WSClient
import util.form.Forms
import views.html.projects.{versions => views}

import scala.util.{Failure, Success}

/**
  * Controller for handling Version related actions.
  */
class Versions @Inject()(override val messagesApi: MessagesApi, implicit val ws: WSClient) extends BaseController {

  private def VersionEditAction(author: String, slug: String) = {
    AuthedProjectAction(author, slug) andThen ProjectPermissionAction(EditVersions)
  }

  /**
    * Shows the specified version view page.
    *
    * @param author        Owner name
    * @param slug          Project slug
    * @param channelName   Channel name
    * @param versionString Version name
    * @return Version view
    */
  def show(author: String, slug: String, channelName: String, versionString: String) = {
    ProjectAction(author, slug, countView = true) { implicit request =>
      val project = request.project
      project.channels.withName(channelName) match {
        case None => NotFound
        case Some(channel) => channel.versions.withName(versionString) match {
          case None => NotFound
          case Some(version) => Ok(views.detail(project, channel, version))
        }
      }
    }
  }

  /**
    * Saves the specified Version's description.
    *
    * @param author        Project owner
    * @param slug          Project slug
    * @param channelName   Version channel
    * @param versionString Version name
    * @return View of Version
    */
  def saveDescription(author: String, slug: String, channelName: String, versionString: String) = {
    VersionEditAction(author, slug) { implicit request =>
      request.project.channels.withName(channelName) match {
        case None => NotFound("Channel not found.")
        case Some(channel) => channel.versions.withName(versionString) match {
          case None => NotFound("Version not found.")
          case Some(version) =>
            version.description = Forms.VersionDescription.bindFromRequest.get.trim
            Redirect(self.show(author, slug, channelName, versionString))
        }
      }
    }
  }

  /**
    * Displays the "versions" tab within a Project view.
    *
    * @param author   Owner of project
    * @param slug     Project slug
    * @param channels Visible channels
    * @return View of project
    */
  def showList(author: String, slug: String, channels: Option[String]) = {
    ProjectAction(author, slug, countView = true) { implicit request =>
      val project = request.project
      val allChannels = project.channels.seq
      var channelNames = if (channels.isDefined) Some(channels.get.toLowerCase.split(",")) else None
      var visibleChannels = allChannels
      if (channelNames.isDefined) {
        visibleChannels = allChannels.filter(c => channelNames.get.contains(c.name.toLowerCase))
      }

      // Don't pass "visible channels" if all channels are visible
      val versions = if (allChannels.equals(visibleChannels)) project.versions.seq else project.versionsIn(visibleChannels)
      if (channelNames.isDefined && allChannels.map(_.name).toSet.subsetOf(channelNames.get.toSet)) {
        channelNames = None
      }

      Ok(views.list(project, allChannels, versions, channelNames))
    }
  }

  /**
    * Shows the creation form for new versions on projects.
    *
    * @param author Owner of project
    * @param slug   Project slug
    * @return Version creation view
    */
  def showCreator(author: String, slug: String) = {
    VersionEditAction(author, slug) { implicit request =>
      val project = request.project
      Ok(views.create(project, None, Some(project.channels.values.toSeq), showFileControls = true))
    }
  }

  /**
    * Uploads a new version for a project for further processing.
    *
    * @param author Owner name
    * @param slug   Project slug
    * @return Version create page (with meta)
    */
  def upload(author: String, slug: String) = {
    VersionEditAction(author, slug) { implicit request =>
      request.body.asMultipartFormData.get.file("pluginFile") match {
        case None => Redirect(self.showCreator(author, slug)).flashing("error" -> "Missing file")
        case Some(tmpFile) =>
          // Initialize plugin file
          ProjectFactory.initUpload(tmpFile.ref, tmpFile.filename, request.user) match {
            case Failure(thrown) => if (thrown.isInstanceOf[InvalidPluginFileException]) {
              // PEBKAC
              Redirect(self.showCreator(author, slug))
                .flashing("error" -> "Invalid plugin file.")
            } else {
              throw thrown
            }
            case Success(plugin) =>
              val project = request.project
              if (!plugin.meta.get.getId.equals(project.pluginId)) {
                Redirect(self.showCreator(author, slug))
                  .flashing("error" -> "The uploaded plugin ID must match your project's plugin ID.")
              } else {
                // Create version from meta file
                val version = Version.fromMeta(project, plugin)
                if (version.exists) {
                  Redirect(self.showCreator(author, slug))
                    .flashing("error" -> "Found a duplicate file in project. Plugin files may only be uploaded once.")
                } else {

                  // Get first channel for default
                  val channelName: String = project.channels.values.head.name

                  // Cache for later use
                  Version.setPending(author, slug, channelName, version, plugin)
                  Redirect(self.showCreatorWithMeta(author, slug, channelName, version.versionString))
                }
              }
          }
      }
    }
  }

  /**
    * Displays the "version create" page with the associated plugin meta-data.
    *
    * @param author        Owner name
    * @param slug          Project slug
    * @param channelName   Channel name
    * @param versionString Version name
    * @return Version create view
    */
  def showCreatorWithMeta(author: String, slug: String, channelName: String, versionString: String) = {
    Authenticated { implicit request =>
      // Get pending version
      Version.getPending(author, slug, channelName, versionString) match {
        case None => Redirect(self.showCreator(author, slug))
        case Some(pendingVersion) =>
          // Get project
          pendingOrReal(author, slug) match {
            case None => Redirect(self.showCreator(author, slug))
            case Some(p) => p match {
              case pending: PendingProject =>
                Ok(views.create(pending.project, Some(pendingVersion), None, showFileControls = false))
              case real: Project =>
                Ok(views.create(real, Some(pendingVersion), Some(real.channels.seq), showFileControls = true))
            }
          }
      }
    }
  }

  private def pendingOrReal(author: String, slug: String): Option[Any] = {
    // Returns either a PendingProject or existing Project
    Project.withSlug(author, slug) match {
      case None => Project.getPending(author, slug)
      case Some(project) => Some(project)
    }
  }

  /**
    * Completes the creation of the specified pending version or project if
    * first version.
    *
    * @param author        Owner name
    * @param slug          Project slug
    * @param channelName   Channel name
    * @param versionString Version name
    * @return New version view
    */
  def create(author: String, slug: String, channelName: String, versionString: String) = {
    Authenticated { implicit request =>
      // First get the pending Version
      Version.getPending(author, slug, channelName, versionString) match {
        case None => Redirect(self.showCreator(author, slug)) // Not found
        case Some(pendingVersion) =>
          // Get submitted channel
          Forms.ChannelEdit.bindFromRequest.fold(
            hasErrors => Redirect(self.showCreatorWithMeta(author, slug, channelName, versionString))
              .flashing("error" -> hasErrors.errors.head.message),

            channelData => {
              // Channel is valid
              pendingVersion.channelName = channelData.name.trim
              pendingVersion.channelColor = channelData.color

              // Check for pending project
              Project.getPending(author, slug) match {
                case None =>
                  // No pending project, create version for existing project
                  withProject(author, slug) { project =>
                    val existingChannel = project.channels.withName(pendingVersion.channelName).orNull
                    var channelResult: Either[String, Channel] = Right(existingChannel)
                    if (existingChannel == null) channelResult = channelData.addTo(project)
                    channelResult.fold(
                      error => Redirect(self.showCreatorWithMeta(author, slug, channelName, versionString))
                        .flashing("error" -> error),
                      channel => {
                        pendingVersion.complete.get
                        Redirect(self.show(author, slug, pendingVersion.channelName, versionString))
                      }
                    )
                  }
                case Some(pendingProject) =>
                  // Found a pending project, create it with first version
                  pendingProject.complete.get
                  Redirect(routes.Projects.show(author, slug))
              }
            }
          )
      }
    }
  }

  /**
    * Deletes the specified version and returns to the version page.
    *
    * @param author        Owner name
    * @param slug          Project slug
    * @param channelName   Channel name
    * @param versionString Version name
    * @return Versions page
    */
  def delete(author: String, slug: String, channelName: String, versionString: String) = {
    VersionEditAction(author, slug) { implicit request =>
      val project = request.project
      project.channels.withName(channelName) match {
        case None => NotFound("Channel not found.")
        case Some(channel) => channel.versions.withName(versionString) match {
          case None => NotFound("Version not found.")
          case Some(version) =>
            channel.deleteVersion(version, project)
            Redirect(self.showList(author, slug, None))
        }
      }
    }
  }

  /**
    * Sends the specified Project Version to the client.
    *
    * @param author        Project owner
    * @param slug          Project slug
    * @param channelName   Version channel
    * @param versionString Version string
    * @return Sent file
    */
  def download(author: String, slug: String, channelName: String, versionString: String) = {
    ProjectAction(author, slug) { implicit request =>
      val project = request.project
      project.channels.withName(channelName) match {
        case None => NotFound("Channel not found.")
        case Some(channel) => channel.versions.withName(versionString) match {
          case None => NotFound("Version not found.")
          case Some(version) =>
            Statistics.versionDownloaded(project, version)
            Ok.sendFile(ProjectFiles.uploadPath(author, slug, versionString, channelName).toFile)
        }
      }
    }
  }

  /**
    * Sends the specified project's current recommended version to the client.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return Sent file
    */
  def downloadRecommended(author: String, slug: String) = {
    ProjectAction(author, slug) { implicit request =>
      val project = request.project
      val rv = project.recommendedVersion
      Statistics.versionDownloaded(project, rv)
      Ok.sendFile(ProjectFiles.uploadPath(author, project.name, rv.versionString, rv.channel.name).toFile)
    }
  }

}
