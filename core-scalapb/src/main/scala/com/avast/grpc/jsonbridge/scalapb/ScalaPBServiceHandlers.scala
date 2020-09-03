package com.avast.grpc.jsonbridge.scalapb

import java.lang.reflect.{InvocationTargetException, Method}

import cats.effect.Async
import cats.syntax.all._
import com.avast.grpc.jsonbridge.GrpcJsonBridge.GrpcMethodName
import com.avast.grpc.jsonbridge.ReflectionGrpcJsonBridge.{HandlerFunc, ServiceHandlers}
import com.avast.grpc.jsonbridge.{BridgeError, JavaGenericHelper, ReflectionGrpcJsonBridge}
import com.fasterxml.jackson.core.JsonProcessingException
import com.typesafe.scalalogging.StrictLogging
import io.grpc._
import io.grpc.protobuf.ProtoFileDescriptorSupplier
import io.grpc.stub.AbstractStub
import org.json4s.ParserUtil.ParseException
import scalapb.json4s.JsonFormatException
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

private[jsonbridge] object ScalaPBServiceHandlers extends ServiceHandlers with StrictLogging {
  def createServiceHandlers[F[_]](
      ec: ExecutionContext
  )(inProcessChannel: ManagedChannel)(ssd: ServerServiceDefinition)(implicit F: Async[F]): Map[GrpcMethodName, HandlerFunc[F]] = {
    if (ssd.getServiceDescriptor.getName.startsWith("grpc.reflection.") || ssd.getServiceDescriptor.getName.startsWith("grpc.health.")) {
      logger.debug("Reflection and health endpoint service cannot be bridged because its implementation is not ScalaPB-based")
      Map.empty
    } else {
      val futureStubCtor = createFutureStubCtor(ssd.getServiceDescriptor, inProcessChannel)
      ssd.getMethods.asScala
        .filter(ReflectionGrpcJsonBridge.isSupportedMethod)
        .map(createHandler(ec)(futureStubCtor)(_))
        .toMap
    }
  }

  private val printer = new scalapb.json4s.Printer().includingDefaultValueFields

  private val parser = new scalapb.json4s.Parser()
  private val parserMethod = parser.getClass.getDeclaredMethods
    .find(_.getName == "fromJsonString")
    .getOrElse(sys.error(s"Method 'fromJsonString' not found on ${parser.getClass}"))
  private def parse(input: String, companion: GeneratedMessageCompanion[_]): Either[Throwable, GeneratedMessage] =
    try {
      Right(parserMethod.invoke(parser, input, companion).asInstanceOf[GeneratedMessage])
    } catch {
      case ie: InvocationTargetException => Left(ie.getCause)
      case NonFatal(e) => Left(e)
    }

  private def createFutureStubCtor(sd: ServiceDescriptor, inProcessChannel: Channel): () => AbstractStub[_] = {
    val serviceCompanionClassNames = getPossibleServiceCompanionClassNames(sd)
    val serviceCompanionClass = serviceCompanionClassNames
      .map(cn => {
        logger.debug(s"Obtaining class of $cn")
        try Some(Class.forName(cn))
        catch {
          case e: ClassNotFoundException =>
            logger.trace(s"Class $cn cannot be loaded", e)
            None
        }
      })
      .collectFirst {
        case Some(c) => c
      }
      .getOrElse(sys.error(s"Classes cannot be loaded: ${serviceCompanionClassNames.mkString(", ")}"))
    val serviceCompanion = serviceCompanionClass.getDeclaredField("MODULE$").get(null)
    val method = serviceCompanionClass.getDeclaredMethod("stub", classOf[Channel])
    () => method.invoke(serviceCompanion, inProcessChannel).asInstanceOf[AbstractStub[_]]
  }

  private def getPossibleServiceCompanionClassNames(sd: ServiceDescriptor): Seq[String] = {
    val servicePackage = sd.getName.substring(0, sd.getName.lastIndexOf('.'))
    val serviceName = sd.getName.substring(sd.getName.lastIndexOf('.') + 1)
    val fileNameWithoutExtension = sd.getSchemaDescriptor
      .asInstanceOf[ProtoFileDescriptorSupplier]
      .getFileDescriptor
      .getName
      .split('/')
      .last
      .stripSuffix(".proto")
    // we must handle when `flatPackage` is set to `true` - then the filename is included
    Seq(servicePackage + "." + fileNameWithoutExtension + "." + serviceName + "Grpc$", servicePackage + "." + serviceName + "Grpc$")
  }

  private def createHandler[F[_]](
      ec: ExecutionContext
  )(futureStubCtor: () => AbstractStub[_])(method: ServerMethodDefinition[_, _])(implicit F: Async[F]): (GrpcMethodName, HandlerFunc[F]) = {
    val requestCompanion = getRequestCompanion(method)
    val requestClass = Class.forName(requestCompanion.getClass.getName.stripSuffix("$"))
    val scalaMethod = futureStubCtor().getClass.getDeclaredMethod(getScalaMethodName(method), requestClass)
    val handler: HandlerFunc[F] = (input: String, headers: Map[String, String]) =>
      parse(input, requestCompanion) match {
        case Left(e: JsonProcessingException) => F.pure(Left(BridgeError.Json(e)))
        case Left(e: JsonFormatException) => F.pure(Left(BridgeError.Json(e)))
        case Left(e: ParseException) => F.pure(Left(BridgeError.Json(e)))
        case Left(e) => F.pure(Left(BridgeError.Unknown(e)))
        case Right(request) =>
          fromScalaFuture(ec) {
            F.delay {
              executeCore(request, headers, futureStubCtor, scalaMethod)(ec)
            }
          }
      }
    val grpcMethodName = GrpcMethodName(method.getMethodDescriptor.getFullMethodName)
    (grpcMethodName, handler)
  }

  private def executeCore(
      request: GeneratedMessage,
      headers: Map[String, String],
      futureStubCtor: () => AbstractStub[_],
      scalaMethod: Method
  )(implicit ec: ExecutionContext): Future[Either[BridgeError.Narrow, String]] = {
    val metadata = {
      val md = new Metadata()
      headers.foreach { case (k, v) => md.put(Metadata.Key.of(k, Metadata.ASCII_STRING_MARSHALLER), v) }
      md
    }
    val stubWithMetadata = JavaGenericHelper.attachHeaders(futureStubCtor(), metadata)
    scalaMethod
      .invoke(stubWithMetadata, request.asInstanceOf[Object])
      .asInstanceOf[scala.concurrent.Future[GeneratedMessage]]
      .map(gm => printer.print(gm))
      .map(Right(_): Either[BridgeError.Narrow, String])
      .recover {
        case e: StatusException =>
          Left(BridgeError.Grpc(e.getStatus))
        case e: StatusRuntimeException =>
          Left(BridgeError.Grpc(e.getStatus))
        case NonFatal(ex) =>
          Left(BridgeError.Unknown(ex))
      }
  }

  private def getScalaMethodName(method: ServerMethodDefinition[_, _]): String = {
    val Seq(_, methodName) = method.getMethodDescriptor.getFullMethodName.split('/').toSeq
    methodName.substring(0, 1).toLowerCase + methodName.substring(1)
  }

  private def getRequestCompanion(method: ServerMethodDefinition[_, _]): GeneratedMessageCompanion[_] = {
    val requestMarshaller = method.getMethodDescriptor.getRequestMarshaller match {
      case marshaller: scalapb.grpc.Marshaller[_] => marshaller
      case typeMappedMarshaller: scalapb.grpc.TypeMappedMarshaller[_, _] => typeMappedMarshaller
      case x =>
        logger.warn(
          "Marshaller of unexpected class '{}', we can't be sure it contains the field `companion: GeneratedMessageCompanion[_]`",
          x.getClass
        )
        x
    }
    val companionField = requestMarshaller.getClass.getDeclaredField("companion")
    companionField.setAccessible(true)
    companionField.get(requestMarshaller).asInstanceOf[GeneratedMessageCompanion[_]]
  }

  private def fromScalaFuture[F[_], A](ec: ExecutionContext)(fsf: F[Future[A]])(implicit F: Async[F]): F[A] =
    fsf.flatMap { sf =>
      F.async { cb =>
        sf.onComplete {
          case Success(r) => cb(Right(r))
          case Failure(e) => cb(Left(BridgeError.Unknown(e)))
        }(ec)
      }
    }
}
