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

package uk.gov.hmrc.organisationsmatchingapi.connectors

import play.api.Logging
import play.api.libs.json.{Format, Json}
import play.api.mvc.RequestHeader
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.*
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.organisationsmatchingapi.audit.AuditHelper
import uk.gov.hmrc.organisationsmatchingapi.domain.integrationframework.ct.IfCorpTaxCompanyDetails
import uk.gov.hmrc.organisationsmatchingapi.domain.integrationframework.sa.IfSaTaxpayerDetails
import uk.gov.hmrc.organisationsmatchingapi.domain.integrationframework.vat.IfVatCustomerInformation
import uk.gov.hmrc.organisationsmatchingapi.domain.models.MatchingException
import uk.gov.hmrc.organisationsmatchingapi.play.RequestHeaderUtils.validateCorrelationId
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.Try

class IfConnector @Inject()(servicesConfig: ServicesConfig, http: HttpClientV2, auditHelper: AuditHelper)(implicit ec: ExecutionContext) extends Logging {
  private val baseUrl = servicesConfig.baseUrl("integration-framework")

  private object BearerTokens {
    val sa: Option[String] = getAPIIFToken("1710").orElse(getIFToken("sa"))
    val ct: Option[String] = getAPIIFToken("1707").orElse(getIFToken("ct"))
    val vat: Option[String] = getOptionalToken(
      "integration-framework.authorization-token.vat"
    )

    private def getAPIIFToken(apiNumber: String): Option[String] =
      getOptionalToken(s"integration-framework.authorization-token.$apiNumber")

    private def getIFToken(name: String): Option[String] =
      getOptionalToken(s"integration-framework.authorization-token.$name")

    private def getOptionalToken(key: String): Option[String] =
      Try(servicesConfig.getConfString(key, "")).toOption.filter(_.nonEmpty)
  }

  private val integrationFrameworkEnvironment = servicesConfig.getString(
    "microservice.services.integration-framework.environment"
  )

  def fetchSelfAssessment(matchId: String, utr: String)(implicit
    hc: HeaderCarrier,
    request: RequestHeader
  ): Future[IfSaTaxpayerDetails] = {

    val SAUrl = s"$baseUrl/organisations/self-assessment/$utr/taxpayer/details"

    callIF[IfSaTaxpayerDetails](SAUrl, matchId, BearerTokens.sa)
  }

  def fetchCorporationTax(matchId: String, crn: String)(implicit
    hc: HeaderCarrier,
    request: RequestHeader
  ): Future[IfCorpTaxCompanyDetails] = {

    val CTUrl = s"$baseUrl/organisations/corporation-tax/$crn/company/details"

    callIF[IfCorpTaxCompanyDetails](CTUrl, matchId, BearerTokens.ct)
  }

  def fetchVat(matchId: String, vrn: String)(implicit
    hc: HeaderCarrier,
    request: RequestHeader
  ): Future[IfVatCustomerInformation] = {
    val vatUrl = s"$baseUrl/vat/customer/vrn/$vrn/information"

    callIF[IfVatCustomerInformation](vatUrl, matchId, BearerTokens.vat)
  }

  private def extractCorrelationId(requestHeader: RequestHeader): String = validateCorrelationId(requestHeader).toString

  private def setHeaders(
    requestHeader: RequestHeader,
    bearerToken: Option[String]
  ): Seq[(String, String)] =
    bearerToken.map(token => HeaderNames.authorisation -> s"Bearer $token").toSeq ++ Seq(
      "Environment"   -> integrationFrameworkEnvironment,
      "CorrelationId" -> extractCorrelationId(requestHeader)
    )

  private def callIF[R: Format: ClassTag](url: String, matchId: String, bearerToken: Option[String])(implicit
                                                                                             hc: HeaderCarrier,
                                                                                             request: RequestHeader
  ) =
    recover[R](
      http.get(url"$url").setHeader(setHeaders(request, bearerToken)*).execute[R].map(auditResponse(url, matchId)),
      extractCorrelationId(request),
      matchId,
      request,
      url
    )

  private def auditResponse[R: Format](url: String, matchId: String)(
    response: R
  )(implicit hc: HeaderCarrier, request: RequestHeader): R = {
    val responseStr = Json.toJson(response).toString
    val correlationId = extractCorrelationId(request)
    auditHelper.auditIfApiResponse(correlationId, matchId, request, url, responseStr)
    response
  }

  private def recover[T](
    x: Future[T],
    correlationId: String,
    matchId: String,
    request: RequestHeader,
    requestUrl: String
  )(implicit hc: HeaderCarrier): Future[T] = x.recoverWith {
    case validationError: JsValidationException =>
      logger.warn("Integration Framework JsValidationException encountered")
      auditHelper.auditIfApiFailure(
        correlationId,
        matchId,
        request,
        requestUrl,
        s"Error parsing IF response: ${validationError.errors}"
      )
      Future.failed(new InternalServerException("Something went wrong."))
    case UpstreamErrorResponse(msg, 404, _, _) =>
      auditHelper.auditIfApiFailure(correlationId, matchId, request, requestUrl, msg)
      logger.warn("Integration Framework NotFoundException encountered")
      Future.failed(new MatchingException)
    case UpstreamErrorResponse.Upstream5xxResponse(m) =>
      logger.warn(s"Integration Framework Upstream5xxResponse encountered: ${m.statusCode}")
      auditHelper.auditIfApiFailure(correlationId, matchId, request, requestUrl, s"Internal Server error: ${m.message}")
      Future.failed(new InternalServerException("Something went wrong."))
    case UpstreamErrorResponse(msg, 429, _, _) =>
      logger.warn(s"Integration Framework Rate limited: $msg")
      auditHelper.auditIfApiFailure(correlationId, matchId, request, requestUrl, s"IF Rate limited: $msg")
      Future.failed(new TooManyRequestException(msg))
    case UpstreamErrorResponse.Upstream4xxResponse(m) =>
      logger.warn(s"Integration Framework Upstream4xxResponse encountered: ${m.statusCode}")
      auditHelper.auditIfApiFailure(correlationId, matchId, request, requestUrl, m.message)
      Future.failed(new InternalServerException("Something went wrong."))
    case e: Exception =>
      logger.error(s"Integration Framework Exception encountered", e)
      auditHelper.auditIfApiFailure(correlationId, matchId, request, requestUrl, e.getMessage)
      Future.failed(new InternalServerException("Something went wrong."))
  }
}
