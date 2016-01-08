// Projects are the top-level entities on the UI.
//
// A project has a vertex set, an edge bundle, and any number of attributes,
// scalars and segmentations. A segmentation itself can also be looked at as a project.
// It has its own vertex set, edge bundle, attributes, scalars and is connected to the
// base project via belongsTo edge bundle.
//
// The complete state of a root project - a project which is not a segmentation of a base project -
// is completely represented by the immutable RootProjectState class.
//
// We can optionally store a RootProjectState as a checkpoint. Checkpoints are globally unique
// (within a Kite instance) identifiers that can be used to recall a project state. Checkpointed
// project states also encapsulate the full operation history of the state as they have references
// to their parent state checkpoint and the operation that was used to get from the parent to the
// child. Thus checkpoints form a directed tree.
//
// Project frames represent named project states. Basically each project on the UI is
// represented via a project frame. A project frame is uniquely identified by a project name
// and contains informations like the checkpoint representing the current state of the project and
// high level state independent meta information like access control. Project frames are persisted
// using tags. ProjectFrame instances are short-lived, they are just a rich interface for
// querying and manipulating the underlying tag tree.

package com.lynxanalytics.biggraph.controllers

import com.lynxanalytics.biggraph.{ bigGraphLogger => log }
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.graph_operations
import com.lynxanalytics.biggraph.graph_util.Timestamp
import com.lynxanalytics.biggraph.serving.User

import java.io.File
import java.util.UUID
import org.apache.commons.io.FileUtils
import play.api.libs.json
import play.api.libs.json.Json
import scala.reflect.runtime.universe._

sealed abstract class ElementKind(kindName: String) {
  def /(name: String): String = {
    s"$kindName/$name"
  }
}
object VertexAttributeKind extends ElementKind("vertex attribute")
object EdgeAttributeKind extends ElementKind("edge attribute")
object ScalarKind extends ElementKind("scalar")
object SegmentationKind extends ElementKind("segmentation")

// Captures the part of the state that is common for segmentations and root projects.
case class CommonProjectState(
  vertexSetGUID: Option[UUID],
  vertexAttributeGUIDs: Map[String, UUID],
  edgeBundleGUID: Option[UUID],
  edgeAttributeGUIDs: Map[String, UUID],
  scalarGUIDs: Map[String, UUID],
  segmentations: Map[String, SegmentationState],
  notes: String,
  elementNotes: Option[Map[String, String]]) // Option for compatibility.
object CommonProjectState {
  val emptyState = CommonProjectState(None, Map(), None, Map(), Map(), Map(), "", Some(Map()))
}

// Complete state of a root project.
case class RootProjectState(
    state: CommonProjectState,
    checkpoint: Option[String],
    previousCheckpoint: Option[String],
    lastOperationDesc: String,
    // This will be set exactly when previousCheckpoint is set.
    lastOperationRequest: Option[SubProjectOperation]) {
}
object RootProjectState {
  val emptyState = RootProjectState(CommonProjectState.emptyState, Some(""), None, "", None)
}

// Complete state of segmentation.
case class SegmentationState(
  state: CommonProjectState,
  belongsToGUID: Option[UUID])
object SegmentationState {
  val emptyState = SegmentationState(CommonProjectState.emptyState, None)
}

// Rich interface for looking at project states.
sealed trait ProjectViewer {
  val rootState: RootProjectState
  val state: CommonProjectState
  implicit val manager: MetaGraphManager

  lazy val vertexSet: VertexSet =
    state.vertexSetGUID.map(manager.vertexSet(_)).getOrElse(null)
  lazy val vertexAttributes: Map[String, Attribute[_]] =
    state.vertexAttributeGUIDs.mapValues(manager.attribute(_))
  def vertexAttributeNames[T: TypeTag] = vertexAttributes.collect {
    case (name, attr) if typeOf[T] =:= typeOf[Nothing] || attr.is[T] => name
  }.toSeq.sorted
  lazy val edgeBundle: EdgeBundle =
    state.edgeBundleGUID.map(manager.edgeBundle(_)).getOrElse(null)
  lazy val edgeAttributes: Map[String, Attribute[_]] =
    state.edgeAttributeGUIDs.mapValues(manager.attribute(_))
  def edgeAttributeNames[T: TypeTag] = edgeAttributes.collect {
    case (name, attr) if typeOf[T] =:= typeOf[Nothing] || attr.is[T] => name
  }.toSeq.sorted
  lazy val scalars: Map[String, Scalar[_]] =
    state.scalarGUIDs.mapValues(manager.scalar(_))
  def scalarNames[T: TypeTag] = scalars.collect {
    case (name, scalar) if typeOf[T] =:= typeOf[Nothing] || scalar.is[T] => name
  }.toSeq.sorted

  lazy val segmentationMap: Map[String, SegmentationViewer] =
    state.segmentations
      .map { case (name, state) => name -> new SegmentationViewer(this, name) }
  def segmentation(name: String) = segmentationMap(name)

