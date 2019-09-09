package com.avast.grpc.jsonbridge.scalapbtest

import com.avast.grpc.jsonbridge.scalapbtest.TestServices2.{AddParams2, AddResponse2, TestService2Grpc}

import scala.concurrent.Future

class TestServiceImpl2 extends TestService2Grpc.TestService2 {
  def add2(request: AddParams2): Future[AddResponse2] = Future.successful(AddResponse2(request.a + request.b))
}
