// BigGraphEnvironment creates and holds the MetaGraphManager and DataManager.
package com.lynxanalytics.biggraph

import java.io.File
import org.apache.spark

import com.lynxanalytics.biggraph.graph_util.HadoopFile
import com.lynxanalytics.biggraph.graph_util.PrefixRepository

trait SparkContextProvider {
  val sparkContext: spark.SparkContext

  def allowsClusterResize: Boolean = false
  def numInstances: Int = ???
  def setNumInstances(numInstances: Int): Unit = ???
}

class StaticSparkContextProvider() extends SparkContextProvider {
  val sparkContext = spark_util.BigGraphSparkContext("LynxKite")
  if (!sparkContext.isLocal) {
    bigGraphLogger.info("Wait 10 seconds for the workers to log in to the master...")
    Thread.sleep(10000)
  }
}

trait BigGraphEnvironment extends SparkContextProvider {
  val metaGraphManager: graph_api.MetaGraphManager
  val dataManager: graph_api.DataManager
}

trait StaticDirEnvironment extends BigGraphEnvironment {
  val repositoryDirs: RepositoryDirs

  override lazy val metaGraphManager = graph_api.MetaRepositoryManager(repositoryDirs.metaDir)
  override lazy val dataManager = new graph_api.DataManager(
    sparkContext, repositoryDirs.dataDir, repositoryDirs.ephemeralDataDir)
}

class RepositoryDirs(
    val metaDir: String,
    dataDirSymbolicName: String,
    dataDirResolvedName: String,
    ephemeralDirResolvedName: Option[String] = None) {

  lazy val dataDir: HadoopFile = {
    PrefixRepository.registerPrefix(dataDirSymbolicName, dataDirResolvedName)
    HadoopFile(dataDirSymbolicName)
  }

  lazy val ephemeralDataDir: Option[HadoopFile] = {
    ephemeralDirResolvedName.map {
      ephemeralDirResolvedName =>
        val ephemeralDirSymbolicName = "EPHEMERAL_" + dataDirSymbolicName
        PrefixRepository.registerPrefix(ephemeralDirSymbolicName, ephemeralDirResolvedName)
        HadoopFile(ephemeralDirSymbolicName)
    }
  }

  def forcePrefixRegistration(): Unit = {
    dataDir
    ephemeralDataDir
  }
}
