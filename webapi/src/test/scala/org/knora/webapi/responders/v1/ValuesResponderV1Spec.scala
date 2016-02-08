/*
 * Copyright © 2015 Lukas Rosenthaler, Benjamin Geer, Ivan Subotic,
 * Tobias Schweizer, André Kilchenmann, and André Fatton.
 *
 * This file is part of Knora.
 *
 * Knora is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Knora is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with Knora.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.knora.webapi.responders.v1

import java.util.UUID

import akka.actor.Props
import akka.testkit.{ImplicitSender, TestActorRef}
import org.knora.webapi._
import org.knora.webapi.messages.v1respondermessages.resourcemessages.{ResourceFullGetRequestV1, ResourceFullResponseV1}
import org.knora.webapi.messages.v1respondermessages.triplestoremessages._
import org.knora.webapi.messages.v1respondermessages.usermessages.{UserDataV1, UserProfileV1}
import org.knora.webapi.messages.v1respondermessages.valuemessages._
import org.knora.webapi.responders._
import org.knora.webapi.store.{STORE_MANAGER_ACTOR_NAME, StoreManager}
import org.knora.webapi.util.DateUtilV1

import scala.concurrent.duration._

/**
  * Static data for testing [[ValuesResponderV1]].
  */
object ValuesResponderV1Spec {
    private val projectIri = "http://data.knora.org/projects/77275339"

    private val zeitglöckleinIri = "http://data.knora.org/c5058f3a"

    // A test UserDataV1.
    private val userData = UserDataV1(
        email = Some("test@test.ch"),
        lastname = Some("Test"),
        firstname = Some("User"),
        username = Some("testuser"),
        token = None,
        user_id = Some("http://data.knora.org/users/b83acc5f05"),
        lang = "de"
    )

    // A test UserProfileV1.
    private val userProfile = UserProfileV1(
        projects = Vector("http://data.knora.org/projects/77275339"),
        groups = Nil,
        userData = userData
    )

    private val versionHistoryWithHiddenVersion = ValueVersionHistoryGetResponseV1(
        userdata = userData,
        valueVersions = Vector(
            ValueVersionV1(
                previousValue = None, // The user doesn't have permission to see the previous value.
                valueCreationDate = Some("2016-01-22T11:31:24Z"),
                valueObjectIri = "http://data.knora.org/21abac2162/values/f76660458201"
            ),
            ValueVersionV1(
                previousValue = None,
                valueCreationDate = Some("2016-01-20T11:31:24Z"),
                valueObjectIri = "http://data.knora.org/21abac2162/values/11111111"
            )
        )
    )
}

/**
  * Tests [[ValuesResponderV1]].
  */
class ValuesResponderV1Spec extends CoreSpec() with ImplicitSender {
    private val actorUnderTest = TestActorRef[ValuesResponderV1]
    private val responderManager = system.actorOf(Props(new ResponderManagerV1 with LiveActorMaker), name = RESPONDER_MANAGER_ACTOR_NAME)
    private val storeManager = system.actorOf(Props(new StoreManager with LiveActorMaker), name = STORE_MANAGER_ACTOR_NAME)

    val rdfDataObjects = Vector(
        RdfDataObject(path = "../knora-ontologies/knora-base.ttl", name = "http://www.knora.org/ontology/knora-base"),
        RdfDataObject(path = "../knora-ontologies/knora-dc.ttl", name = "http://www.knora.org/ontology/dc"),
        RdfDataObject(path = "../knora-ontologies/salsah-gui.ttl", name = "http://www.knora.org/ontology/salsah-gui"),
        RdfDataObject(path = "_test_data/ontologies/incunabula-onto.ttl", name = "http://www.knora.org/ontology/incunabula"),
        RdfDataObject(path = "_test_data/responders.v1.ValuesResponderV1Spec/incunabula-data.ttl", name = "http://www.knora.org/data/incunabula")
    )

    // The default timeout for receiving reply messages from actors.
    private val timeout = 30.seconds

    private var commentIri = ""
    private var firstValueIriWithResourceRef = ""
    private var secondValueIriWithResourceRef = ""
    private var standoffLinkValueIri = ""
    private var currentSeqnumValue = ""
    private var currentPubdateValue = ""
    private var regionLinkValueIri = ""
    private var linkObjLinkValueIri = ""
    private var currentColorValue = ""
    private var currentGeomValue = ""

    private def checkComment1aResponse(response: CreateValueResponseV1, utf8str: String, textattr: Map[String, Seq[StandoffPositionV1]] = Map.empty[String, Seq[StandoffPositionV1]]): Unit = {
        assert(response.rights == 8, "rights was not 8")
        assert(response.value.asInstanceOf[TextValueV1].utf8str == utf8str, "comment value did not match")
        assert(response.value.asInstanceOf[TextValueV1].textattr == textattr, "textattr did not match")
        commentIri = response.id
    }

    private def checkValueGetResponse(response: ValueGetResponseV1): Unit = {
        assert(response.rights == 8, "rights was not 8")
        assert(response.value.asInstanceOf[TextValueV1].utf8str == "Comment 1a\r", "comment value did not match")
    }

    private def checkValueGetResponseWithStandoff(response: ValueGetResponseV1): Unit = {
        assert(response.rights == 6, "rights was not 6")
        assert(response.value.asInstanceOf[TextValueV1].utf8str == "Zusammengebunden mit zwei weiteren Drucken von Johann Amerbach\n", "comment utf8str value did not match")

        // expected Standoff information for <http://data.knora.org/e41ab5695c/values/d3398239089e04> in incunabula-data.ttl
        val textattr = Map(
            "bold" -> Vector(StandoffPositionV1(
                start = 21,
                end = 25
            ))
        )

        assert(response.value.asInstanceOf[TextValueV1].textattr == textattr, "textattr did not match")
    }

    private def checkComment1bResponse(response: ChangeValueResponseV1, utf8str: String, textattr: Map[String, Seq[StandoffPositionV1]] = Map.empty[String, Seq[StandoffPositionV1]]): Unit = {
        assert(response.rights == 8, "rights was not 8")
        assert(response.value.asInstanceOf[TextValueV1].utf8str == utf8str, "comment value did not match")
        assert(response.value.asInstanceOf[TextValueV1].textattr == textattr, "textattr did not match")
        commentIri = response.id
    }

    private def checkOrderInResource(response: ResourceFullResponseV1): Unit = {
        val comments = response.props.get.properties.filter(_.pid == "http://www.knora.org/ontology/incunabula#book_comment").head

        assert(comments.values == Vector(
            TextValueV1(utf8str = "Comment 1b"),
            TextValueV1("Comment 2")
        ), "Values of book_comment did not match")
    }

    private def checkDeletion(response: DeleteValueResponseV1): Unit = {
        commentIri = response.id
    }

