package com.avast.grpc.jsonbridge.scalapb

import com.avast.grpc.jsonbridge.ReflectionGrpcJsonBridge

object ScalaPBReflectionGrpcJsonBridge extends ReflectionGrpcJsonBridge(ScalaPBServiceHandlers)
