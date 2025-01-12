/*
 * Copyright © 2021 Data and Service Center for the Humanities and/or DaSCH Service Platform contributors.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.knora.webapi
package responders

import exceptions.{BadRequestException, DuplicateValueException, UnexpectedMessageException}
import messages.store.triplestoremessages.SparqlSelectRequest
import messages.util.ResponderData
import messages.util.rdf.SparqlSelectResult
import messages.{SmartIri, StringFormatter}
import settings.{KnoraDispatchers, KnoraSettings, KnoraSettingsImpl}
import akka.actor.{ActorRef, ActorSystem}
import akka.event.LoggingAdapter
import akka.http.scaladsl.util.FastFuture
import akka.pattern._
import akka.util.Timeout
import com.typesafe.scalalogging.{LazyLogging, Logger}
import org.knora.webapi.store.cacheservice.settings.CacheServiceSettings

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

/**
 * Responder helper methods.
 */
object Responder {

  /**
   * An responder use this method to handle unexpected request messages in a consistent way.
   *
   * @param message the message that was received.
   * @param log     a [[Logger]].
   * @param who     the responder receiving the message.
   */
  def handleUnexpectedMessage(message: Any, log: Logger, who: String): Future[Nothing] = {
    val unexpectedMessageException = UnexpectedMessageException(
      s"$who received an unexpected message $message of type ${message.getClass.getCanonicalName}"
    )
    FastFuture.failed(unexpectedMessageException)
  }
}

/**
 * An abstract class providing values that are commonly used in Knora responders.
 */
abstract class Responder(responderData: ResponderData) extends LazyLogging {

  /**
   * The actor system.
   */
  protected implicit val system: ActorSystem = responderData.system

  /**
   * The execution context for futures created in Knora actors.
   */
  protected implicit val executionContext: ExecutionContext =
    system.dispatchers.lookup(KnoraDispatchers.KnoraActorDispatcher)

  /**
   * The application settings.
   */
  protected val settings: KnoraSettingsImpl = responderData.knoraSettings

  /**
   * The Cache Service settings.
   */
  protected val cacheServiceSettings: CacheServiceSettings = responderData.cacheServiceSettings

  /**
   * The main application actor.
   */
  protected val appActor: ActorRef = responderData.appActor

  /**
   * The main application actor forwards messages to the responder manager.
   */
  protected val responderManager: ActorRef = responderData.appActor

  /**
   * The main application actor forwards messages to the store manager.
   */
  protected val storeManager: ActorRef = responderData.appActor

  /**
   * A string formatter.
   */
  protected implicit val stringFormatter: StringFormatter = StringFormatter.getGeneralInstance

  /**
   * The application's default timeout for `ask` messages.
   */
  protected implicit val timeout: Timeout = settings.defaultTimeout

  /**
   * Provides logging
   */
  protected val log: Logger = logger
  protected val loggingAdapter: LoggingAdapter = akka.event.Logging(system, this.getClass)

  /**
   * Checks whether an entity is used in the triplestore.
   *
   * @param entityIri                 the IRI of the entity.
   * @param ignoreKnoraConstraints    if `true`, ignores the use of the entity in Knora subject or object constraints.
   * @param ignoreRdfSubjectAndObject if `true`, ignores the use of the entity in `rdf:subject` and `rdf:object`.
   *
   * @return `true` if the entity is used.
   */
  protected def isEntityUsed(
    entityIri: SmartIri,
    ignoreKnoraConstraints: Boolean = false,
    ignoreRdfSubjectAndObject: Boolean = false
  ): Future[Boolean] =
    for {
      isEntityUsedSparql <- Future(
        org.knora.webapi.messages.twirl.queries.sparql.v2.txt
          .isEntityUsed(
            triplestore = settings.triplestoreType,
            entityIri = entityIri,
            ignoreKnoraConstraints = ignoreKnoraConstraints,
            ignoreRdfSubjectAndObject = ignoreRdfSubjectAndObject
          )
          .toString()
      )

      isEntityUsedResponse: SparqlSelectResult <- (storeManager ? SparqlSelectRequest(isEntityUsedSparql))
        .mapTo[SparqlSelectResult]

    } yield isEntityUsedResponse.results.bindings.nonEmpty

  /**
   * Checks whether an instance of a class (or any ob its sub-classes) exists
   *
   * @param classIri  the IRI of the class.
   *
   * @return `true` if the class is used.
   */
  protected def isClassUsedInData(
    classIri: SmartIri
  ): Future[Boolean] =
    for {
      isClassUsedInDataSparql <- Future(
        org.knora.webapi.messages.twirl.queries.sparql.v2.txt
          .isClassUsedInData(
            triplestore = settings.triplestoreType,
            classIri = classIri
          )
          .toString()
      )

      isClassUsedInDataResponse: SparqlSelectResult <- (storeManager ? SparqlSelectRequest(isClassUsedInDataSparql))
        .mapTo[SparqlSelectResult]

    } yield isClassUsedInDataResponse.results.bindings.nonEmpty

  /**
   * Throws an exception if an entity is used in the triplestore.
   *
   * @param entityIri the IRI of the entity.
   * @param errorFun                  a function that throws an exception. It will be called if the entity is used.
   * @param ignoreKnoraConstraints    if `true`, ignores the use of the entity in Knora subject or object constraints.
   * @param ignoreRdfSubjectAndObject if `true`, ignores the use of the entity in `rdf:subject` and `rdf:object`.
   */
  protected def throwIfEntityIsUsed(
    entityIri: SmartIri,
    errorFun: => Nothing,
    ignoreKnoraConstraints: Boolean = false,
    ignoreRdfSubjectAndObject: Boolean = false
  ): Future[Unit] =
    for {
      entityIsUsed: Boolean <- isEntityUsed(entityIri, ignoreKnoraConstraints, ignoreRdfSubjectAndObject)

      _ = if (entityIsUsed) {
        errorFun
      }
    } yield ()

  /**
   * Throws an exception if a class is used in data.
   *
   * @param classIri  the IRI of the class.
   * @param errorFun  a function that throws an exception. It will be called if the class is used.
   */
  protected def throwIfClassIsUsedInData(
    classIri: SmartIri,
    errorFun: => Nothing
  ): Future[Unit] =
    for {
      classIsUsed: Boolean <- isClassUsedInData(classIri)

      _ = if (classIsUsed) {
        errorFun
      }
    } yield ()

  /**
   * Checks whether an entity with the provided custom IRI exists in the triplestore, if yes, throws an exception.
   * If no custom IRI was given, creates a random unused IRI.
   *
   * @param entityIri    the optional custom IRI of the entity.
   * @param iriFormatter the stringFormatter method that must be used to create a random Iri.
   * @return IRI of the entity.
   */
  protected def checkOrCreateEntityIri(entityIri: Option[SmartIri], iriFormatter: => IRI): Future[IRI] =
    entityIri match {
      case Some(customEntityIri: SmartIri) =>
        val entityIriAsString = customEntityIri.toString
        for {

          result <- stringFormatter.checkIriExists(entityIriAsString, storeManager)
          _ = if (result) {
            throw DuplicateValueException(s"IRI: '$entityIriAsString' already exists, try another one.")
          }
          // Check that given entityIRI ends with a UUID
          ending: String = entityIriAsString.split('/').last
          _ = stringFormatter.validateBase64EncodedUuid(
            ending,
            throw BadRequestException(s"IRI: '$entityIriAsString' must end with a valid base 64 UUID.")
          )

        } yield entityIriAsString

      case None => stringFormatter.makeUnusedIri(iriFormatter, storeManager, loggingAdapter)
    }
}
