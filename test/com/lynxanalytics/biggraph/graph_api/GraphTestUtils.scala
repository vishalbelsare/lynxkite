package com.lynxanalytics.biggraph.graph_api

import java.io.File

import org.apache.spark
import org.apache.spark.graphx.Edge

import com.lynxanalytics.biggraph.TestTempDir
import com.lynxanalytics.biggraph.TestSparkContext
import com.lynxanalytics.biggraph.BigGraphEnviroment

import attributes.AttributeSignature
import attributes.DenseAttributes

trait TestBigGraphManager extends TestTempDir {
  def cleanGraphManager(dirName: String): BigGraphManager = {
    val managerDir = tempDir("graphManager." + dirName)
    managerDir.mkdir
    BigGraphManager(managerDir.toString)
  }
}

trait TestGraphDataManager extends TestTempDir with TestSparkContext {
  def cleanDataManager(dirName: String): GraphDataManager = {
    val managerDir = tempDir("dataManager." + dirName)
    managerDir.mkdir
    GraphDataManager(sparkContext, managerDir.toString)
  }
}

class BigGraphTestEnviroment(dirName: String) extends BigGraphEnviroment with TestBigGraphManager with TestGraphDataManager {
  lazy val bigGraphManager = cleanGraphManager(dirName)
  lazy val graphDataManager = cleanDataManager(dirName)
}

class InstantiateSimpleGraph extends GraphOperation {
  @transient var executionCounter = 0

  def isSourceListValid(sources: Seq[BigGraph]) = (sources.size == 0)

  def execute(target: BigGraph, manager: GraphDataManager): GraphData = {
    val sc = manager.runtimeContext.sparkContext
    val vertexSig = vertexAttributes(target.sources)
    val edgeSig = edgeAttributes(target.sources)

    val vertexMaker = vertexSig.maker
    val nameIdx = vertexSig.writeIndex[String]("name")
    val vertices = Seq(
        (0l, vertexMaker.make.set(nameIdx, "Adam")),
        (1l, vertexMaker.make.set(nameIdx, "Eve")),
        (2l, vertexMaker.make.set(nameIdx, "Bob")))

    val edgeMaker = edgeSig.maker
    val commentIdx = edgeSig.writeIndex[String]("comment")
    val edges = Seq(
        new Edge(0l, 1l, edgeMaker.make.set(commentIdx, "Adam loves Eve")),
        new Edge(1l, 0l, edgeMaker.make.set(commentIdx, "Eve loves Adam")),
        new Edge(2l, 0l, edgeMaker.make.set(commentIdx, "Bob envies Adam")),
        new Edge(2l, 1l, edgeMaker.make.set(commentIdx, "Bob loves Eve")))

    executionCounter += 1

    return new SimpleGraphData(target, sc.parallelize(vertices), sc.parallelize(edges))
  }

  private lazy val internalVertexAttributes =
    AttributeSignature.empty.addAttribute[String]("name").signature
  def vertexAttributes(sources: Seq[BigGraph]): AttributeSignature = internalVertexAttributes

  private lazy val internalEdgeAttributes =
    AttributeSignature.empty.addAttribute[String]("comment").signature
  def edgeAttributes(sources: Seq[BigGraph]): AttributeSignature = internalEdgeAttributes
}