  def getVertexAttributeNote(name: String) = getElementNote(VertexAttributeKind, name)
  def getEdgeAttributeNote(name: String) = getElementNote(EdgeAttributeKind, name)
  def getScalarNote(name: String) = getElementNote(ScalarKind, name)
  def getElementNote(kind: ElementKind, name: String) =
    state.elementNotes.getOrElse(Map()).getOrElse(kind / name, "")

  def offspringPath: Seq[String]
  def offspringViewer(path: Seq[String]): ProjectViewer =
    if (path.isEmpty) this
    else segmentation(path.head).offspringViewer(path.tail)

  def editor: ProjectEditor
  def rootCheckpoint: String = rootState.checkpoint.get

  val isSegmentation: Boolean
  def asSegmentation: SegmentationViewer

  // Methods for conversion to FE objects.
  private def feScalar(name: String)(implicit epm: EntityProgressManager): Option[FEAttribute] = {
    if (scalars.contains(name)) {
      Some(ProjectViewer.feEntity(scalars(name), name, getScalarNote(name)))
    } else {
      None
    }
  }

  def toListElementFE(projectName: String)(implicit epm: EntityProgressManager): FEProjectListElement = {
    FEProjectListElement(
      projectName,
      state.notes,
      feScalar("vertex_count"),
      feScalar("edge_count"))
  }

  // Returns the FE attribute representing the seq of members for
  // each segment in a segmentation. None in root projects.
  protected def getFEMembers()(implicit epm: EntityProgressManager): Option[FEAttribute]

  def sortedSegmentations: List[SegmentationViewer] =
    segmentationMap.toList.sortBy(_._1).map(_._2)

  def toFE(projectName: String)(implicit epm: EntityProgressManager): FEProject = {
    val vs = Option(vertexSet).map(_.gUID.toString).getOrElse("")
    val eb = Option(edgeBundle).map(_.gUID.toString).getOrElse("")
    def feList(
      things: Iterable[(String, TypedEntity[_])],
      kind: ElementKind) = {
      things.toSeq.sortBy(_._1).map {
        case (name, e) => ProjectViewer.feEntity(e, name, getElementNote(kind, name))
      }.toList
    }

    FEProject(
      name = projectName,
      vertexSet = vs,
      edgeBundle = eb,
      notes = state.notes,
      scalars = feList(scalars, ScalarKind),
      vertexAttributes = feList(vertexAttributes, VertexAttributeKind) ++ getFEMembers,
      edgeAttributes = feList(edgeAttributes, EdgeAttributeKind),
      segmentations = sortedSegmentations.map(_.toFESegmentation(projectName)),
      // To be set by the ProjectFrame for root projects.
      undoOp = "",
      redoOp = "",
      readACL = "",
      writeACL = "")
  }

  def allOffspringFESegmentations(
    rootName: String, rootRelativePath: String = "")(
      implicit epm: EntityProgressManager): List[FESegmentation] = {
    sortedSegmentations.flatMap { segmentation =>
      segmentation.toFESegmentation(rootName, rootRelativePath) +:
        segmentation.allOffspringFESegmentations(
          rootName, rootRelativePath + segmentation.segmentationName + ProjectFrame.separator)
    }
  }
}
object ProjectViewer {
  def feEntity[T](
    e: TypedEntity[T],
    name: String,
    note: String,
    isInternal: Boolean = false)(implicit epm: EntityProgressManager): FEAttribute = {
    val canBucket = Seq(typeOf[Double], typeOf[String]).exists(e.typeTag.tpe <:< _)
    val canFilter = Seq(typeOf[Double], typeOf[String], typeOf[Long], typeOf[Vector[Any]])
      .exists(e.typeTag.tpe <:< _)
    val isNumeric = Seq(typeOf[Double]).exists(e.typeTag.tpe <:< _)
    FEAttribute(
      e.gUID.toString,
      name,
      e.typeTag.tpe.toString.replace("com.lynxanalytics.biggraph.graph_api.", ""),
      note,
      canBucket,
      canFilter,
      isNumeric,
      isInternal,
      epm.computeProgress(e))
  }
}

// Specialized ProjectViewer for RootProjectStates.
class RootProjectViewer(val rootState: RootProjectState)(implicit val manager: MetaGraphManager)
    extends ProjectViewer {
  val state = rootState.state
  def editor: RootProjectEditor = new RootProjectEditor(rootState)

  val isSegmentation = false
  def asSegmentation: SegmentationViewer = ???
  def offspringPath: Seq[String] = Nil

  protected def getFEMembers()(implicit epm: EntityProgressManager): Option[FEAttribute] = None
}

