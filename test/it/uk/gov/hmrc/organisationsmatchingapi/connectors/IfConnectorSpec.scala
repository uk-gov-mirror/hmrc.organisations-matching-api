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

package it.uk.gov.hmrc.organisationsmatchingapi.connectors

import com.github.tomakehurst.wiremock.client.WireMock._
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{times, verify}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.{Application, Configuration}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.mvc.AnyContentAsEmpty
import play.api.test.FakeRequest
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.test.WireMockSupport
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames, InternalServerException}
import uk.gov.hmrc.organisationsmatchingapi.audit.AuditHelper
import uk.gov.hmrc.organisationsmatchingapi.connectors.IfConnector
import uk.gov.hmrc.organisationsmatchingapi.domain.integrationframework.common.IfAddress
import uk.gov.hmrc.organisationsmatchingapi.domain.integrationframework.ct.{IfCorpTaxCompanyDetails, IfNameAndAddressDetails, IfNameDetails}
import uk.gov.hmrc.organisationsmatchingapi.domain.integrationframework.sa.{IfSaTaxpayerDetails, IfSaTaxpayerNameAddress}
import uk.gov.hmrc.organisationsmatchingapi.domain.integrationframework.vat._
import uk.gov.hmrc.organisationsmatchingapi.domain.models.MatchingException
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global

