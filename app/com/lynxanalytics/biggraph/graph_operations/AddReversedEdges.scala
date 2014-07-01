package com.lynxanalytics.biggraph.graph_operations

import org.apache.spark.SparkContext.rddToPairRDDFunctions
import com.lynxanalytics.biggraph.spark_util.RDDUtils
import com.lynxanalytics.biggraph.graph_api._

case class AddReversedEdges() extends MetaGraphOperation {
  def signature = newSignature
    .inputGraph('vs, 'es)
    .outputEdgeBundle('esPlus, 'vs -> 'vs)

  def execute(inputs: DataSet, outputs: DataSetBuilder, rc: RuntimeContext): Unit = {
    val es = inputs.edgeBundles('es).rdd
    val esPlus = es.values.flatMap(e => Iterator(e, Edge(e.dst, e.src)))
    outputs.putEdgeBundle('esPlus, RDDUtils.fastNumbered(esPlus).partitionBy(es.partitioner.get))
  }
}
