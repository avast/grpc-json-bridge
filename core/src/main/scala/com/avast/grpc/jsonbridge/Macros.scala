package com.avast.grpc.jsonbridge

import com.google.protobuf.MessageLite
import io.grpc.BindableService
import io.grpc.stub.AbstractStub

import scala.reflect.macros.blackbox

class Macros(val c: blackbox.Context) {

  import c.universe._

  def generateGrpcJsonBridge[GrpcServiceStub <: BindableService: WeakTypeTag,
                             GrpcClientStub <: AbstractStub[GrpcClientStub]: WeakTypeTag]()(
      ec: c.Tree,
      ex: c.Tree): c.Expr[GrpcJsonBridge[GrpcServiceStub]] = {
    val serviceType = weakTypeOf[GrpcServiceStub]
    val clientType = weakTypeOf[GrpcClientStub]

    val channelVar = getVariable

    val stub = {
      q" ${clientType.typeSymbol.owner}.newFutureStub(channel) "
    }

    val methodCases = getMethodCases(serviceType)

    val t =
      q"""
      new com.avast.grpc.jsonbridge.GrpcJsonBridge[$serviceType] with com.avast.grpc.jsonbridge.GrpcJsonBridgeBase[$clientType] {
        import com.avast.grpc.jsonbridge._

        protected val channel: io.grpc.ManagedChannel = $channelVar

        override protected def newFutureStub: $clientType = $stub

        override def invokeGrpcMethod(name: String, json: String, headers: scala.Seq[com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcHeader]): scala.Option[scala.concurrent.Future[String]] = {
          name match {
            case ..$methodCases
            // unsupported method
            case _ => scala.None
          }
        }

        override val serviceInfo: scala.Seq[String] = {
          import scala.collection.JavaConverters._

          new $serviceType() {}
            .bindService()
            .getMethods
            .asScala
            .map(_.getMethodDescriptor)
            .filter(_.getType == io.grpc.MethodDescriptor.MethodType.UNARY) // filter out all STREAMING methods
            .map(_.getFullMethodName)
            .toSeq
        }
      }
      """

    c.Expr[GrpcJsonBridge[GrpcServiceStub]](t)
  }

  // TODO cactus

  private def getMethodCases(serviceType: c.Type): Iterable[c.Tree] = {
    serviceType.decls
      .collect {
        case ApiMethod(m) => m
      }
      .map {
        case ApiMethod(name, request, _) =>
          cq"""
          ${firstUpper(name.toString)} =>
            val request: $request = fromJson(${request.companion}.getDefaultInstance, json)

            scala.Option {
              withNewFutureStub(headers) { _.$name(request).asScala.map(toJson) }
            }

        """
      }
  }

  private case class ApiMethod(name: TermName, request: Type, response: Type)

  private object ApiMethod {
    def unapply(s: Symbol): Option[ApiMethod] = {
      Option(s)
        .collect {
          case m
              if m.isMethod
                && m.name.toString != "bindService"
                && m.asMethod.paramLists.size == 1
                && m.asMethod.paramLists.head.size == 2
                && m.asMethod.returnType == typeOf[Unit] =>
            m.asMethod.name -> m.asMethod.paramLists.head.map(_.typeSignature.resultType)
        }
        .collect {
          case (name, List(req: Type, respObs: Type)) if isGpbClass(req) && respObs.typeArgs.forall(isGpbClass) =>
            ApiMethod(name, req, respObs.typeArgs.head)
        }
    }
  }

  private def getVariable: c.Tree = {
    import c.universe._

    val variable = c.prefix.tree match {
      case q"${_}[${_}]($n)" => n
      case q"${_}($n)" => n

      case t => terminateWithInfo(s"Cannot process the conversion - variable name extraction from tree '$t' failed")
    }

    q" $variable "
  }

  private def terminateWithInfo(msg: String = ""): Nothing = {
    if (msg != "") c.info(c.enclosingPosition, msg, force = false)
    c.abort(c.enclosingPosition, "Could not proceed")
  }

  private def firstUpper(s: String): String = {
    s.charAt(0).toUpper + s.substring(1)
  }

  private def isGpbClass(t: Type): Boolean = t.baseClasses.contains(typeOf[MessageLite].typeSymbol)

}
