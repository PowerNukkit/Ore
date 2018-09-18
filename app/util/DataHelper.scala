package util

import javax.inject.Inject

import scala.concurrent.{ExecutionContext, Future}

import db.access.ModelAccess
import db.impl.access.{ProjectBase, UserBase}
import db.{ModelService, ObjectId}
import discourse.OreDiscourseApi
import models.project.{Channel, Project, ProjectSettings, Version}
import models.user.User
import ore.project.factory.ProjectFactory

import com.google.common.base.Preconditions.checkArgument

/**
  * Utility class for performing some bulk actions on the application data.
  * Typically for testing.
  */
final class DataHelper @Inject()(
    implicit statusZ: StatusZ,
    service: ModelService,
    factory: ProjectFactory,
    forums: OreDiscourseApi
) {

  private val projects                       = ProjectBase.fromService(service)
  private val channels: ModelAccess[Channel] = this.service.access[Channel](classOf[Channel])
  private val versions: ModelAccess[Version] = this.service.access[Version](classOf[Version])
  private val users                          = UserBase.fromService(service)

  val Logger = play.api.Logger("DataHelper")

  /**
    * Resets the application to factory defaults.
    */
  def reset()(implicit ec: ExecutionContext): Unit = {
    if (sys.env.getOrElse(statusZ.SpongeEnv, "unknown") != "local") return
    Logger.info("Resetting Ore...")
    this.projects.all.map { projects =>
      Logger.info(s"Deleting ${projects.size} projects...")
      for (project <- projects) this.projects.delete(project)
    }
    this.users.size.map { size =>
      Logger.info(s"Deleting $size users...")
      this.users.removeAll()
    }
    Logger.info("Clearing disk...")
    FileUtils.deleteDirectory(this.factory.env.uploads)
    Logger.info("Done.")

  }

  /**
    * Fills the application with some dummy data.
    *
    * @param users Amount of users to create
    */
  def seed(users: Long, projects: Int, versions: Int, channels: Int)(
      implicit ec: ExecutionContext,
      service: ModelService
  ): Unit = {
    if (sys.env.getOrElse(statusZ.SpongeEnv, "unknown") != "local") return
    // Note: Dangerous as hell, handle with care
    Logger.info("---- Seeding Ore ----")

    checkArgument(channels <= Channel.Colors.size, "cannot make more channels than there are colors", "")
    this.factory.isPgpEnabled = false

    Logger.info("Resetting Ore")
    this.reset

    // Create some users.
    Logger.info("Seeding...")
    var projectNum = 0
    for (i <- 0L until users) {
      Logger.info(Math.ceil(i.toDouble / users.toDouble * 100D).toInt.toString + "%")
      this.users.add(User(id = ObjectId(i), name = s"User-$i")).map { user =>
        // Create some projects
        for (_ <- 0 until projects) {
          val pluginId = s"plugin$projectNum"
          this.projects
            .add(
              Project
                .Builder(this.service)
                .pluginId(pluginId)
                .ownerName(user.name)
                .ownerId(user.id.value)
                .name(s"Project$projectNum")
                .build()
            )
            .map { project =>
              project.updateSettings(ProjectSettings())
              // Now create some additional versions for this project
              var versionNum = 0
              for (k <- 0 until channels) {
                this.channels.add(new Channel(s"channel$k", Channel.Colors(k), project.id.value)).map { channel =>
                  for (l <- 0 until versions) {
                    this.versions
                      .add(
                        Version(
                          projectId = project.id.value,
                          versionString = versionNum.toString,
                          channelId = channel.id.value,
                          fileSize = 1,
                          hash = "none",
                          authorId = i,
                          fileName = "none",
                          signatureFileName = "none"
                        )
                      )
                      .map { version =>
                        versionNum += 1
                        if (l == 0) service.update(project.copy(recommendedVersionId = Some(version.id.value)))
                        else Future.unit
                      }
                  }
                }
              }
              projectNum += 1

            }

        }
      }
    }

    Logger.info("---- Seed complete ----")

  }

  def migrate(): Unit = {
    if (sys.env.getOrElse(statusZ.SpongeEnv, "unknown") != "local") return
    Unit
  }

}