    private def checkTextValue(expected: TextValueV1, received: TextValueV1): Unit = {
        def orderPositions(left: StandoffPositionV1, right: StandoffPositionV1): Boolean = {
            if (left.start != right.start) {
                left.start < right.start
            } else {
                left.end < right.end
            }
        }

        assert(expected.utf8str == received.utf8str)
        assert(expected.resource_reference == received.resource_reference)
        assert(received.textattr.keys == expected.textattr.keys)

        for (attribute <- expected.textattr.keys) {
            val expectedPositions = expected.textattr(attribute).sortWith(orderPositions)
            val receivedPositions = received.textattr(attribute).sortWith(orderPositions)

            assert(receivedPositions.length == expectedPositions.length)

            for ((expectedPosition, receivedPosition) <- expectedPositions.zip(receivedPositions)) {
                assert(expectedPosition.start == receivedPosition.start)
                assert(expectedPosition.end == receivedPosition.end)

                assert(expectedPosition.resid == receivedPosition.resid)

                if (expectedPosition.resid.isEmpty) {
                    assert(expectedPosition.href == receivedPosition.href)
                }
            }
        }
    }

    private def getLastModificationDate(resourceIri: IRI): Option[String] = {
        val lastModSparqlQuery = queries.sparql.v1.txt.getLastModificationDate(
            resourceIri = resourceIri
        ).toString()

        storeManager ! SparqlSelectRequest(lastModSparqlQuery)

        expectMsgPF(timeout) {
            case response: SparqlSelectResponse =>
                val rows = response.results.bindings
                (rows.size <= 1) should ===(true)

                if (rows.size == 1) {
                    Some(rows.head.rowMap("lastModificationDate"))
                } else {
                    None
                }
        }
    }

    // a sample set of text attributes
    private val sampleTextattr = Map(
        "bold" -> Vector(StandoffPositionV1(
            start = 0,
            end = 7
        )),
        "p" -> Vector(StandoffPositionV1(
            start = 0,
            end = 10
        ))
    )

    "Load test data" in {
        storeManager ! ResetTriplestoreContent(rdfDataObjects)
        expectMsg(300.seconds, ResetTriplestoreContentACK())
    }

    "The values responder" should {
        "add a new text value without Standoff" in {
            val lastModBeforeUpdate = getLastModificationDate(ValuesResponderV1Spec.zeitglöckleinIri)

            val utf8str = "Comment 1a\r"

            actorUnderTest ! CreateValueRequestV1(
                projectIri = ValuesResponderV1Spec.projectIri,
                resourceIri = ValuesResponderV1Spec.zeitglöckleinIri,
                propertyIri = "http://www.knora.org/ontology/incunabula#book_comment",
                value = TextValueV1(utf8str = utf8str),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: CreateValueResponseV1 => checkComment1aResponse(msg, utf8str)
            }

            // Check that the resource's last modification date got updated.
            val lastModAfterUpdate = getLastModificationDate(ValuesResponderV1Spec.zeitglöckleinIri)
            lastModBeforeUpdate != lastModAfterUpdate should ===(true)
        }

        "query a text value without Standoff" in {
            actorUnderTest ! ValueGetRequestV1(
                valueIri = commentIri,
                userProfile = ValuesResponderV1Spec.userProfile
            )

            expectMsgPF(timeout) {
                case msg: ValueGetResponseV1 => checkValueGetResponse(msg)
            }
        }

        "query a text value containing Standoff (disabled because of issue 17)" ignore {
            actorUnderTest ! ValueGetRequestV1(
                valueIri = "http://data.knora.org/e41ab5695c/values/d3398239089e04",
                userProfile = ValuesResponderV1Spec.userProfile
            )

            expectMsgPF(timeout) {
                case msg: ValueGetResponseV1 =>
                    checkValueGetResponseWithStandoff(msg)
            }
        }

        "query a LinkValue" in {
            actorUnderTest ! LinkValueGetRequestV1(
                subjectIri = "http://data.knora.org/8a0b1e75",
                predicateIri = "http://www.knora.org/ontology/incunabula#partOf",
                objectIri = ValuesResponderV1Spec.zeitglöckleinIri,
                userProfile = ValuesResponderV1Spec.userProfile
            )

            expectMsg(
                timeout,
                ValueGetResponseV1(
                    valuetype = OntologyConstants.KnoraBase.LinkValue,
                    value = LinkValueV1(
                        subjectIri = "http://data.knora.org/8a0b1e75",
                        predicateIri = "http://www.knora.org/ontology/incunabula#partOf",
                        objectIri = ValuesResponderV1Spec.zeitglöckleinIri,
                        referenceCount = 1
                    ),
                    rights = 2,
                    userdata = ValuesResponderV1Spec.userData
                )
            )
        }

        "add a new version of a text value without Standoff" in {
            val lastModBeforeUpdate = getLastModificationDate(ValuesResponderV1Spec.zeitglöckleinIri)

            val utf8str = "Comment 1b"

            actorUnderTest ! ChangeValueRequestV1(
                valueIri = commentIri,
                value = TextValueV1(utf8str = utf8str),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: ChangeValueResponseV1 => checkComment1bResponse(msg, utf8str)
            }

            // Check that the resource's last modification date got updated.
            val lastModAfterUpdate = getLastModificationDate(ValuesResponderV1Spec.zeitglöckleinIri)
            lastModBeforeUpdate != lastModAfterUpdate should ===(true)
        }

        "not add a new version of a value that's exactly the same as the current version" in {
            val utf8str = "Comment 1b"

            actorUnderTest ! ChangeValueRequestV1(
                valueIri = commentIri,
                value = TextValueV1(utf8str = utf8str),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[DuplicateValueException] should ===(true)
            }
        }

        "not create a new value that would duplicate an existing value" in {
            val utf8str = "Comment 1b"

            actorUnderTest ! CreateValueRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = ValuesResponderV1Spec.zeitglöckleinIri,
                propertyIri = "http://www.knora.org/ontology/incunabula#book_comment",
                value = TextValueV1(utf8str = utf8str),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[DuplicateValueException] should ===(true)
            }
        }

        "not add a new version of a value that would duplicate an existing value" in {
            val utf8str = "GW 4168"

            actorUnderTest ! ChangeValueRequestV1(
                valueIri = "http://data.knora.org/c5058f3a/values/184e99ca01",
                value = TextValueV1(utf8str = utf8str),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[DuplicateValueException] should ===(true)
            }
        }

        "insert valueHasOrder correctly for each value" in {
            actorUnderTest ! CreateValueRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = ValuesResponderV1Spec.zeitglöckleinIri,
                propertyIri = "http://www.knora.org/ontology/incunabula#book_comment",
                value = TextValueV1("Comment 2"),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: CreateValueResponseV1 => ()
            }