// Specialized ProjectViewer for SegmentationStates.
class SegmentationViewer(val parent: ProjectViewer, val segmentationName: String)
    extends ProjectViewer {

  implicit val manager = parent.manager
  val rootState = parent.rootState
  val segmentationState: SegmentationState = parent.state.segmentations(segmentationName)
  val state = segmentationState.state

  override val isSegmentation = true
  override val asSegmentation = this
  def offspringPath: Seq[String] = parent.offspringPath :+ segmentationName

  def editor: SegmentationEditor = parent.editor.segmentation(segmentationName)

  lazy val belongsTo: EdgeBundle =
    segmentationState.belongsToGUID.map(manager.edgeBundle(_)).getOrElse(null)

  lazy val belongsToAttribute: Attribute[Vector[ID]] = {
    val segmentationIds = graph_operations.IdAsAttribute.run(vertexSet)
    val reversedBelongsTo = graph_operations.ReverseEdges.run(belongsTo)
    val aop = graph_operations.AggregateByEdgeBundle(graph_operations.Aggregator.AsVector[ID]())
    aop(aop.connection, reversedBelongsTo)(aop.attr, segmentationIds).result.attr
  }

  lazy val membersAttribute: Attribute[Vector[ID]] = {
    val parentIds = graph_operations.IdAsAttribute.run(parent.vertexSet)
    val aop = graph_operations.AggregateByEdgeBundle(graph_operations.Aggregator.AsVector[ID]())
    aop(aop.connection, belongsTo)(aop.attr, parentIds).result.attr
  }

  override protected def getFEMembers()(implicit epm: EntityProgressManager): Option[FEAttribute] =
    Some(ProjectViewer.feEntity(membersAttribute, "#members", note = "", isInternal = true))

  val equivalentUIAttributeTitle = s"segmentation[$segmentationName]"

  def equivalentUIAttribute()(implicit epm: EntityProgressManager): FEAttribute =
    ProjectViewer.feEntity(belongsToAttribute, equivalentUIAttributeTitle, note = "")

  def toFESegmentation(
    rootName: String,
    rootRelativePath: String = "")(implicit epm: EntityProgressManager): FESegmentation = {
    val bt =
      if (belongsTo == null) null
      else belongsTo.gUID.toString
    FESegmentation(
      rootRelativePath + segmentationName,
      rootName + ProjectFrame.separator + rootRelativePath + segmentationName,
      bt,
      equivalentUIAttribute)
  }
}

// The CheckpointRepository's job is to persist project states to checkpoints.
// There is one special checkpoint, "", which is the root of the checkpoint tree.
// It corresponds to an empty project state with no parent state.
object CheckpointRepository {
  private val checkpointFilePrefix = "save-"

  implicit val fFEOperationSpec = Json.format[FEOperationSpec]
  implicit val fSubProjectOperation = Json.format[SubProjectOperation]

  // We need to define this manually because of the cyclic reference.
  implicit val fSegmentationState = new json.Format[SegmentationState] {
    def reads(j: json.JsValue): json.JsResult[SegmentationState] = {
      json.JsSuccess(SegmentationState(
        jsonToCommonProjectState(j \ "state"),
        (j \ "belongsToGUID").as[Option[UUID]]))
    }
    def writes(o: SegmentationState): json.JsValue =
      Json.obj(
        "state" -> commonProjectStateToJSon(o.state),
        "belongsToGUID" -> o.belongsToGUID)
  }
  implicit val fCommonProjectState = Json.format[CommonProjectState]
  implicit val fRootProjectState = Json.format[RootProjectState]

  private def commonProjectStateToJSon(state: CommonProjectState): json.JsValue = Json.toJson(state)
  private def jsonToCommonProjectState(j: json.JsValue): CommonProjectState =
    j.as[CommonProjectState]

  val startingState = RootProjectState.emptyState
}
class CheckpointRepository(val baseDir: String) {
  import CheckpointRepository.fRootProjectState
  import CheckpointRepository.checkpointFilePrefix

  val baseDirFile = new File(baseDir)
  baseDirFile.mkdirs

  def checkpointFileName(checkpoint: String): File =
    new File(baseDirFile, s"${checkpointFilePrefix}${checkpoint}")

  def allCheckpoints: Map[String, RootProjectState] =
    baseDirFile
      .list
      .filter(_.startsWith(checkpointFilePrefix))
      .map(fileName => fileName.drop(checkpointFilePrefix.length))
      .map(cp => cp -> readCheckpoint(cp))
      .toMap

  def saveCheckpointedState(checkpoint: String, state: RootProjectState): Unit = {
    val dumpFile = new File(baseDirFile, s"dump-$checkpoint")
    val finalFile = checkpointFileName(checkpoint)
    FileUtils.writeStringToFile(
      dumpFile,
      Json.prettyPrint(Json.toJson(state)),
      "utf8")
    dumpFile.renameTo(finalFile)
  }

  def checkpointState(state: RootProjectState, prevCheckpoint: String): RootProjectState = {
    if (state.checkpoint.nonEmpty) {
      // Already checkpointed.
      state
    } else {
      val withPrev = state.copy(previousCheckpoint = Some(prevCheckpoint))
      val checkpoint = Timestamp.toString
      saveCheckpointedState(checkpoint, withPrev)
      withPrev.copy(checkpoint = Some(checkpoint))
    }
  }

