package com.lynxanalytics.biggraph.controllers

import org.apache.spark
import com.lynxanalytics.biggraph.BigGraphEnvironment
import com.lynxanalytics.biggraph.serving

case class SparkStatusRequest(
  timestamp: Long) // Client requests to be notified only of events after this time.

case class SparkStatusResponse(
  activeStages: Seq[Int],
  timestamp: Long) // This is the status at the given time.

case class SparkClusterStatusResponse(
  master: String,
  workerInstances: Int)

case class SetClusterNumInstanceRequest(
  password: String,
  workerInstances: Int)

class SparkListener extends spark.scheduler.SparkListener {
  val activeStages = collection.mutable.Set[Int]()
  val promises = collection.mutable.Set[concurrent.Promise[SparkStatusResponse]]()
  var currentResp = SparkStatusResponse(Seq(), 0)

  override def onStageCompleted(stageCompleted: spark.scheduler.SparkListenerStageCompleted): Unit = {
    activeStages -= stageCompleted.stageInfo.stageId
    send()
  }

  override def onStageSubmitted(stageSubmitted: spark.scheduler.SparkListenerStageSubmitted): Unit = {
    activeStages += stageSubmitted.stageInfo.stageId
    send()
  }

  def send(): Unit = synchronized {
    val time = System.currentTimeMillis
    currentResp = SparkStatusResponse(activeStages.toSeq, time)
    for (p <- promises) {
      p.success(currentResp)
    }
    promises.clear()
  }

  def promise(timestamp: Long): concurrent.Promise[SparkStatusResponse] = synchronized {
    val p = concurrent.promise[SparkStatusResponse]
    if (timestamp < currentResp.timestamp) {
      p.success(currentResp) // We immediately have news for you.
    } else {
      promises += p // No news currently. You have successfully subscribed.
    }
    return p
  }
}

class SparkClusterController(environment: BigGraphEnvironment) {
  val sc = environment.sparkContext
  val listener = new SparkListener
  sc.addSparkListener(listener)

  def sparkStatus(req: SparkStatusRequest): concurrent.Future[SparkStatusResponse] = {
    listener.promise(req.timestamp).future
  }

  def sparkCancelJobs(req: serving.Empty): Unit = {
    sc.cancelAllJobs()
  }

  def getClusterStatus(request: serving.Empty): SparkClusterStatusResponse = {
    SparkClusterStatusResponse(environment.sparkContext.master, environment.numInstances)
  }

  def setClusterNumInstances(request: SetClusterNumInstanceRequest): SparkClusterStatusResponse = {
    if (request.password != "UCU8HB0d6fQJwyD8UAdDb")
      throw new IllegalArgumentException("Bad password!")
    environment.setNumInstances(request.workerInstances)
    return getClusterStatus(serving.Empty())
  }
}
