package com.lynxanalytics.biggraph.graph_operations

import org.apache.spark.SparkContext.rddToPairRDDFunctions
import scala.reflect.runtime.universe._

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.SortedRDD

object VertexSetIntersection extends OpFromJson {
  class Input(numVertexSets: Int) extends MagicInputSignature {
    val vss = Range(0, numVertexSets).map {
      i => vertexSet(Symbol("vs" + i))
    }.toList
  }
  class Output(
      implicit instance: MetaGraphOperationInstance,
      input: Input) extends MagicOutput(instance) {

    val intersection = vertexSet
    // An embedding of the intersection into the first vertex set.
    val firstEmbedding = edgeBundle(
      intersection, input.vss(0).entity, EdgeBundleProperties.embedding)
  }
  def fromJson(j: JsValue) = VertexSetIntersection((j \ "numVertexSets").as[Int])
}
case class VertexSetIntersection(numVertexSets: Int)
    extends TypedMetaGraphOp[VertexSetIntersection.Input, VertexSetIntersection.Output] {

  import VertexSetIntersection._
  assert(numVertexSets >= 1)
  @transient override lazy val inputs = new Input(numVertexSets)

  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs)
  override def toJson = Json.obj("numVertexSets" -> numVertexSets)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val intersection = inputs.vss.map(_.rdd)
      .reduce((rdd1, rdd2) => rdd1.sortedJoin(rdd2).mapValues(_ => ()))
    output(o.intersection, intersection)
    output(o.firstEmbedding, intersection.mapValuesWithKeys { case (id, _) => Edge(id, id) })
  }
}