  def readCheckpoint(checkpoint: String): RootProjectState = {
    if (checkpoint == "") {
      CheckpointRepository.startingState
    } else {
      Json.parse(FileUtils.readFileToString(checkpointFileName(checkpoint), "utf8"))
        .as[RootProjectState].copy(checkpoint = Some(checkpoint))
    }
  }
}

// This class is used to provide a convenient interface for entity map-like project data as
// scalars and attributes. The instantiator needs to know how to do the actual updates on the
// underlying state.
abstract class StateMapHolder[T <: MetaGraphEntity] extends collection.Map[String, T] {
  protected def getMap: Map[String, T]
  protected def updateMap(newMap: Map[String, UUID]): Unit
  def validate(name: String, entity: T): Unit

  def updateEntityMap(newMap: Map[String, T]): Unit =
    updateMap(newMap.mapValues(_.gUID))
  // Skip name validation. Special-name entities can be set through this method.
  def set(name: String, entity: T) = {
    if (entity == null) {
      updateEntityMap(getMap - name)
    } else {
      validate(name, entity)
      updateEntityMap(getMap + (name -> entity))
    }
  }

  def update(name: String, entity: T) = {
    ProjectFrame.validateName(name)
    set(name, entity)
  }

  // Implementing the map interface
  def get(key: String) = getMap.get(key)
  def iterator = getMap.iterator
  def +[T1 >: T](kv: (String, T1)) = getMap + kv
  def -(key: String) = getMap - key
}

// A mutable wrapper around a CommonProjectState. A ProjectEditor can be editing the state
// of a particular segmentation or a root project. The actual state is always stored and modified
// on the root level. E.g. if you take a RootProjectEditor, get a particular SegmentationEditor
// from it and then do some modifications using the SegmentationEditor then the state of the
// original RootProjectEditor will change as well.
sealed trait ProjectEditor {
  implicit val manager: MetaGraphManager
  def state: CommonProjectState
  def state_=(newState: CommonProjectState): Unit

  def viewer: ProjectViewer

  def rootState: RootProjectState

  def vertexSet = viewer.vertexSet
  def vertexSet_=(e: VertexSet): Unit = {
    updateVertexSet(e)
  }
  protected def updateVertexSet(e: VertexSet): Unit = {
    if (e != vertexSet) {
      edgeBundle = null
      vertexAttributes = Map()
      state = state.copy(segmentations = Map())
      if (e != null) {
        state = state.copy(vertexSetGUID = Some(e.gUID))
        scalars("vertex_count") = graph_operations.Count.run(e)
      } else {
        state = state.copy(vertexSetGUID = None)
        scalars("vertex_count") = null
      }
    }
  }
  def setVertexSet(e: VertexSet, idAttr: String): Unit = {
    vertexSet = e
    vertexAttributes(idAttr) = graph_operations.IdAsAttribute.run(e)
  }

  def edgeBundle = viewer.edgeBundle
  def edgeBundle_=(e: EdgeBundle) = {
    if (e != edgeBundle) {
      assert(e == null || vertexSet != null, s"No vertex set")
      assert(e == null || e.srcVertexSet == vertexSet, s"Edge bundle does not match vertex set")
      assert(e == null || e.dstVertexSet == vertexSet, s"Edge bundle does not match vertex set")
      edgeAttributes = Map()
    }
    if (e != null) {
      state = state.copy(edgeBundleGUID = Some(e.gUID))
      scalars("edge_count") = graph_operations.Count.run(e)
    } else {
      state = state.copy(edgeBundleGUID = None)
      scalars("edge_count") = null
    }
  }

  def newVertexAttribute(name: String, attr: Attribute[_], note: String = null) = {
    vertexAttributes(name) = attr
    setElementNote(VertexAttributeKind, name, note)
  }
  def deleteVertexAttribute(name: String) = {
    vertexAttributes(name) = null
    setElementNote(VertexAttributeKind, name, null)
  }

  def newEdgeAttribute(name: String, attr: Attribute[_], note: String = null) = {
    edgeAttributes(name) = attr
    setElementNote(EdgeAttributeKind, name, note)
  }
  def deleteEdgeAttribute(name: String) = {
    edgeAttributes(name) = null
    setElementNote(EdgeAttributeKind, name, null)
  }

  def newScalar(name: String, scalar: Scalar[_], note: String = null) = {
    scalars(name) = scalar
    setElementNote(ScalarKind, name, note)
  }
  def deleteScalar(name: String) = {
    scalars(name) = null
    setElementNote(ScalarKind, name, null)
  }

  def setElementNote(kind: ElementKind, name: String, note: String) = {
    val notes = state.elementNotes.getOrElse(Map())
    if (note == null) {
      state = state.copy(elementNotes = Some(notes - kind / name))
    } else {
      state = state.copy(elementNotes = Some(notes + (kind / name -> note)))
    }
  }

