package com.avast.grpc.jsonbridge.akkahttp

import akka.http.scaladsl.model.HttpHeader.ParsingResult.Ok
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.data.NonEmptyList
import cats.effect.IO
import com.avast.grpc.jsonbridge._
import io.grpc.ServerServiceDefinition
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.ExecutionContext
import scala.util.Random

class AkkaHttpTest extends AnyFunSuite with ScalatestRouteTest {

  val ec: ExecutionContext = implicitly[ExecutionContext]
  def bridge(ssd: ServerServiceDefinition): GrpcJsonBridge[IO] =
    ReflectionGrpcJsonBridge
      .createFromServices[IO](ec)(ssd)
      .allocated
      .unsafeRunSync()
      ._1

  test("basic") {
    val route = AkkaHttp[IO](Configuration.Default)(bridge(TestServiceImpl.bindService()))
    Post("/com.avast.grpc.jsonbridge.test.TestService/Add", """ { "a": 1, "b": 2} """)
      .withHeaders(AkkaHttp.JsonContentType) ~> route ~> check {
      assertResult(StatusCodes.OK)(status)
      assertResult("""{"sum":3}""")(responseAs[String])
      assertResult(Seq(`Content-Type`(ContentType.WithMissingCharset(MediaType.applicationWithOpenCharset("json")))))(headers)
    }
  }

  test("with path prefix") {
    val configuration = Configuration.Default.copy(pathPrefix = Some(NonEmptyList.of("abc", "def")))
    val route = AkkaHttp[IO](configuration)(bridge(TestServiceImpl.bindService()))
    Post("/abc/def/com.avast.grpc.jsonbridge.test.TestService/Add", """ { "a": 1, "b": 2} """)
      .withHeaders(AkkaHttp.JsonContentType) ~> route ~> check {
      assertResult(StatusCodes.OK)(status)
      assertResult("""{"sum":3}""")(responseAs[String])
    }
  }

  test("bad request after wrong request") {
    val route = AkkaHttp[IO](Configuration.Default)(bridge(TestServiceImpl.bindService()))
    // empty body
    Post("/com.avast.grpc.jsonbridge.test.TestService/Add", "")
      .withHeaders(AkkaHttp.JsonContentType) ~> route ~> check {
      assertResult(StatusCodes.BadRequest)(status)
    }
    // no Content-Type header
    Post("/com.avast.grpc.jsonbridge.test.TestService/Add", """ { "a": 1, "b": 2} """) ~> route ~> check {
      assertResult(StatusCodes.BadRequest)(status)
    }
  }

  test("propagates user-specified status") {
    val route = AkkaHttp(Configuration.Default)(bridge(PermissionDeniedTestServiceImpl.bindService()))
    Post(s"/com.avast.grpc.jsonbridge.test.TestService/Add", """ { "a": 1, "b": 2} """)
      .withHeaders(AkkaHttp.JsonContentType) ~> route ~> check {
      assertResult(status)(StatusCodes.Forbidden)
    }
  }

  test("provides service description") {
    val route = AkkaHttp[IO](Configuration.Default)(bridge(TestServiceImpl.bindService()))
    Get("/com.avast.grpc.jsonbridge.test.TestService") ~> route ~> check {
      assertResult(StatusCodes.OK)(status)
      assertResult("com.avast.grpc.jsonbridge.test.TestService/Add")(responseAs[String])
    }
  }

  test("provides services description") {
    val route = AkkaHttp[IO](Configuration.Default)(bridge(TestServiceImpl.bindService()))
    Get("/") ~> route ~> check {
      assertResult(StatusCodes.OK)(status)
      assertResult("com.avast.grpc.jsonbridge.test.TestService/Add")(responseAs[String])
    }
  }

  test("passes headers") {
    val headerValue = Random.alphanumeric.take(10).mkString("")
    val route = AkkaHttp[IO](Configuration.Default)(bridge(TestServiceImpl.withInterceptor))
    val Ok(customHeaderToBeSent, _) = HttpHeader.parse(TestServiceImpl.HeaderName, headerValue)
    Post("/com.avast.grpc.jsonbridge.test.TestService/Add", """ { "a": 1, "b": 2} """)
      .withHeaders(AkkaHttp.JsonContentType, customHeaderToBeSent) ~> route ~> check {
      assertResult(StatusCodes.OK)(status)
      assertResult("""{"sum":3}""")(responseAs[String])
      assertResult(Seq(`Content-Type`(ContentType.WithMissingCharset(MediaType.applicationWithOpenCharset("json")))))(headers)
    }
  }
}