class IfConnectorSpec
  extends AnyWordSpec
    with WireMockSupport
    with MockitoSugar
    with Matchers
    with GuiceOneAppPerSuite {
  val corporationTaxAuthorizationToken = "IF_TOKEN_1707"
  val selfAssessmentAuthorizationToken = "IF_TOKEN_1710"
  val vatAuthorizationToken = "IF_TOKEN_VAT"
  val existingCorporationTaxAuthorizationToken = "EXISTING_IF_TOKEN_CT"
  val existingSelfAssessmentAuthorizationToken = "EXISTING_IF_TOKEN_SA"
  val integrationFrameworkEnvironment = "IF_ENVIRONMENT"

  override def fakeApplication(): Application = new GuiceApplicationBuilder()
    .configure(
      "microservice.services.integration-framework.port" -> wireMockPort,
      "microservice.services.integration-framework.authorization-token.1707" -> corporationTaxAuthorizationToken,
      "microservice.services.integration-framework.authorization-token.1710" -> selfAssessmentAuthorizationToken,
      "microservice.services.integration-framework.authorization-token.ct" -> existingCorporationTaxAuthorizationToken,
      "microservice.services.integration-framework.authorization-token.sa" -> existingSelfAssessmentAuthorizationToken,
      "microservice.services.integration-framework.authorization-token.vat" -> vatAuthorizationToken,
      "microservice.services.integration-framework.environment" -> integrationFrameworkEnvironment
    )
    .build()

  trait Setup {
    val matchId = "80a6bb14-d888-436e-a541-4000674c60aa"
    val sampleCorrelationId = "188e9400-b636-4a3b-80ba-230a8c72b92a"
    val sampleCorrelationIdHeader: (String, String) = "CorrelationId" -> sampleCorrelationId

    implicit val hc: HeaderCarrier = HeaderCarrier()
    implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest().withHeaders(sampleCorrelationIdHeader)

    val config: ServicesConfig = app.injector.instanceOf[ServicesConfig]
    private val httpClient = app.injector.instanceOf[HttpClientV2]
    val auditHelper: AuditHelper = mock[AuditHelper]

    val underTest = new IfConnector(config, httpClient, auditHelper)

    def connectorWithExistingTokens(ctToken: String, saToken: String): IfConnector = {
      val fallbackConfig = new ServicesConfig(Configuration.from(Map(
        "microservice.services.integration-framework.protocol" -> "http",
        "microservice.services.integration-framework.host" -> "localhost",
        "microservice.services.integration-framework.port" -> wireMockPort,
        "microservice.services.integration-framework.environment" -> integrationFrameworkEnvironment,
        "microservice.services.integration-framework.authorization-token.ct" -> ctToken,
        "microservice.services.integration-framework.authorization-token.sa" -> saToken,
        "microservice.services.integration-framework.authorization-token.vat" -> vatAuthorizationToken
      )))

      new IfConnector(fallbackConfig, httpClient, auditHelper)
    }
  }

  "fetch Corporation Tax" should {
    val crn = "12345678"
    val utr = "1234567890"

    "return data on successful call" in new Setup {
      val registeredDetails: IfNameAndAddressDetails = IfNameAndAddressDetails(
        Some(IfNameDetails(
          Some("Waitrose"),
          Some("And Partners")
        )),
        Some(IfAddress(
          Some("Alfie House"),
          Some("Main Street"),
          Some("Manchester"),
          Some("Londonberry"),
          Some("LN1 1AG")
        ))
      )

      val communicationDetails: IfNameAndAddressDetails = IfNameAndAddressDetails(
        Some(IfNameDetails(
          Some("Waitrose"),
          Some("And Partners")
        )),
        Some(IfAddress(
          Some("Orange House"),
          Some("Corporation Street"),
          Some("London"),
          Some("Londonberry"),
          Some("LN1 1AG")
        ))
      )

      stubFor(
        get(urlPathMatching(s"/organisations/corporation-tax/$crn/company/details"))
          .withHeader(HeaderNames.authorisation, equalTo(s"Bearer $corporationTaxAuthorizationToken"))
          .withHeader("Environment", equalTo(integrationFrameworkEnvironment))
          .withHeader("CorrelationId", equalTo(sampleCorrelationId))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody(
                Json.toJson(
                  IfCorpTaxCompanyDetails(
                    Some(utr),
                    Some(crn),
                    Some(registeredDetails),
                    Some(communicationDetails)
                  )).toString()
              )
          )
      )

      val result: IfCorpTaxCompanyDetails = await(underTest.fetchCorporationTax(matchId, crn))

      verify(auditHelper, times(1))
        .auditIfApiResponse(any(), any(), any(), any(), any())(using any())

      result shouldBe IfCorpTaxCompanyDetails(
        Some(utr),
        Some(crn),
        Some(registeredDetails),
        Some(communicationDetails)
      )
    }

    "fall back to the existing CT authorization token when the 1707 token is absent" in new Setup {
      val connector = connectorWithExistingTokens(existingCorporationTaxAuthorizationToken, existingSelfAssessmentAuthorizationToken)
      val response = IfCorpTaxCompanyDetails(Some(utr), Some(crn), None, None)

      stubFor(
        get(urlPathMatching(s"/organisations/corporation-tax/$crn/company/details"))
          .withHeader(HeaderNames.authorisation, equalTo(s"Bearer $existingCorporationTaxAuthorizationToken"))
          .willReturn(okJson(Json.stringify(Json.toJson(response)))))

      await(connector.fetchCorporationTax(matchId, crn)) shouldBe response
    }

    "omit the authorization header when neither the 1707 nor existing CT token exists" in new Setup {
      val connector = connectorWithExistingTokens("", existingSelfAssessmentAuthorizationToken)
      val response = IfCorpTaxCompanyDetails(Some(utr), Some(crn), None, None)

      stubFor(
        get(urlPathMatching(s"/organisations/corporation-tax/$crn/company/details"))
          .withHeader(HeaderNames.authorisation, absent())
          .willReturn(okJson(Json.stringify(Json.toJson(response)))))

      await(connector.fetchCorporationTax(matchId, crn)) shouldBe response
    }

    "Fail when IF returns an error" in new Setup {
      stubFor(
        get(urlPathMatching(s"/organisations/corporation-tax/$crn/company/details"))
          .willReturn(aResponse().withStatus(500)))

      intercept[InternalServerException] {
        await(underTest.fetchCorporationTax(UUID.randomUUID().toString, "12345678"))
      }

      verify(auditHelper,
        times(1)).auditIfApiFailure(any(), any(), any(), any(), any())(using any())
    }

    "Fail when IF returns a bad request" in new Setup {
      stubFor(
        get(urlPathMatching(s"/organisations/corporation-tax/$crn/company/details"))
          .willReturn(aResponse().withStatus(400).withBody("BAD_REQUEST")))

      intercept[InternalServerException] {
        await(underTest.fetchCorporationTax(UUID.randomUUID().toString, "12345678"))
      }

      verify(auditHelper,
        times(1)).auditIfApiFailure(any(), any(), any(), any(), any())(using any())
    }

    "Fail when IF returns a NOT_FOUND" in new Setup {
      stubFor(
        get(urlPathMatching(s"/organisations/corporation-tax/$crn/company/details"))
          .willReturn(aResponse().withStatus(404).withBody("NOT_FOUND")))

      intercept[MatchingException] {
        await(underTest.fetchCorporationTax(UUID.randomUUID().toString, "12345678"))
      }
      verify(auditHelper, times(1))
        .auditIfApiFailure(any(), any(), any(), any(), any())(using any())
    }
  }

  "fetch Self Assessment" should {
    val utr = "1234567890"

    "return data on successful request" in new Setup {
      val taxpayerJohnNameAddress: IfSaTaxpayerNameAddress = IfSaTaxpayerNameAddress(
        Some("John Smith II"),
        Some("Base"),
        Some(IfAddress(
          Some("Alfie House"),
          Some("Main Street"),
          Some("Birmingham"),
          Some("West Midlands"),
          Some("B14 6JH"),
        ))
      )

      val taxpayerJoanneNameAddress: IfSaTaxpayerNameAddress = IfSaTaxpayerNameAddress(
        Some("Joanne Smith"),
        Some("Correspondence"),
        Some(IfAddress(
          Some("Alfie House"),
          Some("Main Street"),
          Some("Birmingham"),
          Some("West Midlands"),
          Some("MC1 4AA"),
        ))
      )

      stubFor(
        get(urlPathMatching(s"/organisations/self-assessment/$utr/taxpayer/details"))
          .withHeader(
            HeaderNames.authorisation,
            equalTo(s"Bearer $selfAssessmentAuthorizationToken"))
          .withHeader("Environment", equalTo(integrationFrameworkEnvironment))
          .withHeader("CorrelationId", equalTo(sampleCorrelationId))
          .willReturn(aResponse()
            .withStatus(200)
            .withBody(Json.toJson(IfSaTaxpayerDetails(
              Some(utr),
              Some("Individual"),
              Some(Seq(taxpayerJohnNameAddress, taxpayerJoanneNameAddress))
            )).toString())))

      val result: IfSaTaxpayerDetails = await(
        underTest.fetchSelfAssessment(UUID.randomUUID().toString, "1234567890")
      )

      verify(auditHelper, times(1))
        .auditIfApiResponse(any(), any(), any(), any(), any())(using any())

      result shouldBe IfSaTaxpayerDetails(
        Some(utr),
        Some("Individual"),
        Some(Seq(taxpayerJohnNameAddress, taxpayerJoanneNameAddress))
      )
    }

    "fall back to the existing SA authorization token when the 1710 token is absent" in new Setup {
      val connector = connectorWithExistingTokens(existingCorporationTaxAuthorizationToken, existingSelfAssessmentAuthorizationToken)
      val response = IfSaTaxpayerDetails(Some(utr), Some("Individual"), None)

      stubFor(
        get(urlPathMatching(s"/organisations/self-assessment/$utr/taxpayer/details"))
          .withHeader(HeaderNames.authorisation, equalTo(s"Bearer $existingSelfAssessmentAuthorizationToken"))
          .willReturn(okJson(Json.stringify(Json.toJson(response)))))

      await(connector.fetchSelfAssessment(matchId, utr)) shouldBe response
    }

    "Fail when IF returns an error" in new Setup {
      stubFor(
        get(urlPathMatching(s"/organisations/self-assessment/$utr/taxpayer/details"))
          .willReturn(aResponse().withStatus(500)))

      intercept[InternalServerException] {
        await(underTest.fetchSelfAssessment(UUID.randomUUID().toString, "1234567890"))
      }

      verify(auditHelper,
        times(1)).auditIfApiFailure(any(), any(), any(), any(), any())(using any())
    }

    "Fail when IF returns a bad request" in new Setup {
      stubFor(
        get(urlPathMatching(s"/organisations/self-assessment/$utr/taxpayer/details"))
          .willReturn(aResponse().withStatus(400).withBody("BAD_REQUEST")))

      intercept[InternalServerException] {
        await(underTest.fetchSelfAssessment(UUID.randomUUID().toString, "1234567890"))
      }

      verify(auditHelper, times(1))
        .auditIfApiFailure(any(), any(), any(), any(), any())(using any())
    }

    "Fail when IF returns a NOT_FOUND" in new Setup {
      stubFor(
        get(urlPathMatching(s"/organisations/self-assessment/$utr/taxpayer/details"))
          .willReturn(aResponse().withStatus(404).withBody("NOT_FOUND")))

      intercept[MatchingException] {
        await(underTest.fetchSelfAssessment(UUID.randomUUID().toString, "1234567890"))
      }
      verify(auditHelper,
        times(1)).auditIfApiFailure(any(), any(), any(), any(), any())(using any())
    }
  }

  "fetch VAT" should {
    val vrn = "123456789"
    val vatUrl = s"/vat/customer/vrn/$vrn/information"

    "return data on successful request" in new Setup {
      val ifResponse = IfVatCustomerInformation(
        IfVatApprovedInformation(
          IfVatCustomerDetails(Some("orgName")),
          IfPPOB(Some(IfVatCustomerAddress(Some("line1"), Some("postcode"))))
        )
      )

      stubFor(
        get(urlPathMatching(vatUrl))
          .withHeader(
            HeaderNames.authorisation,
            equalTo(s"Bearer $vatAuthorizationToken"))
          .withHeader("Environment", equalTo(integrationFrameworkEnvironment))
          .withHeader("CorrelationId", equalTo(sampleCorrelationId))
          .willReturn(aResponse()
            .withStatus(200)
            .withBody(Json.toJson(ifResponse).toString())
          )
      )

      val result: IfVatCustomerInformation = await(underTest.fetchVat(UUID.randomUUID().toString, vrn))

      verify(auditHelper, times(1))
        .auditIfApiResponse(any(), any(), any(), any(), any())(using any())

      result shouldBe ifResponse
    }

    "fail with InternalServerException when IF returns 500" in new Setup {
      stubFor(
        get(urlPathMatching(vatUrl))
          .willReturn(aResponse().withStatus(500)))

      intercept[InternalServerException] {
        await(underTest.fetchVat(UUID.randomUUID().toString, vrn))
      }

      verify(auditHelper, times(1))
        .auditIfApiFailure(any(), any(), any(), any(), any())(using any())
    }

    "fail with InternalServerException when IF returns a bad request" in new Setup {
      stubFor(
        get(urlPathMatching(vatUrl))
          .willReturn(aResponse().withStatus(400).withBody("BAD_REQUEST")))

      intercept[InternalServerException] {
        await(underTest.fetchVat(UUID.randomUUID().toString, vrn))
      }

      verify(auditHelper,
        times(1)).auditIfApiFailure(any(), any(), any(), any(), any())(using any())
    }

    "fail with MatchingException when IF returns a NOT_FOUND" in new Setup {
      stubFor(
        get(urlPathMatching(vatUrl))
          .willReturn(aResponse().withStatus(404).withBody("NOT_FOUND")))

      intercept[MatchingException] {
        await(underTest.fetchVat(UUID.randomUUID().toString, vrn))
      }
      verify(auditHelper,
        times(1)).auditIfApiFailure(any(), any(), any(), any(), any())(using any())
    }
  }
}
