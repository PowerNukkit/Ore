package models.project

import java.sql.Timestamp

import com.google.common.base.Preconditions
import db.ModelService
import db.impl.ModelKeys._
import db.impl.OrePostgresDriver.api._
import db.impl.action.VersionActions
import db.impl.{ChannelTable, OreModel, VersionDownloadsTable, VersionTable}
import db.meta.{Actions, Bind, HasMany}
import models.statistic.VersionDownload
import ore.permission.scope.ProjectScope
import ore.project.Dependency
import ore.project.util.PluginFile
import org.apache.commons.io.FileUtils
import play.twirl.api.Html

import scala.annotation.meta.field
import scala.collection.JavaConverters._

/**
  * Represents a single version of a Project.
  *
  * @param id               Unique identifier
  * @param createdAt        Instant of creation
  * @param versionString    Version string
  * @param dependenciesIds  List of plugin dependencies with the plugin ID and
  *                         version separated by a ':'
  * @param _description     User description of version
  * @param assets           Path to assets directory within plugin
  * @param projectId        ID of project this version belongs to
  * @param channelId        ID of channel this version belongs to
  */
@Actions(classOf[VersionActions])
@HasMany(Array(classOf[VersionDownload]))
case class Version(override val id: Option[Int] = None,
                   override val createdAt: Option[Timestamp] = None,
                   override val projectId: Int,
                   versionString: String,
                   dependenciesIds: List[String] = List(),
                   assets: Option[String] = None,
                   channelId: Int,
                   fileSize: Long,
                   hash: String,
                   @(Bind @field) private var _description: Option[String] = None,
                   @(Bind @field) private var _downloads: Int = 0,
                   @(Bind @field) private var _isReviewed: Boolean = false)
                   extends OreModel(id, createdAt)
                     with ProjectScope { self =>

  override type M = Version
  override type T = VersionTable
  override type A = VersionActions

  def this(versionString: String, dependencies: List[String], description: String,
           assets: String, projectId: Int, channelId: Int, fileSize: Long, hash: String) = {
    this(None, None, projectId, versionString, dependencies,
         Option(assets), channelId, fileSize, hash, Option(description))
  }

  def this(versionString: String, dependencies: List[String],
           description: String, assets: String, projectId: Int, fileSize: Long, hash: String) = {
    this(versionString, dependencies, description, assets, projectId, -1, fileSize, hash)
  }

  /**
    * Returns the name of this Channel.
    *
    * @return Name of channel
    */
  def name: String = this.versionString

  /**
    * Returns the channel this version belongs to.
    *
    * @return Channel
    */
  def channel: Channel = this.service.access[ChannelTable, Channel](classOf[Channel]).get(this.channelId).get

  /**
    * Returns the channel this version belongs to from the specified collection
    * of channels if present.
    *
    * @param channels   Channels to search
    * @return           Channel if present, None otherwise
    */
  def findChannelFrom(channels: Seq[Channel]): Option[Channel] = Defined {
    channels.find(_.id.get == this.channelId)
  }

  /**
    * Returns this Version's description.
    *
    * @return Version description
    */
  def description: Option[String] = this._description

  /**
    * Sets this Version's description.
    *
    * @param _description Version description
    */
  def description_=(_description: String) = {
    Preconditions.checkArgument(_description.length <= Page.MaxLength, "content too long", "")
    this._description = Some(_description)
    if (isDefined) update(Description)
  }

  /**
    * Returns this Version's markdown description in HTML.
    *
    * @return Description in html
    */
  def descriptionHtml: Html
  = this.description.map(str => Html(Page.MarkdownProcessor.markdownToHtml(str))).getOrElse(Html(""))

  /**
    * Returns true if this version has been reviewed by the moderation staff.
    *
    * @return True if reviewed
    */
  def isReviewed: Boolean = this._isReviewed

  /**
    * Sets whether this version has been reviewed by the moderation staff.
    *
    * @param reviewed True if reviewed
    */
  def setReviewed(reviewed: Boolean) = {
    this._isReviewed = reviewed
    if (isDefined) update(IsReviewed)
  }

  /**
    * Returns this Versions plugin dependencies.
    *
    * @return Plugin dependencies
    */
  def dependencies: List[Dependency] = {
    for (depend <- this.dependenciesIds) yield {
      val data = depend.split(":")
      Dependency(data(0), data(1))
    }
  }

  def downloads: Int = this._downloads

  def addDownload() = {
    this._downloads += 1
    update(Downloads)
  }

  def downloadEntries = this.getRelated[VersionDownloadsTable, VersionDownload](classOf[VersionDownload])

  /**
    * Returns a human readable file size for this Version.
    *
    * @return Human readable file size
    */
  def humanFileSize: String = FileUtils.byteCountToDisplaySize(this.fileSize)

  /**
    * Returns true if a project ID is defined on this Model, there is no
    * matching hash in the Project, and there is no duplicate version with
    * the same name in the Project.
    *
    * @return True if exists
    */
  def exists: Boolean = {
    this.projectId > -1 && (service.await(this.actions.hashExists(this.projectId, this.hash)).get
      || this.project.versions.exists(_.versionString.toLowerCase === this.versionString.toLowerCase))
  }

  override def copyWith(id: Option[Int], theTime: Option[Timestamp]) = this.copy(id = id, createdAt = theTime)
  override def hashCode() = this.id.hashCode
  override def equals(o: Any) = o.isInstanceOf[Version] && o.asInstanceOf[Version].id.get == this.id.get

}

object Version {

  /**
    * Returns all Versions that have not been reviewed by the moderation staff.
    *
    * @return All versions not reviewed
    */
  def notReviewed(implicit service: ModelService): Seq[Version]
  = service.access[VersionTable, Version](classOf[Version]).filterNot(_.isReviewed)

  /**
    * Creates a new Version from the specified PluginMetadata.
    *
    * @param project  Project this version belongs to
    * @param plugin   PluginFile
    * @return         New Version
    */
  def fromMeta(project: Project, plugin: PluginFile): Version = {
    // TODO: asset parsing
    val meta = plugin.meta.get
    val depends = for (depend <- meta.getRequiredDependencies.asScala) yield depend.getId + ":" + depend.getVersion
    val path = plugin.path
    new Version(
      meta.getVersion, depends.toList, meta.getDescription, "",
      project.id.getOrElse(-1), path.toFile.length, plugin.md5
    )
  }

}
