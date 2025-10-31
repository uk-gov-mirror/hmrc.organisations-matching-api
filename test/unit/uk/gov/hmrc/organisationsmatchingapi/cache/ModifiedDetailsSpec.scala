/*
 * Copyright 2025 HM Revenue & Customs
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

package unit.uk.gov.hmrc.organisationsmatchingapi.cache

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.*
import uk.gov.hmrc.organisationsmatchingapi.cache.ModifiedDetails

import java.time.{Instant, LocalDateTime, ZoneOffset}

class ModifiedDetailsSpec extends AnyWordSpec with Matchers {

  "ModifiedDetails JSON format" should {

    "serialize ModifiedDetails to JSON correctly" in {
      val createdAt = LocalDateTime.of(2023, 5, 10, 12, 30)
      val lastUpdated = LocalDateTime.of(2023, 6, 15, 14, 45)
      val details = ModifiedDetails(createdAt, lastUpdated)

      val json = Json.toJson(details)

      val expectedJson = Json.obj(
        "createdAt" -> Json.obj(
          "$date" -> Json.obj(
            "$numberLong" -> createdAt.toInstant(ZoneOffset.UTC).toEpochMilli.toString
          )
        ),
        "lastUpdated" -> Json.obj(
          "$date" -> Json.obj(
            "$numberLong" -> lastUpdated.toInstant(ZoneOffset.UTC).toEpochMilli.toString
          )
        )
      )

      json shouldBe expectedJson
    }

    "deserialize JSON to ModifiedDetails correctly" in {
      val createdAtMillis = Instant.parse("2023-05-10T12:30:00Z").toEpochMilli.toString
      val lastUpdatedMillis = Instant.parse("2023-06-15T14:45:00Z").toEpochMilli.toString

      val json = Json.obj(
        "createdAt" -> Json.obj(
          "$date" -> Json.obj(
            "$numberLong" -> createdAtMillis
          )
        ),
        "lastUpdated" -> Json.obj(
          "$date" -> Json.obj(
            "$numberLong" -> lastUpdatedMillis
          )
        )
      )

      val result = json.validate[ModifiedDetails]

      result.isSuccess shouldBe true
      val details = result.get
      details.createdAt shouldBe LocalDateTime.ofInstant(Instant.ofEpochMilli(createdAtMillis.toLong), ZoneOffset.UTC)
      details.lastUpdated shouldBe LocalDateTime.ofInstant(
        Instant.ofEpochMilli(lastUpdatedMillis.toLong),
        ZoneOffset.UTC
      )
    }

    "fail to deserialize invalid JSON" in {
      val invalidJson = Json.obj(
        "createdAt"   -> "not a date",
        "lastUpdated" -> "still not a date"
      )

      val result = invalidJson.validate[ModifiedDetails]
      result.isError shouldBe true
    }
  }
}