            responderManager ! ResourceFullGetRequestV1(
                iri = ValuesResponderV1Spec.zeitglöckleinIri,
                userProfile = ValuesResponderV1Spec.userProfile
            )

            expectMsgPF(timeout) {
                case msg: ResourceFullResponseV1 => checkOrderInResource(msg)
            }
        }

        "return the version history of a value" in {
            actorUnderTest ! ValueVersionHistoryGetRequestV1(
                resourceIri = ValuesResponderV1Spec.zeitglöckleinIri,
                propertyIri = "http://www.knora.org/ontology/incunabula#book_comment",
                currentValueIri = commentIri,
                userProfile = ValuesResponderV1Spec.userProfile
            )

            expectMsgPF(timeout) {
                case msg: ValueVersionHistoryGetResponseV1 => msg.valueVersions.length should ===(2)
            }
        }

        "mark a value as deleted" in {
            val lastModBeforeUpdate = getLastModificationDate(ValuesResponderV1Spec.zeitglöckleinIri)

            actorUnderTest ! DeleteValueRequestV1(
                valueIri = commentIri,
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: DeleteValueResponseV1 => checkDeletion(msg)
            }

            actorUnderTest ! ValueGetRequestV1(
                valueIri = commentIri,
                userProfile = ValuesResponderV1Spec.userProfile
            )

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[NotFoundException] should ===(true)
            }

            // Check that the resource's last modification date got updated.
            val lastModAfterUpdate = getLastModificationDate(ValuesResponderV1Spec.zeitglöckleinIri)
            lastModBeforeUpdate != lastModAfterUpdate should ===(true)
        }

        "not add a new value to a nonexistent resource" in {
            actorUnderTest ! CreateValueRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = "http://data.knora.org/nonexistent",
                propertyIri = "http://www.knora.org/ontology/incunabula#book_comment",
                value = TextValueV1("Comment 1"),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[NotFoundException] should ===(true)
            }
        }

        "not add a new value to a deleted resource" in {
            actorUnderTest ! CreateValueRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = "http://data.knora.org/9935159f67",
                propertyIri = "http://www.knora.org/ontology/incunabula#book_comment",
                value = TextValueV1("Comment 1"),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[NotFoundException] should ===(true)
            }
        }

        "not add a new version of a deleted value" in {
            actorUnderTest ! ChangeValueRequestV1(
                valueIri = commentIri,
                value = TextValueV1("Comment 1c"),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[NotFoundException] should ===(true)
            }
        }

        "not add a new value to a resource that the user doesn't have permission to modify" in {
            actorUnderTest ! CreateValueRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = "http://data.knora.org/e41ab5695c",
                propertyIri = "http://www.knora.org/ontology/incunabula#book_comment",
                value = TextValueV1("Comment 1"),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[ForbiddenException] should ===(true)
            }
        }

        "not add a new value of the wrong type" in {
            actorUnderTest ! CreateValueRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = "http://data.knora.org/21abac2162",
                propertyIri = "http://www.knora.org/ontology/incunabula#pubdate",
                value = TextValueV1("this is not a date"),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[OntologyConstraintException] should ===(true)
            }
        }

        "not add a new version to a value that the user doesn't have permission to modify" in {
            actorUnderTest ! ChangeValueRequestV1(
                valueIri = "http://data.knora.org/c5058f3a/values/c3295339",
                value = TextValueV1("Zeitglöcklein des Lebens und Leidens Christi modified"),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[ForbiddenException] should ===(true)
            }
        }

        "not add a new version of a value of the wrong type" in {
            actorUnderTest ! ChangeValueRequestV1(
                valueIri = "http://data.knora.org/c5058f3a/values/cfd09f1e01",
                value = TextValueV1("this is not a date"),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[OntologyConstraintException] should ===(true)
            }
        }

        "not add a new value that would violate a cardinality restriction" in {
            // The cardinality of incunabula:title is 1, and this book already has a title.
            actorUnderTest ! CreateValueRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = ValuesResponderV1Spec.zeitglöckleinIri,
                propertyIri = "http://www.knora.org/ontology/incunabula#title",
                value = TextValueV1("New title"),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[OntologyConstraintException] should ===(true)
            }

            // The cardinality of incunabula:publisher is 0-1, and this book already has a publisher.
            actorUnderTest ! CreateValueRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = ValuesResponderV1Spec.zeitglöckleinIri,
                propertyIri = "http://www.knora.org/ontology/incunabula#publisher",
                value = TextValueV1("New publisher"),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[OntologyConstraintException] should ===(true)
            }
        }

        "hide versions the user doesn't have permission to see" in {
            actorUnderTest ! ValueVersionHistoryGetRequestV1(
                resourceIri = "http://data.knora.org/21abac2162",
                propertyIri = "http://www.knora.org/ontology/incunabula#title",
                currentValueIri = "http://data.knora.org/21abac2162/values/f76660458201",
                userProfile = ValuesResponderV1Spec.userProfile
            )

            expectMsg(timeout, ValuesResponderV1Spec.versionHistoryWithHiddenVersion)
        }

        "add a new text value with Standoff" in {

            val utf8str = "Comment 1aa\r"

            actorUnderTest ! CreateValueRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = ValuesResponderV1Spec.zeitglöckleinIri,
                propertyIri = "http://www.knora.org/ontology/incunabula#book_comment",
                value = TextValueV1(utf8str = utf8str, textattr = sampleTextattr),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: CreateValueResponseV1 => checkComment1aResponse(msg, utf8str, sampleTextattr)
            }
        }

        "add a new version of a text value with Standoff" in {

            val utf8str = "Comment 1bb\r"

            actorUnderTest ! ChangeValueRequestV1(
                valueIri = commentIri,
                value = TextValueV1(utf8str = utf8str, textattr = sampleTextattr),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: ChangeValueResponseV1 => checkComment1bResponse(msg, utf8str, sampleTextattr)
            }
        }

        "add a new text value containing a Standoff resource reference, and create a hasStandoffLinkTo direct link and a corresponding LinkValue" in {
            val textValueWithResourceRef = TextValueV1(
                utf8str = "This comment refers to another resource",
                textattr = Map(
                    StandoffConstantsV1.LINK_ATTR -> Vector(StandoffPositionV1(
                        start = 31,
                        end = 39,
                        resid = Some(ValuesResponderV1Spec.zeitglöckleinIri)
                    ))
                ),
                resource_reference = Vector(ValuesResponderV1Spec.zeitglöckleinIri)
            )

            actorUnderTest ! CreateValueRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = "http://data.knora.org/21abac2162",
                propertyIri = "http://www.knora.org/ontology/incunabula#book_comment",
                value = textValueWithResourceRef,
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case CreateValueResponseV1(newValue: TextValueV1, _, newValueIri: IRI, _, ValuesResponderV1Spec.userData) =>
                    firstValueIriWithResourceRef = newValueIri
                    checkTextValue(textValueWithResourceRef, newValue)
            }

            actorUnderTest ! LinkValueGetRequestV1(
                subjectIri = "http://data.knora.org/21abac2162",
                predicateIri = OntologyConstants.KnoraBase.HasStandoffLinkTo,
                objectIri = ValuesResponderV1Spec.zeitglöckleinIri,
                userProfile = ValuesResponderV1Spec.userProfile
            )

            // Since this is the first Standoff resource reference between the source and target resources, we should
            // now have version 1 of a LinkValue, with a reference count of 1.

            expectMsg(
                timeout,
                ValueGetResponseV1(
                    valuetype = OntologyConstants.KnoraBase.LinkValue,
                    value = LinkValueV1(
                        subjectIri = "http://data.knora.org/21abac2162",
                        predicateIri = OntologyConstants.KnoraBase.HasStandoffLinkTo,
                        objectIri = ValuesResponderV1Spec.zeitglöckleinIri,
                        referenceCount = 1
                    ),
                    rights = 8,
                    userdata = ValuesResponderV1Spec.userData
                )
            )

            val sparqlQuery = queries.sparql.v1.txt.findLinkValueByObject(
                subjectIri = "http://data.knora.org/21abac2162",
                predicateIri = OntologyConstants.KnoraBase.HasStandoffLinkTo,
                objectIri = ValuesResponderV1Spec.zeitglöckleinIri
            ).toString()

            storeManager ! SparqlSelectRequest(sparqlQuery)

            // The new LinkValue should have no previous version, and there should be a direct link between the resources.

            expectMsgPF(timeout) {
                case response: SparqlSelectResponse =>
                    val rows = response.results.bindings
                    rows.groupBy(_.rowMap("linkValue")).size should ===(1)
                    rows.exists(_.rowMap("objPred") == OntologyConstants.KnoraBase.PreviousValue) should ===(false)
                    rows.head.rowMap.get("directLinkExists").exists(_.toBoolean) should ===(true)
            }

        }

        "add a new version of a text value containing a Standoff resource reference, without needlessly making a new version of the LinkValue" in {
            // The new version contains two references to the same resource.
            val textValueWithResourceRef = TextValueV1(
                utf8str = "This updated comment refers to another resource",
                textattr = Map(
                    StandoffConstantsV1.LINK_ATTR -> Vector(
                        StandoffPositionV1(
                            start = 39,
                            end = 47,
                            resid = Some(ValuesResponderV1Spec.zeitglöckleinIri)
                        ),
                        StandoffPositionV1(
                            start = 0,
                            end = 4,
                            resid = Some(ValuesResponderV1Spec.zeitglöckleinIri)
                        )
                    )
                ),
                resource_reference = Vector(ValuesResponderV1Spec.zeitglöckleinIri)
            )

            actorUnderTest ! ChangeValueRequestV1(
                valueIri = firstValueIriWithResourceRef,
                value = textValueWithResourceRef,
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case ChangeValueResponseV1(newValue: TextValueV1, _, newValueIri: IRI, _, ValuesResponderV1Spec.userData) =>
                    firstValueIriWithResourceRef = newValueIri
                    checkTextValue(textValueWithResourceRef, newValue)
            }

            actorUnderTest ! LinkValueGetRequestV1(
                subjectIri = "http://data.knora.org/21abac2162",
                predicateIri = OntologyConstants.KnoraBase.HasStandoffLinkTo,
                objectIri = ValuesResponderV1Spec.zeitglöckleinIri,
                userProfile = ValuesResponderV1Spec.userProfile
            )

            // Since the new version still refers to the same resource, the reference count of the LinkValue should not
            // change.

            expectMsg(
                timeout,
                ValueGetResponseV1(
                    valuetype = OntologyConstants.KnoraBase.LinkValue,
                    value = LinkValueV1(
                        subjectIri = "http://data.knora.org/21abac2162",
                        predicateIri = OntologyConstants.KnoraBase.HasStandoffLinkTo,
                        objectIri = ValuesResponderV1Spec.zeitglöckleinIri,
                        referenceCount = 1
                    ),
                    rights = 8,
                    userdata = ValuesResponderV1Spec.userData
                )
            )

            val sparqlQuery = queries.sparql.v1.txt.findLinkValueByObject(
                subjectIri = "http://data.knora.org/21abac2162",
                predicateIri = OntologyConstants.KnoraBase.HasStandoffLinkTo,
                objectIri = ValuesResponderV1Spec.zeitglöckleinIri
            ).toString()

            storeManager ! SparqlSelectRequest(sparqlQuery)

            // There should be no new version of the LinkValue, and the direct link should still be there.

            expectMsgPF(timeout) {
                case response: SparqlSelectResponse =>
                    val rows = response.results.bindings
                    rows.groupBy(_.rowMap("linkValue")).size should ===(1)
                    rows.exists(_.rowMap("objPred") == OntologyConstants.KnoraBase.PreviousValue) should ===(false)
                    rows.head.rowMap.get("directLinkExists").exists(_.toBoolean) should ===(true)
            }
        }

        "add another new text value containing a Standoff resource reference, and make a new version of the LinkValue" in {
            val textValueWithResourceRef = TextValueV1(
                utf8str = "This remark refers to another resource",
                textattr = Map(
                    StandoffConstantsV1.LINK_ATTR -> Vector(StandoffPositionV1(
                        start = 30,
                        end = 38,
                        resid = Some(ValuesResponderV1Spec.zeitglöckleinIri)
                    ))
                ),
                resource_reference = Vector(ValuesResponderV1Spec.zeitglöckleinIri)
            )

            actorUnderTest ! CreateValueRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = "http://data.knora.org/21abac2162",
                propertyIri = "http://www.knora.org/ontology/incunabula#book_comment",
                value = textValueWithResourceRef,
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case CreateValueResponseV1(newValue: TextValueV1, _, newValueIri: IRI, _, ValuesResponderV1Spec.userData) =>
                    secondValueIriWithResourceRef = newValueIri
                    checkTextValue(textValueWithResourceRef, newValue)
            }

            actorUnderTest ! LinkValueGetRequestV1(
                subjectIri = "http://data.knora.org/21abac2162",
                predicateIri = OntologyConstants.KnoraBase.HasStandoffLinkTo,
                objectIri = ValuesResponderV1Spec.zeitglöckleinIri,
                userProfile = ValuesResponderV1Spec.userProfile
            )

            // Now that we've added a different TextValue that refers to the same resource, we should have version 2
            // of the LinkValue, with a reference count of 2.

            expectMsg(
                timeout,
                ValueGetResponseV1(
                    valuetype = OntologyConstants.KnoraBase.LinkValue,
                    value = LinkValueV1(
                        subjectIri = "http://data.knora.org/21abac2162",
                        predicateIri = OntologyConstants.KnoraBase.HasStandoffLinkTo,
                        objectIri = ValuesResponderV1Spec.zeitglöckleinIri,
                        referenceCount = 2
                    ),
                    rights = 8,
                    userdata = ValuesResponderV1Spec.userData
                )
            )

            val sparqlQuery = queries.sparql.v1.txt.findLinkValueByObject(
                subjectIri = "http://data.knora.org/21abac2162",
                predicateIri = OntologyConstants.KnoraBase.HasStandoffLinkTo,
                objectIri = ValuesResponderV1Spec.zeitglöckleinIri
            ).toString()

            storeManager ! SparqlSelectRequest(sparqlQuery)

            // It should have a previousValue pointing to the previous version, and the direct link should
            // still be there.

            expectMsgPF(timeout) {
                case response: SparqlSelectResponse =>
                    val rows = response.results.bindings
                    rows.groupBy(_.rowMap("linkValue")).size should ===(1)
                    rows.exists(_.rowMap("objPred") == OntologyConstants.KnoraBase.PreviousValue) should ===(true)
                    rows.head.rowMap.get("directLinkExists").exists(_.toBoolean) should ===(true)
            }
        }

        "add a new version of a text value with the Standoff resource reference removed, and make a new version of the LinkValue" in {
            val textValue = TextValueV1(utf8str = "No resource reference here")

            actorUnderTest ! ChangeValueRequestV1(
                valueIri = firstValueIriWithResourceRef,
                value = textValue,
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case ChangeValueResponseV1(newValue: TextValueV1, _, newValueIri: IRI, _, ValuesResponderV1Spec.userData) =>
                    firstValueIriWithResourceRef = newValueIri
                    checkTextValue(textValue, newValue)
            }

            actorUnderTest ! LinkValueGetRequestV1(
                subjectIri = "http://data.knora.org/21abac2162",
                predicateIri = OntologyConstants.KnoraBase.HasStandoffLinkTo,
                objectIri = ValuesResponderV1Spec.zeitglöckleinIri,
                userProfile = ValuesResponderV1Spec.userProfile
            )

            // Version 3 of the LinkValue should have a reference count of 1.

            expectMsg(
                timeout,
                ValueGetResponseV1(
                    valuetype = OntologyConstants.KnoraBase.LinkValue,
                    value = LinkValueV1(
                        subjectIri = "http://data.knora.org/21abac2162",
                        predicateIri = OntologyConstants.KnoraBase.HasStandoffLinkTo,
                        objectIri = ValuesResponderV1Spec.zeitglöckleinIri,
                        referenceCount = 1
                    ),
                    rights = 8,
                    userdata = ValuesResponderV1Spec.userData
                )
            )

            val sparqlQuery = queries.sparql.v1.txt.findLinkValueByObject(
                subjectIri = "http://data.knora.org/21abac2162",
                predicateIri = OntologyConstants.KnoraBase.HasStandoffLinkTo,
                objectIri = ValuesResponderV1Spec.zeitglöckleinIri
            ).toString()

            storeManager ! SparqlSelectRequest(sparqlQuery)

            // The LinkValue should point to its previous version, and the direct link should still be there.

            expectMsgPF(timeout) {
                case response: SparqlSelectResponse =>
                    standoffLinkValueIri = response.results.bindings.head.rowMap("linkValue")
                    val rows = response.results.bindings
                    rows.groupBy(_.rowMap("linkValue")).size should ===(1)
                    rows.exists(_.rowMap("objPred") == OntologyConstants.KnoraBase.PreviousValue) should ===(true)
                    rows.head.rowMap.get("directLinkExists").exists(_.toBoolean) should ===(true)
            }

            // The LinkValue should have 3 versions in its version history.

            actorUnderTest ! ValueVersionHistoryGetRequestV1(
                resourceIri = "http://data.knora.org/21abac2162",
                propertyIri = OntologyConstants.KnoraBase.HasStandoffLinkToValue,
                currentValueIri = standoffLinkValueIri,
                userProfile = ValuesResponderV1Spec.userProfile
            )

            expectMsgPF(timeout) {
                case msg: ValueVersionHistoryGetResponseV1 => msg.valueVersions.length should ===(3)
            }
        }

        "delete a hasStandoffLinkTo direct link when the reference count of the corresponding LinkValue reaches 0" in {
            val textValue = TextValueV1(utf8str = "No resource reference here either")

            actorUnderTest ! ChangeValueRequestV1(
                valueIri = secondValueIriWithResourceRef,
                value = textValue,
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case ChangeValueResponseV1(newValue: TextValueV1, _, newValueIri: IRI, _, ValuesResponderV1Spec.userData) =>
                    secondValueIriWithResourceRef = newValueIri
                    checkTextValue(textValue, newValue)
            }

            // The new version of the LinkValue should be marked as deleted.

            actorUnderTest ! LinkValueGetRequestV1(
                subjectIri = "http://data.knora.org/21abac2162",
                predicateIri = OntologyConstants.KnoraBase.HasStandoffLinkTo,
                objectIri = ValuesResponderV1Spec.zeitglöckleinIri,
                userProfile = ValuesResponderV1Spec.userProfile
            )

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[NotFoundException] should ===(true)
            }

            val sparqlQuery = queries.sparql.v1.txt.findLinkValueByObject(
                subjectIri = "http://data.knora.org/21abac2162",
                predicateIri = OntologyConstants.KnoraBase.HasStandoffLinkTo,
                objectIri = ValuesResponderV1Spec.zeitglöckleinIri,
                includeDeleted = true
            ).toString()

            storeManager ! SparqlSelectRequest(sparqlQuery)

            // The LinkValue should point to its previous version. There should be no direct link.

            expectMsgPF(timeout) {
                case response: SparqlSelectResponse =>
                    standoffLinkValueIri = ""
                    val rows = response.results.bindings
                    rows.groupBy(_.rowMap("linkValue")).size should ===(1)
                    rows.exists(row => row.rowMap("objPred") == OntologyConstants.KnoraBase.IsDeleted && row.rowMap("objObj").toBoolean) should ===(true)
                    rows.exists(_.rowMap("objPred") == OntologyConstants.KnoraBase.PreviousValue) should ===(true)
                    rows.head.rowMap.get("directLinkExists").exists(_.toBoolean) should ===(false)
            }
        }

        "recreate the hasStandoffLinkTo direct link when the reference count of the corresponding LinkValue is incremented from 0 to 1" in {
            val textValueWithResourceRef = TextValueV1(
                utf8str = "This updated comment refers again to another resource",
                textattr = Map(
                    StandoffConstantsV1.LINK_ATTR -> Vector(
                        StandoffPositionV1(
                            start = 45,
                            end = 53,
                            resid = Some(ValuesResponderV1Spec.zeitglöckleinIri)
                        )
                    )
                ),
                resource_reference = Vector(ValuesResponderV1Spec.zeitglöckleinIri)
            )

            actorUnderTest ! ChangeValueRequestV1(
                valueIri = firstValueIriWithResourceRef,
                value = textValueWithResourceRef,
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case ChangeValueResponseV1(newValue: TextValueV1, _, newValueIri: IRI, _, ValuesResponderV1Spec.userData) =>
                    firstValueIriWithResourceRef = newValueIri
                    checkTextValue(textValueWithResourceRef, newValue)
            }

            actorUnderTest ! LinkValueGetRequestV1(
                subjectIri = "http://data.knora.org/21abac2162",
                predicateIri = OntologyConstants.KnoraBase.HasStandoffLinkTo,
                objectIri = ValuesResponderV1Spec.zeitglöckleinIri,
                userProfile = ValuesResponderV1Spec.userProfile
            )

            // There should now be a new LinkValue with no previous versions and a reference count of 1, and
            // there should once again be a direct link.

            expectMsg(
                timeout,
                ValueGetResponseV1(
                    valuetype = OntologyConstants.KnoraBase.LinkValue,
                    value = LinkValueV1(
                        subjectIri = "http://data.knora.org/21abac2162",
                        predicateIri = OntologyConstants.KnoraBase.HasStandoffLinkTo,
                        objectIri = ValuesResponderV1Spec.zeitglöckleinIri,
                        referenceCount = 1
                    ),
                    rights = 8,
                    userdata = ValuesResponderV1Spec.userData
                )
            )

            val sparqlQuery = queries.sparql.v1.txt.findLinkValueByObject(
                subjectIri = "http://data.knora.org/21abac2162",
                predicateIri = OntologyConstants.KnoraBase.HasStandoffLinkTo,
                objectIri = ValuesResponderV1Spec.zeitglöckleinIri
            ).toString()

            storeManager ! SparqlSelectRequest(sparqlQuery)

            expectMsgPF(timeout) {
                case response: SparqlSelectResponse =>
                    val rows = response.results.bindings
                    rows.groupBy(_.rowMap("linkValue")).size should ===(1)
                    rows.exists(_.rowMap("objPred") == OntologyConstants.KnoraBase.PreviousValue) should ===(false)
                    rows.head.rowMap.get("directLinkExists").exists(_.toBoolean) should ===(true)
            }
        }

        "add a new Integer value (seqnum of a page)" in {

            val seqnum = 4

            actorUnderTest ! CreateValueRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = "http://data.knora.org/8a0b1e75",
                propertyIri = "http://www.knora.org/ontology/incunabula#seqnum",
                value = IntegerValueV1(seqnum),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case CreateValueResponseV1(newValue: IntegerValueV1, _, newValueIri: IRI, _, ValuesResponderV1Spec.userData) =>
                    currentSeqnumValue = newValueIri
                    newValue should ===(IntegerValueV1(seqnum))
            }
        }

        "change an existing Integer value (seqnum of a page)" in {

            val seqnum = 8

            actorUnderTest ! ChangeValueRequestV1(
                value = IntegerValueV1(seqnum),
                userProfile = ValuesResponderV1Spec.userProfile,
                valueIri = currentSeqnumValue,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case ChangeValueResponseV1(newValue: IntegerValueV1, _, newValueIri: IRI, _, ValuesResponderV1Spec.userData) =>
                    newValue should ===(IntegerValueV1(seqnum))
            }
        }

        "add a new Date value (pubdate of a book)" in {

            // great resource to verify that expected conversion result from and to JDC is correct:
            // https://www.fourmilab.ch/documents/calendar/
            actorUnderTest ! CreateValueRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = "http://data.knora.org/21abac2162",
                propertyIri = "http://www.knora.org/ontology/incunabula#pubdate",
                value = JulianDayCountValueV1(
                    dateval1 = 2451545,
                    dateval2 = 2457044,
                    dateprecision1 = KnoraPrecisionV1.YEAR,
                    dateprecision2 = KnoraPrecisionV1.DAY,
                    calendar = KnoraCalendarV1.GREGORIAN
                ),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: CreateValueResponseV1 =>
                    currentPubdateValue = msg.id
                    msg.value should ===(DateValueV1("2000", "2015-01-21", KnoraCalendarV1.GREGORIAN))
            }
        }

        "change an existing date (pubdate of a book)" in {

            actorUnderTest ! ChangeValueRequestV1(
                value = JulianDayCountValueV1(
                    dateval1 = 2265854,
                    dateval2 = 2265854,
                    dateprecision1 = KnoraPrecisionV1.DAY,
                    dateprecision2 = KnoraPrecisionV1.DAY,
                    calendar = KnoraCalendarV1.JULIAN
                ),
                userProfile = ValuesResponderV1Spec.userProfile,
                valueIri = currentPubdateValue,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: ChangeValueResponseV1 =>
                    currentPubdateValue = msg.id
                    msg.value should ===(DateValueV1("1491-07-28", "1491-07-28", KnoraCalendarV1.JULIAN))
            }

        }

        "create a color value for a region" in {

            val color = "#000000"

            actorUnderTest ! CreateValueRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = "http://data.knora.org/5e51519c4407",
                propertyIri = "http://www.knora.org/ontology/knora-base#hasColor",
                value = ColorValueV1(color),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID)

            expectMsgPF(timeout) {
                case msg: CreateValueResponseV1 =>
                    currentColorValue = msg.id
                    msg.value should ===(ColorValueV1(color))
            }

        }

        "change an existing color of a region" in {

            val color = "#FFFFFF"

            actorUnderTest ! ChangeValueRequestV1(
                value = ColorValueV1(color),
                userProfile = ValuesResponderV1Spec.userProfile,
                valueIri = currentColorValue,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: ChangeValueResponseV1 =>
                    currentColorValue = msg.id
                    msg.value should ===(ColorValueV1(color))
            }

        }

        "create a geometry value for a region" in {

            val geom = "{\"status\":\"active\",\"lineColor\":\"#ff3333\",\"lineWidth\":2,\"points\":[{\"x\":0.5516074450084602,\"y\":0.4444444444444444},{\"x\":0.2791878172588832,\"y\":0.5}],\"type\":\"rectangle\",\"original_index\":0}"

            actorUnderTest ! CreateValueRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = "http://data.knora.org/5e51519c4407",
                propertyIri = "http://www.knora.org/ontology/knora-base#hasGeometry",
                value = GeomValueV1(geom),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID)

            expectMsgPF(timeout) {
                case msg: CreateValueResponseV1 =>
                    currentGeomValue = msg.id
                    msg.value should ===(GeomValueV1(geom))
            }

        }

        "change a geometry value for a region" in {

            val geom = "{\"status\":\"active\",\"lineColor\":\"#ff4433\",\"lineWidth\":1,\"points\":[{\"x\":0.5516074450084602,\"y\":0.4444444444444444},{\"x\":0.2791878172588832,\"y\":0.5}],\"type\":\"rectangle\",\"original_index\":0}"

            actorUnderTest ! ChangeValueRequestV1(
                value = GeomValueV1(geom),
                valueIri = currentGeomValue,
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: ChangeValueResponseV1 =>
                    currentGeomValue = msg.id
                    msg.value should ===(GeomValueV1(geom))
            }
        }

        "create a link between two resources" in {
            val linkSourceIri = "http://data.knora.org/5e51519c4407"
            val linkTargetIri = "http://data.knora.org/8a0b1e75"
            val lastModBeforeUpdate = getLastModificationDate(linkSourceIri)

            actorUnderTest ! CreateValueRequestV1(
                projectIri = ValuesResponderV1Spec.projectIri,
                resourceIri = linkSourceIri,
                propertyIri = OntologyConstants.KnoraBase.IsRegionOf,
                value = LinkUpdateV1(
                    targetResourceIri = linkTargetIri
                ),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case CreateValueResponseV1(regionLinkValue: LinkV1, _, newLinkValueIri: IRI, _, _) =>
                    regionLinkValueIri = newLinkValueIri
                    regionLinkValue.targetResourceIri should ===("http://data.knora.org/8a0b1e75")
                    regionLinkValue.valueResourceClass should ===(Some("http://www.knora.org/ontology/incunabula#page"))
            }

            val sparqlQuery = queries.sparql.v1.txt.findLinkValueByObject(
                subjectIri = "http://data.knora.org/5e51519c4407",
                predicateIri = OntologyConstants.KnoraBase.IsRegionOf,
                objectIri = "http://data.knora.org/8a0b1e75"
            ).toString()

            storeManager ! SparqlSelectRequest(sparqlQuery)

            // The new LinkValue should have no previous version, and there should be a direct link between the resources.

            expectMsgPF(timeout) {
                case response: SparqlSelectResponse =>
                    val rows = response.results.bindings
                    rows.groupBy(_.rowMap("linkValue")).size should ===(1)
                    rows.exists(_.rowMap("objPred") == OntologyConstants.KnoraBase.PreviousValue) should ===(false)
                    rows.head.rowMap.get("directLinkExists").exists(_.toBoolean) should ===(true)
            }

            // Check that the link source's last modification date got updated.
            val lastModAfterUpdate = getLastModificationDate(linkSourceIri)
            lastModBeforeUpdate != lastModAfterUpdate should ===(true)
        }

        "delete a link between two resources" in {
            val linkSourceIri = "http://data.knora.org/5e51519c4407"
            val linkTargetIri = "http://data.knora.org/8a0b1e75"
            val lastModBeforeUpdate = getLastModificationDate(linkSourceIri)

            val comment = "This link is no longer needed"

            actorUnderTest ! DeleteValueRequestV1(
                valueIri = regionLinkValueIri,
                comment = Some(comment),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: DeleteValueResponseV1 => () // If we got a DeleteValueResponseV1, the operation was successful.
            }

            val deletedLinkValueSparqlQuery = queries.sparql.v1.txt.findLinkValueByObject(
                subjectIri = linkSourceIri,
                predicateIri = OntologyConstants.KnoraBase.IsRegionOf,
                objectIri = linkTargetIri,
                includeDeleted = true
            ).toString()

            storeManager ! SparqlSelectRequest(deletedLinkValueSparqlQuery)

            expectMsgPF(timeout) {
                case response: SparqlSelectResponse =>
                    val rows = response.results.bindings
                    rows.groupBy(_.rowMap("linkValue")).size should ===(1)
                    rows.exists(row => row.rowMap("objPred") == OntologyConstants.KnoraBase.IsDeleted && row.rowMap("objObj").toBoolean) should ===(true)
                    rows.exists(_.rowMap("objPred") == OntologyConstants.KnoraBase.PreviousValue) should ===(true)
                    rows.head.rowMap.get("directLinkExists").exists(_.toBoolean) should ===(false)
                    rows.exists(row => row.rowMap("objPred") == OntologyConstants.KnoraBase.ValueHasComment && row.rowMap("objObj") == comment) should ===(true)
            }

            // Check that the link source's last modification date got updated.
            val lastModAfterUpdate = getLastModificationDate(linkSourceIri)
            lastModBeforeUpdate != lastModAfterUpdate should ===(true)
        }

        "not create a link that points to the wrong type of resource" in {
            actorUnderTest ! CreateValueRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = "http://data.knora.org/4f11adaf",
                propertyIri = "http://www.knora.org/ontology/incunabula#partOf", // can only point to an incunabula:book
                value = LinkUpdateV1(
                    targetResourceIri = "http://data.knora.org/5e51519c4407" // a knora-base:Region
                ),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[OntologyConstraintException] should ===(true)
            }
        }

        "not create a duplicate link" in {
            val createValueRequest = CreateValueRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = "http://data.knora.org/cb1a74e3e2f6",
                propertyIri = OntologyConstants.KnoraBase.HasLinkTo,
                value = LinkUpdateV1(
                    targetResourceIri = ValuesResponderV1Spec.zeitglöckleinIri
                ),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            actorUnderTest ! createValueRequest

            expectMsgPF(timeout) {
                case CreateValueResponseV1(regionLinkValue: LinkV1, _, newLinkValueIri: IRI, _, _) =>
                    linkObjLinkValueIri = newLinkValueIri
                    regionLinkValue.targetResourceIri should ===(ValuesResponderV1Spec.zeitglöckleinIri)
                    regionLinkValue.valueResourceClass should ===(Some("http://www.knora.org/ontology/incunabula#book"))
            }

            // The new LinkValue should have no previous version, and there should be a direct link between the resources.

            val sparqlQuery = queries.sparql.v1.txt.findLinkValueByObject(
                subjectIri = "http://data.knora.org/cb1a74e3e2f6",
                predicateIri = OntologyConstants.KnoraBase.HasLinkTo,
                objectIri = ValuesResponderV1Spec.zeitglöckleinIri
            ).toString()

            storeManager ! SparqlSelectRequest(sparqlQuery)

            expectMsgPF(timeout) {
                case response: SparqlSelectResponse =>
                    val rows = response.results.bindings
                    rows.groupBy(_.rowMap("linkValue")).size should ===(1)
                    rows.exists(_.rowMap("objPred") == OntologyConstants.KnoraBase.PreviousValue) should ===(false)
                    rows.head.rowMap.get("directLinkExists").exists(_.toBoolean) should ===(true)
            }

            actorUnderTest ! createValueRequest

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[DuplicateValueException] should ===(true)
            }
        }

        "change a link" in {
            val linkSourceIri = "http://data.knora.org/cb1a74e3e2f6"
            val lastModBeforeUpdate = getLastModificationDate(linkSourceIri)

            // Try to change the link that was created in the "not create a duplicate link" test above.
            val changeValueRequest = ChangeValueRequestV1(
                value = LinkUpdateV1(
                    targetResourceIri = "http://data.knora.org/21abac2162"
                ),
                userProfile = ValuesResponderV1Spec.userProfile,
                valueIri = linkObjLinkValueIri,
                apiRequestID = UUID.randomUUID
            )

            actorUnderTest ! changeValueRequest

            expectMsgPF(timeout) {
                case ChangeValueResponseV1(linkValue: LinkV1, _, newLinkValueIri: IRI, _, _) =>
                    linkObjLinkValueIri = newLinkValueIri
                    linkValue.targetResourceIri should ===("http://data.knora.org/21abac2162")
            }

            // The old LinkValue should be deleted now, and the old direct link should have been removed.

            val oldLinkValueSparqlQuery = queries.sparql.v1.txt.findLinkValueByObject(
                subjectIri = linkSourceIri,
                predicateIri = OntologyConstants.KnoraBase.HasLinkTo,
                objectIri = ValuesResponderV1Spec.zeitglöckleinIri,
                includeDeleted = true
            ).toString()

            storeManager ! SparqlSelectRequest(oldLinkValueSparqlQuery)

            expectMsgPF(timeout) {
                case response: SparqlSelectResponse =>
                    val rows = response.results.bindings
                    rows.groupBy(_.rowMap("linkValue")).size should ===(1)
                    rows.exists(row => row.rowMap("objPred") == OntologyConstants.KnoraBase.IsDeleted && row.rowMap("objObj").toBoolean) should ===(true)
                    rows.exists(_.rowMap("objPred") == OntologyConstants.KnoraBase.PreviousValue) should ===(true)
                    rows.head.rowMap.get("directLinkExists").exists(_.toBoolean) should ===(false)
            }

            // The new LinkValue should have no previous version, and there should be a direct link between the resources.

            val newLinkValueSparqlQuery = queries.sparql.v1.txt.findLinkValueByObject(
                subjectIri = linkSourceIri,
                predicateIri = OntologyConstants.KnoraBase.HasLinkTo,
                objectIri = "http://data.knora.org/21abac2162"
            ).toString()

            storeManager ! SparqlSelectRequest(newLinkValueSparqlQuery)

            expectMsgPF(timeout) {
                case response: SparqlSelectResponse =>
                    val rows = response.results.bindings
                    rows.groupBy(_.rowMap("linkValue")).size should ===(1)
                    rows.exists(_.rowMap("objPred") == OntologyConstants.KnoraBase.PreviousValue) should ===(false)
                    rows.head.rowMap.get("directLinkExists").exists(_.toBoolean) should ===(true)
            }

            // Check that the link source's last modification date got updated.
            val lastModAfterUpdate = getLastModificationDate(linkSourceIri)
            lastModBeforeUpdate != lastModAfterUpdate should ===(true)
        }

        "create multiple values in an empty resource" in {
            val title = Vector(
                TextValueV1(utf8str = "De generatione Christi")
            )

            val author = Vector(
                TextValueV1(utf8str = "Franciscus de Retza")
            )

            val publoc = Vector(
                TextValueV1(utf8str = "Basel")
            )

            val pubdate = Vector(
                DateValueV1(
                    dateval1 = "1487",
                    dateval2 = "1490",
                    calendar = KnoraCalendarV1.JULIAN
                )
            )

            val updateValues = Map(
                "http://www.knora.org/ontology/incunabula#title" -> title.map(v => CreateValueV1WithComment(v)),
                "http://www.knora.org/ontology/incunabula#hasAuthor" -> author.map(v => CreateValueV1WithComment(v)),
                "http://www.knora.org/ontology/incunabula#publoc" -> publoc.map(v => CreateValueV1WithComment(v)),
                "http://www.knora.org/ontology/incunabula#pubdate" -> pubdate.map(date => CreateValueV1WithComment(DateUtilV1.dateValueV1ToJulianDayCountValueV1(date), None))
            )

            val apiValues = Map(
                "http://www.knora.org/ontology/incunabula#title" -> title,
                "http://www.knora.org/ontology/incunabula#hasAuthor" -> author,
                "http://www.knora.org/ontology/incunabula#publoc" -> publoc,
                "http://www.knora.org/ontology/incunabula#pubdate" -> pubdate
            )

            val createMultipleValuesRequest = CreateMultipleValuesRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = "http://data.knora.org/c3f913666f",
                resourceClassIri = "http://www.knora.org/ontology/incunabula#book",
                values = updateValues,
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            actorUnderTest ! createMultipleValuesRequest

            expectMsgPF(timeout) {
                case response: CreateMultipleValuesResponseV1 =>
                    val justTheValues: Map[IRI, Seq[ApiValueV1]] = response.values.map {
                        case (propertyIri, createValueResponses) =>
                            propertyIri -> createValueResponses.map(_.value)
                    }

                    justTheValues should ===(apiValues)
            }
        }

        "add a new text value with a comment" in {
            val comment = "This is a comment"
            val metaComment = "This is a metacomment"

            actorUnderTest ! CreateValueRequestV1(
                projectIri = "http://data.knora.org/projects/77275339",
                resourceIri = ValuesResponderV1Spec.zeitglöckleinIri,
                propertyIri = "http://www.knora.org/ontology/incunabula#book_comment",
                value = TextValueV1(utf8str = comment),
                comment = Some(metaComment),
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: CreateValueResponseV1 =>
                    msg.value.toString should ===(comment)
                    msg.comment should ===(Some(metaComment))
            }
        }

        "add a comment to a value" in {
            val lastModBeforeUpdate = getLastModificationDate(ValuesResponderV1Spec.zeitglöckleinIri)

            val comment = "This is wrong. I am the author!"

            val changeCommentRequest = ChangeCommentRequestV1(
                valueIri = "http://data.knora.org/c5058f3a/values/8653a672",
                comment = comment,
                userProfile = ValuesResponderV1Spec.userProfile,
                apiRequestID = UUID.randomUUID
            )

            actorUnderTest ! changeCommentRequest

            expectMsgPF(timeout) {
                case msg: ChangeValueResponseV1 =>
                    msg.value should ===(TextValueV1(utf8str = "Berthold, der Bruder"))
                    msg.comment should ===(Some(comment))
            }

            // Check that the resource's last modification date got updated.
            val lastModAfterUpdate = getLastModificationDate(ValuesResponderV1Spec.zeitglöckleinIri)
            lastModBeforeUpdate != lastModAfterUpdate should ===(true)
        }
    }
}
