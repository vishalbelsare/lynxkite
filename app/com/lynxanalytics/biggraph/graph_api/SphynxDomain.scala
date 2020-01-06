// The SphynxDomain can connect to a Sphynx server that runs single-node operations.

package com.lynxanalytics.biggraph.graph_api

import com.lynxanalytics.biggraph.graph_util
import play.api.libs.json.Json
import org.apache.hadoop.fs.FileSystem
import org.apache.spark.sql.types._
import org.apache.spark.sql.Row
import java.nio.file.{ Paths, Files }
import java.io.SequenceInputStream
import scala.collection.JavaConversions.asJavaEnumeration

abstract class SphynxDomain(host: String, port: Int, certDir: String) extends Domain {
  implicit val executionContext =
    ThreadUtil.limitedExecutionContext(
      "SphynxDomain",
      maxParallelism = graph_util.LoggedEnvironment.envOrElse("KITE_PARALLELISM", "5").toInt)
  val client = new SphynxClient(host, port, certDir)
}

class SphynxMemory(host: String, port: Int, certDir: String) extends SphynxDomain(host, port, certDir) {

  override def has(entity: MetaGraphEntity): Boolean = {
    client.hasInSphynxMemory(entity)
  }

  override def compute(instance: MetaGraphOperationInstance): SafeFuture[Unit] = {
    val jsonMeta = Json.stringify(MetaGraphManager.serializeOperation(instance))
    client.compute(jsonMeta).map(_ => ())
  }

  override def canCompute(instance: MetaGraphOperationInstance): Boolean = {
    val jsonMeta = Json.stringify(MetaGraphManager.serializeOperation(instance))
    client.canCompute(jsonMeta)
  }

  override def get[T](scalar: Scalar[T]): SafeFuture[T] = {
    client.getScalar(scalar)
  }

  override def cache(e: MetaGraphEntity): Unit = {
    ???
  }

  override def canRelocateFrom(source: Domain): Boolean = {
    source match {
      case _: OrderedSphynxDisk => true
      case _: UnorderedSphynxDisk => true
      case _ => false
    }
  }

  override def relocateFrom(e: MetaGraphEntity, source: Domain): SafeFuture[Unit] = {
    source match {
      case _: OrderedSphynxDisk => client.readFromOrderedSphynxDisk(e)
      case _ => ???
    }
  }

}

class OrderedSphynxDisk(host: String, port: Int, certDir: String) extends SphynxDomain(host, port, certDir) {

  override def has(entity: MetaGraphEntity): Boolean = {
    return client.hasOnOrderedSphynxDisk(entity)
  }

  override def compute(instance: MetaGraphOperationInstance): SafeFuture[Unit] = {
    ???
  }

  override def canCompute(instance: MetaGraphOperationInstance): Boolean = {
    false
  }

  override def get[T](scalar: Scalar[T]): SafeFuture[T] = {
    client.getScalar(scalar)
  }

  override def cache(e: MetaGraphEntity): Unit = {
    ???
  }

  override def canRelocateFrom(source: Domain): Boolean = {
    return false
  }

  override def relocateFrom(e: MetaGraphEntity, source: Domain): SafeFuture[Unit] = {
    ???
  }
}

class UnorderedSphynxDisk(host: String, port: Int, certDir: String, val dataDir: String)
  extends SphynxDomain(host, port, certDir) {

  override def has(entity: MetaGraphEntity): Boolean = {
    new java.io.File(s"${dataDir}/${entity.gUID.toString}").isFile
  }

  override def compute(instance: MetaGraphOperationInstance): SafeFuture[Unit] = {
    ???
  }

  override def canCompute(instance: MetaGraphOperationInstance): Boolean = {
    false
  }

  override def get[T](scalar: Scalar[T]): SafeFuture[T] = {
    throw new AssertionError("UnorderedSphynxDisk never contains scalars.")
  }

  override def cache(e: MetaGraphEntity): Unit = {
    ???
  }

  override def canRelocateFrom(source: Domain): Boolean = {
    source match {
      case _: SphynxMemory => true
      case _: SparkDomain => true
      case _ => false
    }
  }

  override def relocateFrom(e: MetaGraphEntity, source: Domain): SafeFuture[Unit] = {
    source match {
      case source: SphynxMemory => {
        e match {
          case v: VertexSet => client.writeToUnorderedDisk(v)
          case e: EdgeBundle => client.writeToUnorderedDisk(e)
          case a: Attribute[_] => client.writeToUnorderedDisk(a)
          case _ => throw new AssertionError(s"Cannot fetch $e from $source")
        }
      }
      case source: SparkDomain => {
        val middlePath = source.repositoryPath / "to_sphynx" / e.gUID.toString
        // TODO: check if middlePath contains something or not.
        val future = SafeFuture.async(source.getData(e) match {
          case v: VertexSetData => {
            val rdd = v.rdd.map {
              case (k, _) => Row(k)
            }
            val schema = StructType(Seq(StructField("Id", LongType, false)))
            val df = source.sparkSession.createDataFrame(rdd, schema)
            df.show()
            df.write.parquet(middlePath.resolvedName)
            val dstPath = Paths.get(s"${dataDir}/${e.gUID.toString}")
            val files = (middlePath / "part-*").list
            val stream = new SequenceInputStream(files.view.map(_.open).iterator)
            try {
              Files.copy(stream, dstPath)
            } catch {
              case err: Throwable => println("eeeeeeeeeeeeeeeee", err)
            } finally {
              stream.close()
            }
            val df3 = source.sparkSession.read.parquet(s"${dataDir}/${e.gUID.toString}")
            df3.show()
          }
          case _ => ???
        })
        future
      }
    }
  }
}
