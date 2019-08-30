package com.avast.grpc.jsonbridge.scalapbtest

import com.avast.grpc.jsonbridge.scalapbtest.TestServices.{AddParams, AddResponse, TestServiceGrpc}

import scala.concurrent.Future

class TestServiceImpl extends TestServiceGrpc.TestService {
  def add(request: AddParams): Future[AddResponse] = Future.successful(AddResponse(request.a + request.b))
}
