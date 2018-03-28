package com.avast.grpc.jsonbridge

import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils

import scala.concurrent.Future

trait GrpcJsonBridgeBase[Stub <: io.grpc.stub.AbstractStub[Stub]] {

  protected def newFutureStub: Stub

  // https://groups.google.com/forum/#!topic/grpc-io/1-KMubq1tuc
  protected def withNewFutureStub[A](headers: Seq[GrpcHeader])(f: Stub => Future[A]): Future[A] = {
    val metadata = new Metadata()
    headers.foreach(h => metadata.put(Metadata.Key.of(h.name, Metadata.ASCII_STRING_MARSHALLER), h.value))

    val clientFutureStub = newFutureStub
      .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))

    f(clientFutureStub)

    // just abandon the stub...
  }

  protected def fromJson[Gpb <: Message](inst: Gpb, json: String): Gpb = {
    val builder = inst.newBuilderForType()
    JsonFormat.parser().merge(json, builder)
    builder.build().asInstanceOf[Gpb]
  }

  protected def toJson(resp: Message): String = {
    JsonFormat.printer().print(resp)
  }
}
