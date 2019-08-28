package com.avast.grpc

import io.grpc.MethodDescriptor.MethodType
import io.grpc.ServerMethodDefinition

package object jsonbridge {
  def isSupportedMethod(d: ServerMethodDefinition[_, _]): Boolean = d.getMethodDescriptor.getType == MethodType.UNARY
}