  def vertexAttributes =
    new StateMapHolder[Attribute[_]] {
      protected def getMap = viewer.vertexAttributes
      protected def updateMap(newMap: Map[String, UUID]) =
        state = state.copy(vertexAttributeGUIDs = newMap)
      def validate(name: String, attr: Attribute[_]): Unit = {
        assert(
          attr.vertexSet == viewer.vertexSet,
          s"Vertex attribute $name does not match vertex set")
      }
    }
  def vertexAttributes_=(attrs: Map[String, Attribute[_]]) =
    vertexAttributes.updateEntityMap(attrs)
  def vertexAttributeNames[T: TypeTag] = viewer.vertexAttributeNames[T]

  def edgeAttributes =
    new StateMapHolder[Attribute[_]] {
      protected def getMap = viewer.edgeAttributes
      protected def updateMap(newMap: Map[String, UUID]) =
        state = state.copy(edgeAttributeGUIDs = newMap)
      def validate(name: String, attr: Attribute[_]): Unit = {
        assert(
          attr.vertexSet == viewer.edgeBundle.idSet,
          s"Edge attribute $name does not match edge bundle")
      }
    }
  def edgeAttributes_=(attrs: Map[String, Attribute[_]]) =
    edgeAttributes.updateEntityMap(attrs)
  def edgeAttributeNames[T: TypeTag] = viewer.edgeAttributeNames[T]

  def scalars =
    new StateMapHolder[Scalar[_]] {
      protected def getMap = viewer.scalars
      protected def updateMap(newMap: Map[String, UUID]) =
        state = state.copy(scalarGUIDs = newMap)
      def validate(name: String, scalar: Scalar[_]): Unit = {}
    }
  def scalars_=(newScalars: Map[String, Scalar[_]]) =
    scalars.updateEntityMap(newScalars)
  def scalarNames[T: TypeTag] = viewer.scalarNames[T]

  def segmentations = segmentationNames.map(segmentation(_))
  def segmentation(name: String) = new SegmentationEditor(this, name)
  def segmentationNames = state.segmentations.keys.toSeq.sorted
  def deleteSegmentation(name: String) = {
    state = state.copy(segmentations = state.segmentations - name)
    setElementNote(SegmentationKind, name, null)
  }
  def newSegmentation(name: String, seg: SegmentationState) = {
    state = state.copy(
      segmentations = state.segmentations + (name -> seg))
  }

  def offspringEditor(path: Seq[String]): ProjectEditor =
    if (path.isEmpty) this
    else segmentation(path.head).offspringEditor(path.tail)

  def rootEditor: RootProjectEditor

  val isSegmentation: Boolean
  def asSegmentation: SegmentationEditor

  def notes = state.notes
  def notes_=(n: String) = state = state.copy(notes = n)

  def pullBackEdges(injection: EdgeBundle): Unit = {
    val op = graph_operations.PulledOverEdges()
    val newEB = op(op.originalEB, edgeBundle)(op.injection, injection).result.pulledEB
    pullBackEdges(edgeBundle, edgeAttributes.toIndexedSeq, newEB, injection)
  }
  def pullBackEdges(
    origEdgeBundle: EdgeBundle,
    origEAttrs: Seq[(String, Attribute[_])],
    newEdgeBundle: EdgeBundle,
    pullBundle: EdgeBundle): Unit = {

    assert(pullBundle.properties.compliesWith(EdgeBundleProperties.partialFunction),
      s"Not a partial function: $pullBundle")
    assert(pullBundle.srcVertexSet.gUID == newEdgeBundle.idSet.gUID,
      s"Wrong source: $pullBundle")
    assert(pullBundle.dstVertexSet.gUID == origEdgeBundle.idSet.gUID,
      s"Wrong destination: $pullBundle")

    edgeBundle = newEdgeBundle

    for ((name, attr) <- origEAttrs) {
      edgeAttributes(name) =
        graph_operations.PulledOverVertexAttribute.pullAttributeVia(attr, pullBundle)
    }
  }

