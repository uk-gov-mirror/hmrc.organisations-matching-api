/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.organisationsmatchingapi.repository

import java.util.UUID

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.json.Json
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.ReadPreference
import reactivemongo.api.commands.MultiBulkWriteResult
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.BSONDocument
import reactivemongo.play.json._
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.organisationsmatchingapi.models.{CrnMatch, JsonFormatters}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CrnMatchRepository @Inject()(mongo: ReactiveMongoComponent,
                                   config: Configuration)
  extends ReactiveRepository[CrnMatch, UUID] (
    "crn-match",
    mongo.mongoConnector.db,
    CrnMatch.formats,
    JsonFormatters.uuidJsonFormat) {

  private lazy val matchTtl: Int = config.get[Int]("mongodb.matchTtlInSeconds")

  override lazy val indexes = Seq(
    Index(Seq(("id", Ascending)), Some("idIndex"), background = true, unique = true),
    Index(
      Seq(("createdAt", Ascending)),
      Some("createdAtIndex"),
      options = BSONDocument("expireAfterSeconds" -> matchTtl),
      background = true)
  )

  def create(record: CrnMatch): Future[CrnMatch] = {
    insert(record) map { writeResult =>
      if (writeResult.n == 1) record
      else throw new RuntimeException(s"failed to persist crn match $record")
    }
  }

  def read(uuid: UUID): Future[Option[CrnMatch]] = findById(uuid)

  override def findById(id: UUID, readPreference: ReadPreference)(
    implicit ec: ExecutionContext): Future[Option[CrnMatch]] =
    collection.find(Json.obj("id" -> id.toString), Some(Json.obj())).one[CrnMatch]

  override def bulkInsert(entities: Seq[CrnMatch])(implicit ec: ExecutionContext): Future[MultiBulkWriteResult] =
    throw new UnsupportedOperationException
}
