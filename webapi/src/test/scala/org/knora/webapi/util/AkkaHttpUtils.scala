/*
 * Copyright © 2021 Data and Service Center for the Humanities and/or DaSCH Service Platform contributors.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.knora.webapi.util

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * Object containing methods for dealing with [[HttpResponse]]
 */
object AkkaHttpUtils extends LazyLogging {

  /**
   * Given an [[HttpResponse]] containing json, return the said json.
   *
   * @param response the [[HttpResponse]] containing json
   * @return an [[JsObject]]
   */
  def httpResponseToJson(response: HttpResponse)(implicit ec: ExecutionContext, system: ActorSystem): JsObject = {

    import DefaultJsonProtocol._
    import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

    implicit val materializer: Materializer = Materializer.matFromSystem(system)

    val jsonFuture: Future[JsObject] = response match {
      case HttpResponse(StatusCodes.OK, _, entity, _) =>
        Unmarshal(entity).to[JsObject]
      case other =>
        throw new Exception(other.toString())
    }

    //FIXME: There is probably a better non blocking way of doing it.
    Await.result(jsonFuture, Timeout(10.seconds).duration)
  }
}
