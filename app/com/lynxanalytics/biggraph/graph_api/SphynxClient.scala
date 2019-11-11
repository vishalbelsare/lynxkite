package com.lynxanalytics.biggraph.graph_api

import _root_.io.grpc.netty.NettyChannelBuilder
import _root_.io.grpc.netty.GrpcSslContexts
import _root_.io.grpc.StatusRuntimeException
import _root_.io.grpc.ManagedChannelBuilder
import _root_.io.grpc.stub.StreamObserver
import com.lynxanalytics.biggraph.graph_api.proto._
import com.lynxanalytics.biggraph.graph_util.LoggedEnvironment
import java.io.File
import scala.reflect.runtime.universe._
import scala.concurrent.{ Promise, Future }
import scala.util.{ Success, Failure }
import play.api.libs.json.Json
import scala.concurrent.ExecutionContext

class SingleResponseStreamObserver[T] extends StreamObserver[T] {
  private val promise = Promise[T]()
  val future = SafeFuture.wrap(promise.future)
  var responseArrived = false
  def onNext(r: T) {
    assert(!responseArrived, s"Two responses arrived, while we expected only one.")
    responseArrived = true
    promise.complete(Success(r))
  }
  def onError(t: Throwable) {
    promise.complete(Failure(t))
  }
  def onCompleted() {
    if (!responseArrived) {
      val e = new Exception("No response arrived.")
      promise.complete(Failure(e))
    }
  }
}

class SphynxClient(host: String, port: Int, certDir: String)(implicit ec: ExecutionContext) {
  // Exchanges messages with Sphynx.

  private val channel = NettyChannelBuilder.forAddress(host, port)
    .sslContext(GrpcSslContexts.forClient().trustManager(new File(s"$certDir/cert.pem")).build())
    .build();

  private val blockingStub = SphynxGrpc.newBlockingStub(channel)
  private val asyncStub = SphynxGrpc.newStub(channel)

  def canCompute(operationMetadataJSON: String): Boolean = {
    val request = SphynxOuterClass.CanComputeRequest.newBuilder().setOperation(operationMetadataJSON).build()
    val response = blockingStub.canCompute(request)
    return response.getCanCompute
  }

  def compute(operationMetadataJSON: String): SafeFuture[Unit] = {
    val request = SphynxOuterClass.ComputeRequest.newBuilder().setOperation(operationMetadataJSON).build()
    val singleResponseStreamObserver = new SingleResponseStreamObserver[SphynxOuterClass.ComputeReply]
    asyncStub.compute(request, singleResponseStreamObserver)
    singleResponseStreamObserver.future.map(_ => ())
  }

  def getScalar[T](scalar: Scalar[T]): SafeFuture[T] = {
    val gUIDString = scalar.gUID.toString()
    val request = SphynxOuterClass.GetScalarRequest.newBuilder().setGuid(gUIDString).build()
    val format = TypeTagToFormat.typeTagToFormat(scalar.typeTag)
    val singleResponseStreamObserver = new SingleResponseStreamObserver[SphynxOuterClass.GetScalarReply]
    asyncStub.getScalar(request, singleResponseStreamObserver)
    singleResponseStreamObserver.future.map(r => format.reads(Json.parse(r.getScalar)).get)
  }
}
