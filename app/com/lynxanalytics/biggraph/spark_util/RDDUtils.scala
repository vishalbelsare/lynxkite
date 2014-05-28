package com.lynxanalytics.biggraph.spark_util

import org.apache.hadoop
import org.apache.spark
import org.apache.spark.graphx
import org.apache.spark.SparkContext.rddToPairRDDFunctions

object RDDUtils {
  // TODO(darabos): Remove this once https://github.com/apache/spark/pull/181 is in place.
  def objectFile[T: scala.reflect.ClassTag](
    sc: spark.SparkContext,
    path: String,
    minSplits: Int = -1): spark.rdd.RDD[T] = {
    val sf = sc.sequenceFile(
      path,
      classOf[hadoop.io.NullWritable],
      classOf[hadoop.io.BytesWritable],
      if (minSplits != -1) minSplits else sc.defaultMinSplits)
    sf.flatMap(x => deserialize[Array[T]](x._2.getBytes))
  }

  // TODO(darabos): Remove this once https://github.com/apache/spark/pull/181 is in place.
  def deserialize[T](bytes: Array[Byte]): T = {
    val bis = new java.io.ByteArrayInputStream(bytes)
    val ois = new java.io.ObjectInputStream(bis)
    ois.readObject.asInstanceOf[T]
  }

  def numbered[T](rdd: spark.rdd.RDD[T]): spark.rdd.RDD[(Long, T)] = {
    val localCounts = rdd.glom().map(_.size).collect().scan(0)(_ + _)
    val counts = rdd.sparkContext.broadcast(localCounts)
    rdd.mapPartitionsWithIndex((i, p) => {
      (counts.value(i) until counts.value(i + 1)).map(_.toLong).toIterator zip p
    })
  }

  def fastNumbered[T](rdd: spark.rdd.RDD[T]): spark.rdd.RDD[(Long, T)] = {
    rdd.mapPartitionsWithIndex {
      case (pid, it) => it.zipWithIndex.map {
        case (el, fID) => ((pid.toLong << 32) + fID, el)
      }
    }
  }
}
