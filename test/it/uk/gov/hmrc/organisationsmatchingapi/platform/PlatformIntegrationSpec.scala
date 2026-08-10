/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.uk.gov.hmrc.organisationsmatchingapi.platform

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, post, stubFor, urlMatching}
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterEach, TestData}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.JsValue
import play.api.mvc.{AnyContentAsEmpty, Result}
import play.api.test.FakeRequest
import play.api.test.Helpers.{contentAsJson, contentAsString, defaultAwaitTimeout, status}
import play.api.{Application, Mode}
import uk.gov.hmrc.organisationsmatchingapi.controllers.documentation.DocumentationController

import scala.collection.Seq
import scala.concurrent.Future

class PlatformIntegrationSpec extends AnyWordSpec with Matchers with GuiceOneAppPerTest with MockitoSugar with ScalaFutures with BeforeAndAfterEach {

  val stubHost: String = "127.0.0.1"
  val stubPort: Int    = 11111
  val wireMockServer: WireMockServer = new WireMockServer(wireMockConfig().port(stubPort))

  override def newAppForTest(testData: TestData): Application =
    GuiceApplicationBuilder()
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

  override def beforeEach(): Unit = {
    wireMockServer.start()
    WireMock.configureFor(stubHost, stubPort)
    stubFor(post(urlMatching("/registration")).willReturn(aResponse().withStatus(204)))
  }

  trait Setup {
    implicit lazy val actorSystem: ActorSystem = app.actorSystem
    implicit lazy val materializer: Materializer = app.materializer

    val documentationController: DocumentationController = app.injector.instanceOf[DocumentationController]
    val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()
  }

  "microservice" should {
    "provide definition endpoint and documentation endpoint for each api" in new Setup {
      def verifyDocumentationPresent(version: String, endpointName: String): Unit = {
        withClue(s"Getting documentation version '$version' of endpoint '$endpointName'") {
          val documentationResult = documentationController.documentation(version, endpointName)(request)
          status(documentationResult) shouldBe 200
        }
      }

      val result: Future[Result] = documentationController.definition()(request)
      status(result) shouldBe 200

      val jsonResponse: JsValue = contentAsJson(result)

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

    "provide oas documentation" in new Setup {
      val result: Future[Result] = documentationController.specification("1.0", "application.yaml")(request)

      status(result) shouldBe 200
      contentAsString(result) should startWith("openapi: 3.0.3")
    }
  }

  override protected def afterEach(): Unit = {
    wireMockServer.stop()
    wireMockServer.resetMappings()
  }
}
