// Projects are the top-level entities on the UI.
//
// A project has a vertex set, an edge bundle, and any number of attributes,
// scalars and segmentations. It represents data stored in the tag system.
// The Project instances are short-lived, they are just a rich interface for
// querying and manipulating the tags.

package com.lynxanalytics.biggraph.controllers

import com.lynxanalytics.biggraph.{ bigGraphLogger => log }
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.graph_operations
import com.lynxanalytics.biggraph.graph_util.Timestamp
import com.lynxanalytics.biggraph.serving.User

import java.util.UUID
import play.api.libs.json.Json
import scala.util.{ Failure, Success, Try }
import scala.reflect.runtime.universe._

class ObsolateProject(val projectPath: SymbolPath)(implicit val tagRoot: TagRoot) {
  val projectName = projectPath.toString
  override def toString = projectName
  override def equals(p: Any) =
    p.isInstanceOf[ObsolateProject] && projectName == p.asInstanceOf[ObsolateProject].projectName
  override def hashCode = projectName.hashCode

  assert(projectName.nonEmpty, "Invalid project name: <empty string>")
  assert(!projectName.contains(ObsolateProject.separator), s"Invalid project name: $projectName")
  val rootDir: SymbolPath = SymbolPath("projects") / projectPath
  // Part of the state that needs to be checkpointed.
  val checkpointedDir: SymbolPath = rootDir / "checkpointed"

  private def checkpoints: Seq[String] = get(rootDir / "checkpoints") match {
    case "" => Seq()
    case x => x.split(java.util.regex.Pattern.quote(ObsolateProject.separator), -1)
  }
  private def checkpointIndex = get(rootDir / "checkpointIndex") match {
    case "" => 0
    case x => x.toInt
  }
  private def checkpointIndex_=(x: Int): Unit =
    set(rootDir / "checkpointIndex", x.toString)
  def checkpointCount = if (checkpoints.nonEmpty) checkpointIndex + 1 else 0
  def checkpointDir(i: Int): SymbolPath = {
    // We used to have absolute checkpoint paths. (Until LynxKite 1.2.0.)
    // To provide backward compatibility these are transformed into relative paths.
    // TODO: Eventually remove this code.
    val path = checkpoints(i)
    val relative = if (path.startsWith("projects/")) path.split("/", -1).last else path
    rootDir / "checkpoint" / relative
  }

  def copyCheckpoint(i: Int, destination: ObsolateProject): Unit = tagRoot.synchronized {
    assert(0 <= i && i < checkpointCount, s"Requested checkpoint $i out of $checkpointCount.")
    copy(destination)
    while (destination.checkpointCount > i + 1) {
      destination.undo
    }
  }

  def lastOperation = get(checkpointedDir / "lastOperation")

  def undo(): Unit = tagRoot.synchronized {
    assert(checkpointIndex > 0, s"Already at checkpoint $checkpointIndex.")
    checkpointIndex -= 1
    cp(checkpointDir(checkpointIndex), checkpointedDir)
  }

  def readACL: String = {
    if (isSegmentation) asSegmentation.parent.readACL
    else get(rootDir / "readACL")
  }
  def writeACL: String = {
    if (isSegmentation) asSegmentation.parent.writeACL
    else get(rootDir / "writeACL")
  }

