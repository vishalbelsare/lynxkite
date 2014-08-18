package com.lynxanalytics.biggraph.graph_operations

import org.apache.spark.SparkContext.rddToPairRDDFunctions
import scala.collection.SortedSet
import scala.collection.mutable

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._

object ClusteringCoefficient {
  class Output(implicit instance: MetaGraphOperationInstance, inputs: GraphInput) extends MagicOutput(instance) {
    val clustering = vertexAttribute[Double](inputs.vs.entity)
  }
}
import ClusteringCoefficient._
case class ClusteringCoefficient() extends TypedMetaGraphOp[GraphInput, Output] {
  override val isHeavy = true
  @transient override lazy val inputs = new GraphInput

  def outputMeta(instance: MetaGraphOperationInstance) =
    new Output()(instance, inputs)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val nonLoopEdges = inputs.es.rdd.filter { case (_, e) => e.src != e.dst }
    val vertices = inputs.vs.rdd
    val vertexPartitioner = vertices.partitioner.get

    val inNeighbors = nonLoopEdges
      .map { case (_, e) => e.dst -> e.src }
      .groupBySortedKey(vertexPartitioner)
      .mapValues(it => SortedSet(it.toSeq: _*).toArray)

    val outNeighbors = nonLoopEdges
      .map { case (_, e) => e.src -> e.dst }
      .groupBySortedKey(vertexPartitioner)
      .mapValues(it => SortedSet(it.toSeq: _*).toArray)

    val neighbors = vertices.sortedLeftOuterJoin(outNeighbors).sortedLeftOuterJoin(inNeighbors)
      .mapValues {
        case ((_, outs), ins) => sortedUnion(outs.getOrElse(Array()), ins.getOrElse(Array()))
      }

    val outNeighborsOfNeighbors = neighbors.sortedJoin(outNeighbors).flatMap {
      case (vid, (all, outs)) => all.map((_, outs))
    }.groupBySortedKey(vertexPartitioner)

    val clusteringCoeff = neighbors.sortedLeftOuterJoin(outNeighborsOfNeighbors).mapValues {
      case (mine, theirs) =>
        val numNeighbors = mine.size
        if (numNeighbors > 1) {
          theirs match {
            case Some(ns) =>
              val edgesInNeighborhood = ns.map(his => sortedIntersectionSize(his, mine)).sum
              edgesInNeighborhood * 1.0 / numNeighbors / (numNeighbors - 1)
            case None => 0.0
          }
        } else {
          1.0
        }
    }

    output(o.clustering, clusteringCoeff)
  }

  private def sortedUnion(a: Array[ID], b: Array[ID]): Array[ID] = {
    val builder = new mutable.ArrayBuilder.ofLong
    var ai = 0
    var bi = 0
    while (ai < a.size && bi < b.size) {
      if (a(ai) == b(bi)) {
        builder += a(ai)
        ai += 1
        bi += 1
      } else if (a(ai) > b(bi)) {
        builder += b(bi)
        bi += 1
      } else {
        builder += a(ai)
        ai += 1
      }
    }
    for (i <- ai until a.size) builder += a(i)
    for (i <- bi until b.size) builder += b(i)
    builder.result()
  }

  private def sortedIntersectionSize(a: Array[ID], b: Array[ID]): Int = {
    var ai = 0
    var bi = 0
    var result = 0
    while (ai < a.size && bi < b.size) {
      if (a(ai) == b(bi)) {
        result += 1
        ai += 1
        bi += 1
      } else if (a(ai) > b(bi)) {
        bi += 1
      } else {
        ai += 1
      }
    }
    result
  }
}