  def pullBack(pullBundle: EdgeBundle): Unit = {
    assert(pullBundle.properties.compliesWith(EdgeBundleProperties.partialFunction),
      s"Not a partial function: $pullBundle")
    assert(pullBundle.dstVertexSet.gUID == vertexSet.gUID,
      s"Wrong destination: $pullBundle")
    val origVS = vertexSet
    val origVAttrs = vertexAttributes.toIndexedSeq
    val origEB = edgeBundle
    val origEAttrs = edgeAttributes.toIndexedSeq
    val origBelongsTo: Option[EdgeBundle] =
      if (isSegmentation) Some(asSegmentation.belongsTo) else None
    val origSegmentations = state.segmentations
    updateVertexSet(pullBundle.srcVertexSet)

    for ((name, attr) <- origVAttrs) {
      vertexAttributes(name) =
        graph_operations.PulledOverVertexAttribute.pullAttributeVia(attr, pullBundle)
    }

    if (origEB != null) {
      val iop = graph_operations.InducedEdgeBundle()
      val induction = iop(
        iop.srcMapping, graph_operations.ReverseEdges.run(pullBundle))(
          iop.dstMapping, graph_operations.ReverseEdges.run(pullBundle))(
            iop.edges, origEB).result
      pullBackEdges(origEB, origEAttrs, induction.induced, induction.embedding)
    }

    for ((segName, segState) <- origSegmentations) {
      newSegmentation(segName, segState)
      val seg = segmentation(segName)
      val op = graph_operations.InducedEdgeBundle(induceDst = false)
      seg.belongsTo = op(
        op.srcMapping, graph_operations.ReverseEdges.run(pullBundle))(
          op.edges, seg.belongsTo).result.induced
    }

    if (isSegmentation) {
      val seg = asSegmentation
      val op = graph_operations.InducedEdgeBundle(induceSrc = false)
      seg.belongsTo = op(
        op.dstMapping, graph_operations.ReverseEdges.run(pullBundle))(
          op.edges, origBelongsTo.get).result.induced
    }
  }

  def setLastOperationDesc(n: String): Unit
  def setLastOperationRequest(n: SubProjectOperation): Unit
}

// Specialized project editor for a RootProjectState.
class RootProjectEditor(
    initialState: RootProjectState)(
        implicit val manager: MetaGraphManager) extends ProjectEditor {
  var rootState: RootProjectState = initialState.copy(checkpoint = None)

  def state = rootState.state
  def state_=(newState: CommonProjectState): Unit = {
    rootState = rootState.copy(state = newState)
  }

  def viewer = new RootProjectViewer(rootState)

  def rootEditor: RootProjectEditor = this

  def checkpoint = rootState.checkpoint
  def checkpoint_=(n: Option[String]) =
    rootState = rootState.copy(checkpoint = n)

  def lastOperationDesc = rootState.lastOperationDesc
  def lastOperationDesc_=(n: String) =
    rootState = rootState.copy(lastOperationDesc = n)
  def setLastOperationDesc(n: String) = lastOperationDesc = n

  def lastOperationRequest = rootState.lastOperationRequest
  def lastOperationRequest_=(n: SubProjectOperation) =
    rootState = rootState.copy(lastOperationRequest = Option(n))
  def setLastOperationRequest(n: SubProjectOperation) = lastOperationRequest = n

  val isSegmentation = false
  def asSegmentation: SegmentationEditor = ???
}

// Specialized editor for a SegmentationState.
class SegmentationEditor(
    val parent: ProjectEditor,
    val segmentationName: String) extends ProjectEditor {

  implicit val manager = parent.manager

  {
    // If this segmentation editor is called with new segmentationName then we
    // create the corresponding SegmentationState in the project state's segmentations field.
    val oldSegs = parent.state.segmentations
    if (!oldSegs.contains(segmentationName)) {
      ProjectFrame.validateName(segmentationName)
      parent.state = parent.state.copy(
        segmentations = oldSegs + (segmentationName -> SegmentationState.emptyState))
    }
  }

  def segmentationState = parent.state.segmentations(segmentationName)
  def segmentationState_=(newState: SegmentationState): Unit = {
    val pState = parent.state
    parent.state = pState.copy(
      segmentations = pState.segmentations + (segmentationName -> newState))
  }
  def state = segmentationState.state
  def state_=(newState: CommonProjectState): Unit = {
    segmentationState = segmentationState.copy(state = newState)
  }

  def viewer = parent.viewer.segmentation(segmentationName)

  def rootState = parent.rootState

  def rootEditor = parent.rootEditor

  override val isSegmentation = true
  override val asSegmentation: SegmentationEditor = this

  def belongsTo = viewer.belongsTo
  def belongsTo_=(e: EdgeBundle): Unit = {
    segmentationState = segmentationState.copy(belongsToGUID = Some(e.gUID))
    scalars.set("!coverage", graph_operations.Coverage.run(e).srcCoverage)
    scalars.set("!nonEmpty", graph_operations.Coverage.run(e).dstCoverage)
    scalars.set("!belongsToEdges", graph_operations.Count.run(e))
  }

  override protected def updateVertexSet(e: VertexSet): Unit = {
    if (e != vertexSet) {
      super.updateVertexSet(e)
      val op = graph_operations.EmptyEdgeBundle()
      belongsTo = op(op.src, parent.vertexSet)(op.dst, e).result.eb
    }
  }

  def setLastOperationDesc(n: String) =
    parent.setLastOperationDesc(s"$n on $segmentationName")

  def setLastOperationRequest(n: SubProjectOperation) =
    parent.setLastOperationRequest(n.copy(path = segmentationName +: n.path))
}