  def isSegmentation = tagRoot.synchronized {
    val grandFather = rootDir.parent.parent
    grandFather.nonEmpty && (grandFather.name == 'segmentations)
  }
  def asSegmentation = tagRoot.synchronized {
    assert(isSegmentation, s"$projectName is not a segmentation")
    // If our parent is a top-level project, rootDir is like:
    //   project/parentName/checkpointed/segmentations/segmentationName/project
    val parentName = new SymbolPath(rootDir.drop(1).dropRight(4))
    val segmentationName = rootDir.dropRight(1).last.name
    ObsolateSegmentation(parentName, segmentationName)
  }

  def notes = get(checkpointedDir / "notes")

  implicit val fFEOperationSpec = Json.format[FEOperationSpec]
  implicit val fProjectOperationRequest = Json.format[ProjectOperationRequest]
  def lastOperationRequest = tagRoot.synchronized {
    existing(checkpointedDir / "lastOperationRequest").map {
      tag => Json.parse(get(tag)).as[ProjectOperationRequest]
    }
  }

  def vertexSet = tagRoot.synchronized {
    existing(checkpointedDir / "vertexSet")
      .flatMap(vsPath =>
        ObsolateProject.withErrorLogging(s"Couldn't resolve vertex set of project $this") {
          tagRoot.gUID(vsPath)
        })
      .getOrElse(null)
  }
  def edgeBundle = tagRoot.synchronized {
    existing(checkpointedDir / "edgeBundle")
      .flatMap(ebPath =>
        ObsolateProject.withErrorLogging(s"Couldn't resolve edge bundle of project $this") {
          tagRoot.gUID(ebPath)
        })
      .getOrElse(null)
  }

  def scalars = new ScalarHolder
  def vertexAttributes = new VertexAttributeHolder
  def edgeAttributes = new EdgeAttributeHolder
  def segmentations = segmentationNames.map(segmentation(_))
  def segmentation(name: String) = ObsolateSegmentation(projectPath, name)
  def segmentationNames = ls(checkpointedDir / "segmentations").map(_.last.name)

  def copy(to: ObsolateProject): Unit = cp(rootDir, to.rootDir)

  private def cp(from: SymbolPath, to: SymbolPath) = tagRoot.synchronized {
    existing(to).foreach(tagRoot.rm(_))
    tagRoot.cp(from, to)
  }

  private def existing(tag: SymbolPath): Option[SymbolPath] =
    if (tagRoot.exists(tag)) Some(tag) else None
  private def set(tag: SymbolPath, content: String): Unit = tagRoot.setTag(tag, content)
  private def get(tag: SymbolPath): String = tagRoot.synchronized {
    existing(tag).map(x => (tagRoot / x).content).getOrElse("")
  }
  private def ls(dir: SymbolPath) = tagRoot.synchronized {
    existing(dir).map(x => (tagRoot / x).ls).getOrElse(Nil).map(_.fullName)
  }

  abstract class Holder(dir: SymbolPath) extends Iterable[(String, UUID)] {
    def apply(name: String): UUID = {
      assert(tagRoot.exists(dir / name), s"$name does not exist in $dir")
      tagRoot.gUID(dir / name)
    }

    def iterator = tagRoot.synchronized {
      ls(dir)
        .flatMap { path =>
          val name = path.last.name
          ObsolateProject.withErrorLogging(s"Couldn't resolve $path") { apply(name) }
            .map(name -> _)
        }
        .iterator
    }

    def contains(x: String) = iterator.exists(_._1 == x)
  }
  class ScalarHolder extends Holder(checkpointedDir / "scalars")
  class VertexAttributeHolder extends Holder(checkpointedDir / "vertexAttributes")
  class EdgeAttributeHolder extends Holder(checkpointedDir / "edgeAttributes")
}

object ObsolateProject {
  val separator = "|"

  def apply(projectPath: SymbolPath)(implicit tagRoot: TagRoot): ObsolateProject =
    new ObsolateProject(projectPath)

  def fromPath(stringPath: String)(implicit tagRoot: TagRoot): ObsolateProject =
    new ObsolateProject(SymbolPath.parse(stringPath))

  def fromName(name: String)(implicit tagRoot: TagRoot): ObsolateProject =
    new ObsolateProject(SymbolPath(name))

  def withErrorLogging[T](message: String)(op: => T): Option[T] = {
    try {
      Some(op)
    } catch {
      case e: Throwable => {
        log.error(message, e)
        None
      }
    }
  }

  private def projects(tagRoot: TagRoot): Seq[ObsolateProject] = {
    val dirs = {
      val projectsRoot = SymbolPath("projects")
      if (tagRoot.exists(projectsRoot))
        (tagRoot / projectsRoot).ls.map(_.fullName)
      else
        Nil
    }
    // Do not list internal project names (starting with "!").
    dirs
      .map(p => ObsolateProject.fromName(p.path.last.name)(tagRoot))
      .filterNot(_.projectName.startsWith("!"))
  }

