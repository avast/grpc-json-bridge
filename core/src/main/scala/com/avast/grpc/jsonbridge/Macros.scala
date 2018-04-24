package com.avast.grpc.jsonbridge

import java.util.UUID

import com.google.protobuf.MessageLite
import io.grpc.BindableService
import io.grpc.stub.AbstractStub

import scala.reflect.ClassTag
import scala.reflect.macros.blackbox

class Macros(val c: blackbox.Context) {

  import c.universe._

  def generateGrpcJsonBridge[GrpcServiceStub <: BindableService, GrpcClientStub <: AbstractStub[GrpcClientStub]: WeakTypeTag]()(
      ec: c.Tree,
      ex: c.Tree,
      ct: c.Tree): c.Expr[GrpcJsonBridge[GrpcServiceStub]] = {

    val clientType = weakTypeOf[GrpcClientStub]
    val serviceTypeRaw = extractSymbolFromClassTag(ct)
    val serviceType = handleCactusType(serviceTypeRaw)

    val channelName = UUID.randomUUID().toString

    val stub = {
      q" ${clientType.typeSymbol.owner}.newFutureStub(clientsChannel) "
    }

    val methodCases = getMethodCases(serviceType)

    val t =
      q"""
      new com.avast.grpc.jsonbridge.GrpcJsonBridge[$serviceTypeRaw] with com.avast.grpc.jsonbridge.GrpcJsonBridgeBase[$clientType] {
        import com.avast.grpc.jsonbridge._

        private val serviceInstance: io.grpc.BindableService = { $getVariable }

        private val clientsChannel: io.grpc.ManagedChannel = ${createClientsChannel(channelName)}
        private val server: io.grpc.Server = ${startServer(channelName)}

        override protected def newFutureStub: $clientType = $stub

        override def invokeGrpcMethod(name: String, json: String, headers: scala.Seq[com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcHeader]): scala.Option[scala.concurrent.Future[String]] = {
          name match {
            case ..$methodCases
            // unsupported method
            case _ => scala.None
          }
        }

        override val serviceInfo: scala.Seq[String] = ${serviceInfo(serviceType)}

        override def close: Unit = {
          clientsChannel.shutdownNow()
          server.shutdownNow()
        }
      }
      """

    c.Expr[GrpcJsonBridge[GrpcServiceStub]](t)
  }

  private def getMethodCases(serviceType: c.Type): Iterable[c.Tree] = {
    serviceType.members
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

  private def startServer(channelName: String): c.Tree = {
    q"""
      io.grpc.inprocess.InProcessServerBuilder
        .forName($channelName)
        .executor(executor)
        .addService(serviceInstance)
        .build
        .start
     """
  }

  private def createClientsChannel(channelName: String): c.Tree = {
    q"""
      io.grpc.inprocess.InProcessChannelBuilder
        .forName($channelName)
        .executor(executor)
        .build()
     """
  }

  private def serviceInfo(serviceType: c.Type): c.Tree = {
    q"""{
          import scala.collection.JavaConverters._

          serviceInstance
            .bindService()
            .getMethods
            .asScala
            .map(_.getMethodDescriptor)
            .filter(_.getType == io.grpc.MethodDescriptor.MethodType.UNARY) // filter out all STREAMING methods
            .map(_.getFullMethodName)
            .toSeq
        }
    """
  }

  private def handleCactusType(t: c.Type): c.Type = {
    // needs to be matched by String, because the dependency on Cactus is missing (by purpose)
    if (t.baseClasses.exists(_.fullName == "com.avast.cactus.grpc.server.GrpcService")) {
      t.typeArgs.headOption.getOrElse(terminateWithInfo("Invalid com.avast.cactus.grpc.server.GrpcService on classpath"))
    } else t
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

  private def extractSymbolFromClassTag(ctTree: c.Tree): c.Type = {
    import c.universe._

    ctTree match {
      case q"ClassTag.apply[$cl](${_}): ${_}" => cl.tpe
      case q" $cl " if cl.tpe.dealias.typeConstructor == typeOf[ClassTag[_]].dealias.typeConstructor => cl.tpe.typeArgs.head
      case t => terminateWithInfo(s"Cannot process the conversion - variable type extraction from tree '$t' failed")
    }
  }

}