// Represents a mutable, named project. It can be seen as a modifiable pointer into the
// checkpoint tree with some additional metadata. ProjectFrame's data is persisted using tags.
class ProjectFrame(path: SymbolPath)(
    implicit manager: MetaGraphManager) extends ProjectDirectory(path) {
  val projectName = path.toString
  assert(!projectName.contains(ProjectFrame.separator), s"Invalid project name: $projectName")

  // Current checkpoint of the project
  def checkpoint: String = {
    assert(exists, s"$this does not exist.")
    get(rootDir / "checkpoint")
  }
  private def checkpoint_=(x: String): Unit = set(rootDir / "checkpoint", x)

  // The farthest checkpoint available in the current redo sequence
  private def farthestCheckpoint: String = get(rootDir / "farthestCheckpoint")
  private def farthestCheckpoint_=(x: String): Unit = set(rootDir / "farthestCheckpoint", x)

  // The next checkpoint in the current redo sequence if a redo is available
  private def nextCheckpoint: Option[String] = get(rootDir / "nextCheckpoint") match {
    case "" => None
    case x => Some(x)
  }
  private def nextCheckpoint_=(x: Option[String]): Unit =
    set(rootDir / "nextCheckpoint", x.getOrElse(""))

  override def exists = manager.tagExists(rootDir / "checkpoint")

  def undo(): Unit = manager.synchronized {
    nextCheckpoint = Some(checkpoint)
    val state = currentState
    checkpoint = currentState.previousCheckpoint.get
  }
  def redo(): Unit = manager.synchronized {
    val next = nextCheckpoint.get
    checkpoint = next
    val reverseCheckpoints = Stream.iterate(farthestCheckpoint) {
      next => getCheckpointState(next).previousCheckpoint.get
    }
    nextCheckpoint = reverseCheckpoints.takeWhile(_ != next).lastOption
  }

  def setCheckpoint(cp: String): Unit = manager.synchronized {
    checkpoint = cp
    farthestCheckpoint = cp
    nextCheckpoint = None
  }

  // Initializes a new project. One needs to call this to make the initial preparations
  // for a project in the tag tree.
  def initialize: Unit = manager.synchronized {
    checkpoint = ""
    nextCheckpoint = None
    farthestCheckpoint = ""
  }

  private def getCheckpointState(checkpoint: String): RootProjectState =
    manager.checkpointRepo.readCheckpoint(checkpoint)

  def currentState: RootProjectState = getCheckpointState(checkpoint)

  def nextState: Option[RootProjectState] = nextCheckpoint.map(getCheckpointState(_))

  def viewer = new RootProjectViewer(currentState)

  def toListElementFE()(implicit epm: EntityProgressManager) = {
    try {
      viewer.toListElementFE(projectName)
    } catch {
      case ex: Throwable =>
        log.warn(s"Could not list $projectName:", ex)
        FEProjectListElement(
          name = projectName,
          error = Some(ex.getMessage)
        )
    }
  }

  def subproject = SubProject(this, Seq())
}
object ProjectFrame {
  val separator = "|"
  val quotedSeparator = java.util.regex.Pattern.quote(ProjectFrame.separator)

  def fromName(rootProjectName: String)(implicit metaManager: MetaGraphManager): ProjectFrame = {
    validateName(rootProjectName, "Project name", allowSlash = true)
    new ProjectFrame(SymbolPath.parse(rootProjectName))
  }

  def validateName(name: String, what: String = "Name",
                   allowSlash: Boolean = false,
                   allowEmpty: Boolean = false): Unit = {
    assert(allowEmpty || name.nonEmpty, s"$what cannot be empty.")
    assert(!name.startsWith("!"), s"$what ($name) cannot start with '!'.")
    assert(!name.contains(separator), s"$what ($name) cannot contain '$separator'.")
    assert(allowSlash || !name.contains("/"), s"$what ($name) cannot contain '/'.")
  }
}

// Represents a named but not necessarily root project. A SubProject is identifed by a ProjectFrame
// representing the named root project and a sequence of segmentation names which show how one
// should climb down the project tree.
// When referring to SubProjects via a single string, we use the format:
//   RootProjectName|Seg1Name|Seg2Name|...
case class SubProject(val frame: ProjectFrame, val path: Seq[String]) {
  def viewer = frame.viewer.offspringViewer(path)
  def fullName = (frame.projectName +: path).mkString(ProjectFrame.separator)
  def toFE()(implicit epm: EntityProgressManager): FEProject = {
    val raw = viewer.toFE(fullName)
    if (path.isEmpty) {
      raw.copy(
        undoOp = frame.currentState.lastOperationDesc,
        redoOp = frame.nextState.map(_.lastOperationDesc).getOrElse(""),
        readACL = frame.readACL,
        writeACL = frame.writeACL)
    } else raw
  }
}
object SubProject {
  def parsePath(projectName: String)(implicit metaManager: MetaGraphManager): SubProject = {
    val nameElements = projectName.split(ProjectFrame.quotedSeparator, -1)
    new SubProject(ProjectFrame.fromName(nameElements.head), nameElements.tail)
  }
}