  private def getProjectState(project: ObsolateProject): CommonProjectState = {
    CommonProjectState(
      vertexSetGUID = Option(project.vertexSet),
      vertexAttributeGUIDs = project.vertexAttributes.toMap,
      edgeBundleGUID = Option(project.edgeBundle),
      edgeAttributeGUIDs = project.edgeAttributes.toMap,
      scalarGUIDs = project.scalars.toMap,
      segmentations =
        project.segmentationNames.map(
          name => name -> getSegmentationState(project.segmentation(name))).toMap,
      notes = project.notes)
  }

  private def getSegmentationState(seg: ObsolateSegmentation): SegmentationState = {
    SegmentationState(
      getProjectState(seg.project),
      Option(seg.belongsTo))
  }

  private def getSegmentationPath(oldFullName: String): Seq[String] = {
    getSegmentationPath(SymbolPath.parse(oldFullName).toList)
  }

  private def getSegmentationPath(oldFullPath: List[scala.Symbol]): List[String] = {
    oldFullPath match {
      case List(name) => List()
      case rootName :: 'checkpointed :: 'segmentations :: segName :: rest =>
        segName.name :: getSegmentationPath(rest)
    }
  }

  private def getRootState(
    project: ObsolateProject): RootProjectState = {

    RootProjectState(
      getProjectState(project),
      None,
      None,
      project.lastOperation,
      Some(
        project.lastOperationRequest
          .map {
            projectOperationRequest =>
              SubProjectOperation(
                getSegmentationPath(projectOperationRequest.project),
                projectOperationRequest.op)
          }
          .getOrElse(SubProjectOperation(Seq(), FEOperationSpec("No-operation", Map())))))

  }
  private def oldCheckpoints(p: ObsolateProject): Seq[ObsolateProject] = {
    val tmpDir = s"!tmp-$Timestamp"
    (0 until p.checkpointCount).map { i =>
      val tmp = ObsolateProject.fromName(s"$tmpDir-$i")(p.tagRoot)
      p.copyCheckpoint(i, tmp)
      tmp
    }
  }

  private def lastNewCheckpoint(
    oldCheckpoints: Seq[ObsolateProject], repo: CheckpointRepository): String = {
    oldCheckpoints.foldLeft("") {
      case (previousCheckpoint, project) =>
        val state = getRootState(project)
        repo.checkpointState(state, previousCheckpoint).checkpoint.get
    }
  }

  private def migrateOneProject(source: ObsolateProject, targetManager: MetaGraphManager): Unit = {
    val lastCp = lastNewCheckpoint(oldCheckpoints(source), targetManager.checkpointRepo)
    val frame = ProjectFrame.fromName(source.projectName)(targetManager)
    frame.setCheckpoint(lastCp)
    (0 until (source.checkpointCount - source.checkpointIndex - 1)).foreach {
      i => frame.undo()
    }
    frame.readACL = source.readACL
    frame.writeACL = source.writeACL
  }

  def migrateV1ToV2(v1TagRoot: TagRoot, targetManager: MetaGraphManager): Unit = {
    projects(v1TagRoot).foreach { project =>
      migrateOneProject(project, targetManager)
    }
  }
}

case class ObsolateSegmentation(parentPath: SymbolPath, name: String)(
    implicit tagRoot: TagRoot) {
  def parent = ObsolateProject(parentPath)
  val parentName = parent.projectName
  val path = SymbolPath("projects") / parentPath / "checkpointed" / "segmentations" / name
  def project = ObsolateProject(parentPath / "checkpointed" / "segmentations" / name / "project")

  def belongsTo = {
    ObsolateProject.withErrorLogging(s"Cannot get 'belongsTo' for $this") {
      tagRoot.gUID(path / "belongsTo")
    }.getOrElse(null)
  }
}

