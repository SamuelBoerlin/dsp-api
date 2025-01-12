/*
 * Copyright © 2021 Data and Service Center for the Humanities and/or DaSCH Service Platform contributors.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.knora.webapi.store.triplestore

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.LoggingReceive
import akka.routing.FromConfig
import org.knora.webapi.core.ActorMaker
import org.knora.webapi.exceptions.UnsupportedTriplestoreException
import org.knora.webapi.feature.FeatureFactoryConfig
import org.knora.webapi.messages.store.triplestoremessages.UpdateRepositoryRequest
import org.knora.webapi.messages.util.FakeTriplestore
import org.knora.webapi.settings._
import org.knora.webapi.store.triplestore.embedded.JenaTDBActor
import org.knora.webapi.store.triplestore.http.HttpTriplestoreConnector
import org.knora.webapi.store.triplestore.upgrade.RepositoryUpdater
import org.knora.webapi.util.ActorUtil._

import scala.concurrent.ExecutionContext

/**
 * This actor receives messages representing SPARQL requests, and forwards them to instances of one of the configured
 * triple stores.
 *
 * @param appActor                    a reference to the main application actor.
 * @param settings                    the application settings.
 * @param defaultFeatureFactoryConfig the application's default feature factory configuration.
 */
class TriplestoreManager(
  appActor: ActorRef,
  settings: KnoraSettingsImpl,
  defaultFeatureFactoryConfig: FeatureFactoryConfig
) extends Actor
    with ActorLogging {
  this: ActorMaker =>

  protected implicit val executionContext: ExecutionContext =
    context.system.dispatchers.lookup(KnoraDispatchers.KnoraActorDispatcher)

  private var storeActorRef: ActorRef = _

  // TODO: run the fake triple store as an actor (the fake triple store will not be needed anymore, once the embedded triple store is implemented)
  FakeTriplestore.init(settings.fakeTriplestoreDataDir)

  if (settings.useFakeTriplestore) {
    FakeTriplestore.load()
    log.info("Loaded fake triplestore")
  } else {
    log.debug(s"Using triplestore: ${settings.triplestoreType}")
  }

  if (settings.prepareFakeTriplestore) {
    FakeTriplestore.clear()
    log.info("About to prepare fake triplestore")
  }

  log.debug(settings.triplestoreType)

  // A RepositoryUpdater for processing requests to update the repository.
  private val repositoryUpdater: RepositoryUpdater = new RepositoryUpdater(
    system = context.system,
    appActor = appActor,
    settings = settings,
    featureFactoryConfig = defaultFeatureFactoryConfig
  )

  override def preStart(): Unit = {
    log.debug("TriplestoreManagerActor: start with preStart")

    storeActorRef = settings.triplestoreType match {
      case TriplestoreTypes.HttpGraphDBSE | TriplestoreTypes.HttpGraphDBFree | TriplestoreTypes.HttpFuseki =>
        makeActor(
          FromConfig.props(Props[HttpTriplestoreConnector]()).withDispatcher(KnoraDispatchers.KnoraActorDispatcher),
          name = HttpTriplestoreActorName
        )
      case TriplestoreTypes.EmbeddedJenaTdb => makeActor(Props[JenaTDBActor](), name = EmbeddedJenaActorName)
      case unknownType                      => throw UnsupportedTriplestoreException(s"Embedded triplestore type $unknownType not supported")
    }

    log.debug("TriplestoreManagerActor: finished with preStart")
  }

  def receive: Receive = LoggingReceive {
    case UpdateRepositoryRequest() => future2Message(sender(), repositoryUpdater.maybeUpdateRepository, log)
    case other                     => storeActorRef.forward(other)
  }
}
