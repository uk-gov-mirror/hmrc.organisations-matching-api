package uk.gov.hmrc.organisationsmatchingapi.platform

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterEach, TestData}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.{Application, Mode}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.JsValue
import play.api.mvc.{AnyContentAsEmpty, Result}
import play.api.test.FakeRequest
import uk.gov.hmrc.organisationsmatchingapi.helpers.UnitSpec
import uk.gov.hmrc.organisationsmatchingapi.platform.controllers.DocumentationController

import scala.concurrent.Future

class PlatformIntegrationSpec extends UnitSpec with GuiceOneAppPerTest with MockitoSugar with ScalaFutures with BeforeAndAfterEach {

  val stubHost: String = "localhost"
  val stubPort: Int    = 11111
  val wireMockServer: WireMockServer = new WireMockServer(wireMockConfig().port(stubPort))

  override def newAppForTest(testData: TestData): Application =
    GuiceApplicationBuilder()
    .disable[com.kenshoo.play.metrics.PlayModule]
    .configure("run.mode" -> "Stub")
    .configure(Map(
      "appName" -> "application-name",
      "appUrl"  -> "http://microservice-name.service",
      "metrics.enabled" -> false,
      "auditing.enabled" -> false,
      "api.access.whitelistedApplicationIds.0" -> "1234567890",
      "microservice.services.metrics.graphite.enabled" -> false
    ))
    .in(Mode.Test)
    .build()

  override def beforeEach() {
    wireMockServer.start()
    WireMock.configureFor(stubHost, stubPort)
    stubFor(post(urlMatching("/registration")).willReturn(aResponse().withStatus(204)))
  }

  trait Setup {
    implicit lazy val actorSystem: ActorSystem = app.actorSystem
    implicit lazy val materializer: Materializer = app.materializer

    val documentationController = app.injector.instanceOf[DocumentationController]
    val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()
  }

  "microservice" should {
    "provide definition endpoint and documentation endpoint for each api" in new Setup {
      def normalizeEndpointName(endpointName: String): String = endpointName.replaceAll(" ", "-")

      def verifyDocumentationPresent(version: String, endpointName: String) {
        withClue(s"Getting documentation version '$version' of endpoint '$endpointName'") {
          val documentationResult = documentationController.documentation(version, endpointName)(request)
          status(documentationResult) shouldBe 200
        }
      }

      val result: Future[Result] = documentationController.definition()(request)
      status(result) shouldBe 200

      val jsonResponse: JsValue = jsonBodyOf(result).futureValue

      val versions: Seq[String] = (jsonResponse \\ "version") map (_.as[String])
      val endpointNames: Seq[Seq[String]] =
        (jsonResponse \\ "endpoints").map(_ \\ "endpointName").map(_.map(_.as[String]))

      versions
        .zip(endpointNames)
        .flatMap {
          case (version, endpoint) =>
            endpoint.map(endpointName => (version, endpointName))
        }
        .foreach { case (version, endpointName) => verifyDocumentationPresent(version, endpointName) }
    }

    "provide definition including the whitelisted app ids" in new Setup {
      val result: Future[Result] = documentationController.definition()(request)
      status(result) shouldBe 200

      val jsonResponse: JsValue = jsonBodyOf(result).futureValue

      val whitelistedIds: Seq[String] =
        (jsonResponse \ "api" \ "versions" \ 0 \ "access" \ "whitelistedApplicationIds").as[Seq[String]]

      whitelistedIds should contain("1234567890")
    }

    "provide raml documentation" in new Setup {
      val result: Future[Result] = documentationController.raml("1.0", "application.raml")(request)

      status(result) shouldBe 200
      bodyOf(result).futureValue should startWith("#%RAML 1.0")
    }
  }

  override protected def afterEach(): Unit = {
    wireMockServer.stop()
    wireMockServer.resetMappings()
  }
}