// May be a directory or a project frame.
class ProjectDirectory(val path: SymbolPath)(
    implicit manager: MetaGraphManager) {
  override def toString = path.toString
  override def equals(p: Any) =
    p.isInstanceOf[ProjectDirectory] && toString == p.asInstanceOf[ProjectDirectory].toString
  override def hashCode = toString.hashCode
  val rootDir: SymbolPath = ProjectDirectory.root / path

  def exists = manager.tagExists(rootDir)

  protected def existing(tag: SymbolPath): Option[SymbolPath] =
    if (manager.tagExists(tag)) Some(tag) else None
  protected def set(tag: SymbolPath, content: String): Unit = manager.setTag(tag, content)
  protected def get(tag: SymbolPath): String = manager.synchronized {
    existing(tag).map(manager.getTag(_)).get
  }
  protected def get(tag: SymbolPath, default: String): String = manager.synchronized {
    existing(tag).map(manager.getTag(_)).getOrElse(default)
  }

  def readACL: String = get(rootDir / "readACL", "*")
  def readACL_=(x: String): Unit = set(rootDir / "readACL", x)

  def writeACL: String = get(rootDir / "writeACL", "*")
  def writeACL_=(x: String): Unit = set(rootDir / "writeACL", x)

  def assertReadAllowedFrom(user: User): Unit = {
    assert(readAllowedFrom(user), s"User $user does not have read access to $this.")
  }
  def assertWriteAllowedFrom(user: User): Unit = {
    assert(writeAllowedFrom(user), s"User $user does not have write access to $this.")
  }
  def readAllowedFrom(user: User): Boolean = {
    // Write access also implies read access.
    user.isAdmin || writeAllowedFrom(user) || aclContains(readACL, user)
  }
  def writeAllowedFrom(user: User): Boolean = {
    user.isAdmin || aclContains(writeACL, user)
  }

  def aclContains(acl: String, user: User): Boolean = {
    // The ACL is a comma-separated list of email addresses with '*' used as a wildcard.
    // We translate this to a regex for checking.
    val regex = acl.replace(" ", "").replace(".", "\\.").replace(",", "|").replace("*", ".*")
    user.email.matches(regex)
  }

  def remove(): Unit = manager.synchronized {
    existing(rootDir).foreach(manager.rmTag(_))
    log.info(s"A project has been discarded: $rootDir")
  }

  private def cp(from: SymbolPath, to: SymbolPath) = manager.synchronized {
    existing(to).foreach(manager.rmTag(_))
    manager.cpTag(from, to)
  }
  def copy(to: ProjectDirectory): Unit = cp(rootDir, to.rootDir)

  def parent = if (path.size > 1) Some(new ProjectDirectory(path.init)) else None
  def parents: Iterable[ProjectDirectory] = parent ++ parent.toSeq.flatMap(_.parents)
  def isProject = new ProjectFrame(path).exists

  // Returns the list of all directories contained in this directory.
  def listDirectoriesRecursively: Seq[ProjectDirectory] = {
    assert(!isProject, s"$this is not a directory.")
    val (dirs, projects) = listDirectoriesAndProjects
    dirs ++ dirs.flatMap(_.listDirectoriesRecursively)
  }

  // Returns the list of all projects contained in this directory.
  def listProjectsRecursively: Seq[ProjectFrame] = {
    assert(!isProject, s"$this is not a directory.")
    val (dirs, projects) = listDirectoriesAndProjects
    projects ++ dirs.flatMap(_.listProjectsRecursively)
  }

  // Lists directories and projects inside this directory.
  def listDirectoriesAndProjects: (Seq[ProjectDirectory], Seq[ProjectFrame]) = {
    assert(!isProject, s"$this is not a directory.")
    val rooted = ProjectDirectory.root / path
    if (manager.tagExists(rooted)) {
      val tags = manager.lsTag(rooted).filter(manager.tagIsDir(_))
      val unrooted = tags.map(path => new SymbolPath(path.drop(ProjectDirectory.root.size)))
      val (projects, dirs) = unrooted.partition(tag => new ProjectFrame(tag).exists)
      (dirs.map(new ProjectDirectory(_)), projects.map(new ProjectFrame(_)))
    } else (Nil, Nil)
  }
}
object ProjectDirectory {
  val root = SymbolPath("projects")

  def rootDirectory(implicit metaManager: MetaGraphManager) = new ProjectDirectory(SymbolPath())

  def fromName(path: String)(implicit metaManager: MetaGraphManager): ProjectDirectory = {
    ProjectFrame.validateName(path, "Name", allowSlash = true, allowEmpty = true)
    val dir = new ProjectDirectory(SymbolPath.parse(path))
    assert(!dir.parents.exists(_.isProject), s"Cannot create directories inside projects: $path")
    dir
  }
}
