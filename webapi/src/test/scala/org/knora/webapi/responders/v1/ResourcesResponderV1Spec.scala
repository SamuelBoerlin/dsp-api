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
import org.knora.webapi.messages.v1.responder.ontologymessages.{LoadOntologiesRequest, LoadOntologiesResponse}
import org.knora.webapi.messages.v1.responder.resourcemessages._
import org.knora.webapi.messages.v1.responder.sipimessages.SipiResponderConversionFileRequestV1
import org.knora.webapi.messages.v1.responder.usermessages.{UserDataV1, UserProfileV1}
import org.knora.webapi.messages.v1.responder.valuemessages._
import org.knora.webapi.messages.v1.store.triplestoremessages._
import org.knora.webapi.responders._
import org.knora.webapi.store._
import org.knora.webapi.util._

import scala.concurrent.duration._

/**
  * Static data for testing [[ResourcesResponderV1]].
  */
object ResourcesResponderV1Spec {

    private val incunabulaUser = UserProfileV1(
        projects = Vector("http://data.knora.org/projects/77275339"),
        groups = Nil,
        userData = UserDataV1(
            email = Some("test@test.ch"),
            lastname = Some("Test"),
            firstname = Some("User"),
            username = Some("testuser"),
            token = None,
            user_id = Some("http://data.knora.org/users/b83acc5f05"),
            lang = "de"
        )
    )

    private val anythingUser1 = UserProfileV1(
        projects = Vector("http://data.knora.org/projects/anything"),
        groups = Nil,
        userData = UserDataV1(
            user_id = Some("http://data.knora.org/users/9XBCrDV3SRa7kS1WwynB4Q"),
            lang = "de"
        )
    )

    private val anythingUser2 = UserProfileV1(
        projects = Vector("http://data.knora.org/projects/anything"),
        groups = Nil,
        userData = UserDataV1(
            user_id = Some("http://data.knora.org/users/BhkfBc3hTeS_IDo-JgXRbQ"),
            lang = "de"
        )
    )

    val ReiseInsHeiligelandThreeValues = ResourceSearchResponseV1(
        resources = Vector(ResourceSearchResultRowV1(
            id = "http://data.knora.org/2a6221216701",
            value = Vector("Reise ins Heilige Land", "Reysen und wanderschafften durch das Gelobte Land", "Itinerarius"),
            rights = Some(6)
        )),
        userdata = UserDataV1(
            password = None,
            email = Some("test@test.ch"),
            lastname = Some("Test"),
            firstname = Some("User"),
            username = Some("testuser"),
            token = None,
            user_id = Some("http://data.knora.org/users/b83acc5f05"),
            lang = "de"
        )
    )

    val ReiseInsHeiligelandOneValueRestrictedToBook = ResourceSearchResponseV1(
        resources = Vector(ResourceSearchResultRowV1(
            id = "http://data.knora.org/2a6221216701",
            value = Vector("Reise ins Heilige Land"),
            rights = Some(6)
        )),
        userdata = UserDataV1(
            password = None,
            email = Some("test@test.ch"),
            lastname = Some("Test"),
            firstname = Some("User"),
            username = Some("testuser"),
            token = None,
            user_id = Some("http://data.knora.org/users/b83acc5f05"),
            lang = "de"
        )
    )

    private val propertiesGetResponseV1Region = PropertiesGetResponseV1(
        PropsGetV1(
            Vector(
                PropertyGetV1(
                    "http://www.knora.org/ontology/knora-base#hasComment",
                    Some("Kommentar"),
                    Some("http://www.knora.org/ontology/knora-base#TextValue"),
                    Some("textval"),
                    Some("richtext"),
                    "",
                    "0",
                    Vector(
                        PropertyGetValueV1(
                            None,
                            "",
                            "Siehe Seite c5v",
                            TextValueV1("Siehe Seite c5v"),
                            "http://data.knora.org/021ec18f1735/values/8a96c303338201",
                            None,
                            None))),
                PropertyGetV1(
                    "http://www.knora.org/ontology/knora-base#hasColor",
                    Some("Farbe"),
                    Some(
                        "http://www.knora.org/ontology/knora-base#ColorValue"),
                    Some("textval"),
                    Some("colorpicker"),
                    "ncolors=8",
                    "0",
                    Vector(
                        PropertyGetValueV1(
                            None,
                            "",
                            "#ff3333",
                            ColorValueV1("#ff3333"),
                            "http://data.knora.org/021ec18f1735/values/10ea6976338201",
                            None,
                            None))),
                PropertyGetV1(
                    "http://www.knora.org/ontology/knora-base#hasGeometry",
                    Some("Geometrie"),
                    Some("http://www.knora.org/ontology/knora-base#GeomValue"),
                    Some("textval"),
                    Some("geometry"),
                    "width=95%;rows=4;wrap=soft",
                    "0",
                    Vector(
                        PropertyGetValueV1(
                            None,
                            "",
                            "{\"status\":\"active\",\"lineColor\":\"#ff3333\",\"lineWidth\":2,\"points\":[{\"x\":0.08098591549295775,\"y\":0.16741071428571427},{\"x\":0.7394366197183099,\"y\":0.7299107142857143}],\"type\":\"rectangle\",\"original_index\":0}",
                            GeomValueV1("{\"status\":\"active\",\"lineColor\":\"#ff3333\",\"lineWidth\":2,\"points\":[{\"x\":0.08098591549295775,\"y\":0.16741071428571427},{\"x\":0.7394366197183099,\"y\":0.7299107142857143}],\"type\":\"rectangle\",\"original_index\":0}"),
                            "http://data.knora.org/021ec18f1735/values/4dc0163d338201",
                            None,
                            None))),
                PropertyGetV1(
                    "http://www.knora.org/ontology/knora-base#isRegionOf",
                    Some("is Region von"),
                    Some("http://www.knora.org/ontology/knora-base#LinkValue"),
                    Some("textval"),
                    None,
                    "restypeid=http://www.knora.org/ontology/knora-base#Representation",
                    "0",
                    Vector(
                        PropertyGetValueV1(
                            None,
                            "",
                            "http://data.knora.org/9d626dc76c03",
                            LinkV1(
                                "http://data.knora.org/9d626dc76c03",
                                Some("u1r"),
                                Some(
                                    "http://www.knora.org/ontology/incunabula#page"),
                                None,
                                None),
                            "http://data.knora.org/021ec18f1735/values/fbcb88bf-cd16-4b7b-b843-51e17c0669d7",
                            None,
                            None))))), userdata = incunabulaUser.userData)

    private val hasOtherThingIncomingLink = IncomingV1(
        value = Some("A thing that only project members can see"),
        resinfo = ResourceInfoV1(
            regions = None,
            firstproperty = Some("A thing that only project members can see"),
            value_of = 0,
            lastmod = "0000-00-00 00:00:00",
            resclass_has_location = false,
            resclass_name = "object",
            locdata = None,
            locations = None,
            preview = None,
            restype_iconsrc = Some("http://localhost:3335/project-icons/anything/thing.png"),
            restype_description = Some("Diese Resource-Klasse beschreibt ein Ding"),
            restype_label = Some("Ding"),
            restype_name = Some("http://www.knora.org/ontology/anything#Thing"),
            restype_id = "http://www.knora.org/ontology/anything#Thing",
            person_id = "http://data.knora.org/users/9XBCrDV3SRa7kS1WwynB4Q",
            project_id = "http://data.knora.org/projects/anything"
        ),
        ext_res_id = ExternalResourceIDV1(
            pid = "http://www.knora.org/ontology/anything#hasOtherThing",
            id = "http://data.knora.org/project-thing-1"
        )
    )

    private val hasStandoffLinkToIncomingLink = IncomingV1(
        value = Some("A thing that only project members can see"),
        resinfo = ResourceInfoV1(
            regions = None,
            firstproperty = Some("A thing that only project members can see"),
            value_of = 0,
            lastmod = "0000-00-00 00:00:00",
            resclass_has_location = false,
            resclass_name = "object",
            locdata = None,
            locations = None,
            preview = None,
            restype_iconsrc = Some("http://localhost:3335/project-icons/anything/thing.png"),
            restype_description = Some("Diese Resource-Klasse beschreibt ein Ding"),
            restype_label = Some("Ding"),
            restype_name = Some("http://www.knora.org/ontology/anything#Thing"),
            restype_id = "http://www.knora.org/ontology/anything#Thing",
            person_id = "http://data.knora.org/users/9XBCrDV3SRa7kS1WwynB4Q",
            project_id = "http://data.knora.org/projects/anything"
        ),
        ext_res_id = ExternalResourceIDV1(
            pid = "http://www.knora.org/ontology/knora-base#hasStandoffLinkTo",
            id = "http://data.knora.org/project-thing-1"
        )
    )

    private val hasOtherThingOutgoingLink = PropertyV1(
        locations = Nil,
        value_rights = Vector(Some(8)),
        value_firstprops = Vector(Some("Another thing that only project members can see")),
        value_iconsrcs = Vector(Some("http://localhost:3335/project-icons/anything/thing.png")),
        value_restype = Vector(Some("Ding")),
        comments = Vector(""),
        value_ids = Vector("http://data.knora.org/project-thing-1/values/0"),
        values = Vector(LinkV1(
            valueResourceClassIcon = Some("http://localhost:3335/project-icons/anything/thing.png"),
            valueResourceClassLabel = Some("Ding"),
            valueResourceClass = Some("http://www.knora.org/ontology/anything#Thing"),
            valueLabel = Some("Another thing that only project members can see"),
            targetResourceIri = "http://data.knora.org/project-thing-2"
        )),
        occurrence = Some("0-n"),
        attributes = "restypeid=http://www.knora.org/ontology/anything#Thing",
        label = Some("Ein anderes Ding"),
        is_annotation = "0",
        guielement = Some("searchbox"),
        guiorder = Some(1),
        valuetype_id = Some("http://www.knora.org/ontology/knora-base#LinkValue"),
        regular_property = 1,
        pid = "http://www.knora.org/ontology/anything#hasOtherThing"
    )

    private val hasStandoffLinkToOutgoingLink = PropertyV1(
        locations = Nil,
        value_rights = Vector(Some(2)),
        value_firstprops = Vector(Some("Another thing that only project members can see")),
        value_iconsrcs = Vector(Some("http://localhost:3335/project-icons/anything/thing.png")),
        value_restype = Vector(Some("Ding")),
        comments = Vector(""),
        value_ids = Vector("http://data.knora.org/project-thing-1/values/1"),
        values = Vector(LinkV1(
            valueResourceClassIcon = Some("http://localhost:3335/project-icons/anything/thing.png"),
            valueResourceClassLabel = Some("Ding"),
            valueResourceClass = Some("http://www.knora.org/ontology/anything#Thing"),
            valueLabel = Some("Another thing that only project members can see"),
            targetResourceIri = "http://data.knora.org/project-thing-2"
        )),
        occurrence = Some("0-n"),
        attributes = "restypeid=http://www.knora.org/ontology/knora-base#Resource",
        label = Some("hat Standoff Link zu"),
        is_annotation = "0",
        guielement = None,
        guiorder = None,
        valuetype_id = Some("http://www.knora.org/ontology/knora-base#LinkValue"),
        regular_property = 1,
        pid = "http://www.knora.org/ontology/knora-base#hasStandoffLinkTo"
    )
}


/**
  * Tests [[ResourcesResponderV1]].
  */
class ResourcesResponderV1Spec extends CoreSpec() with ImplicitSender {
    import ResourcesResponderV1Spec._

    // Construct the actors needed for this test.
    private val actorUnderTest = TestActorRef[ResourcesResponderV1]

    val responderManager = system.actorOf(Props(new TestResponderManagerV1(Map(SIPI_ROUTER_ACTOR_NAME -> system.actorOf(Props(new MockSipiResponderV1))))), name = RESPONDER_MANAGER_ACTOR_NAME)

    private val storeManager = system.actorOf(Props(new StoreManager with LiveActorMaker), name = STORE_MANAGER_ACTOR_NAME)

    val rdfDataObjects = List(
        RdfDataObject(path = "../knora-ontologies/knora-base.ttl", name = "http://www.knora.org/ontology/knora-base"),
        RdfDataObject(path = "../knora-ontologies/knora-dc.ttl", name = "http://www.knora.org/ontology/dc"),
        RdfDataObject(path = "../knora-ontologies/salsah-gui.ttl", name = "http://www.knora.org/ontology/salsah-gui"),
        RdfDataObject(path = "_test_data/ontologies/incunabula-onto.ttl", name = "http://www.knora.org/ontology/incunabula"),
        RdfDataObject(path = "_test_data/all_data/incunabula-data.ttl", name = "http://www.knora.org/data/incunabula"),
        RdfDataObject(path = "_test_data/ontologies/anything-onto.ttl", name = "http://www.knora.org/ontology/anything"),
        RdfDataObject(path = "_test_data/all_data/anything-data.ttl", name = "http://www.knora.org/data/anything")
    )

    // The default timeout for receiving reply messages from actors.
    private val timeout = 60.seconds

    private val newBookResourceIri = new MutableTestIri
    private val newPageResourceIri = new MutableTestIri

    private def compareResourceFullResponses(received: ResourceFullResponseV1, expected: ResourceFullResponseV1): Unit = {
        // println(MessageUtil.toSource(received))

        assert(received.access == expected.access, "access does not match")
        assert(received.userdata == expected.userdata, "userdata does not match")
        assert(received.resinfo == expected.resinfo, "resinfo does not match")
        assert(received.resdata == expected.resdata, "resdata does not match")
        assert(received.incoming == expected.incoming, "incoming does not match")

        val sortedReceivedProps = received.props.get.properties.sortBy(_.pid)
        val sortedExpectedProps = expected.props.get.properties.sortBy(_.pid)

        assert(sortedReceivedProps.length == sortedExpectedProps.length, s"\n********** expected these properties:\n${MessageUtil.toSource(sortedExpectedProps)}\n********** received these properties:\n${MessageUtil.toSource(sortedReceivedProps)}")

        sortedExpectedProps.zip(sortedReceivedProps).foreach {
            case (expectedProp: PropertyV1, receivedProp: PropertyV1) =>

                // sort property attributes
                val expectedPropWithSortedAttr = expectedProp.copy(
                    attributes = expectedProp.attributes.sorted
                )

                val receivedPropWithSortedAttr = receivedProp.copy(
                    attributes = receivedProp.attributes.sorted
                )

                assert(receivedPropWithSortedAttr == expectedPropWithSortedAttr, s"These props do not match:\n********** Expected:\n${MessageUtil.toSource(expectedProp)}\n********** Received:\n${MessageUtil.toSource(receivedProp)}")
        }
    }

    private def compareResourceCompoundContextResponses(received: ResourceContextResponseV1, expected: ResourceContextResponseV1): Unit = {
        val receivedContext = received.resource_context
        val expectedContext = expected.resource_context

        assert(receivedContext.firstprop == expectedContext.firstprop, "firstprop does not match")
        assert(receivedContext.context == expectedContext.context, "context does not match")
        assert(receivedContext.preview == expectedContext.preview, "preview does not match")
        assert(receivedContext.locations.nonEmpty, "no locations given")
        assert(receivedContext.locations.get.size == 402, "the length of locations did not match")
        assert(receivedContext.locations.get.head == ResourcesResponderV1SpecContextData.expectedFirstLocationOfBookResourceContextResponse, "first location did not match")
        assert(receivedContext.canonical_res_id == expectedContext.canonical_res_id, "canonical_res_id does not match")
        assert(receivedContext.region == expectedContext.region, "region does not match")
        assert(receivedContext.res_id == expectedContext.res_id, "res_id does not match")
    }

    private def compareResourcePartOfContextResponses(received: ResourceContextResponseV1, expected: ResourceContextResponseV1): Unit = {
        val receivedContext = received.resource_context
        val expectedContext = expected.resource_context

        assert(receivedContext.resinfo == expectedContext.resinfo, "resinfo does not match")
        assert(receivedContext.parent_res_id == expectedContext.parent_res_id, "parent_res_id does not match")
        assert(receivedContext.context == expectedContext.context, "context does not match")
        assert(receivedContext.canonical_res_id == expectedContext.canonical_res_id, "canonical_res_id does not match")
        assert(receivedContext.parent_resinfo == expectedContext.parent_resinfo, "parent_resinfo does not match")
    }


    private def compareResourceSearchResults(received: ResourceSearchResponseV1, expected: ResourceSearchResponseV1): Unit = {

        assert(received.resources == expected.resources, "resources did not match")
    }

    private def checkResourceCreation(received: ResourceCreateResponseV1, expected: Map[IRI, Seq[ApiValueV1]]): Unit = {
        // sort values by their string representation
        val sortedValuesReceived: Map[IRI, Seq[ResourceCreateValueResponseV1]] = received.results.map {
            case (propIri, propValues: Seq[ResourceCreateValueResponseV1]) => (propIri, propValues.sortBy {
                valueObject: ResourceCreateValueResponseV1 =>
                    val stringValue = valueObject.value.textval.map {
                        case (valType: LiteralValueType.Value, value: String) => value // get string and ignore value type
                    }.head // each value is represented by a map consisting of only one item (e.g. string -> "book title")
                    stringValue
            })
        }

        // sort values by their string representation
        val sortedValuesExpected: Map[IRI, Seq[ResourceCreateValueResponseV1]] = expected.map {
            case (propIri, propValues) => (propIri, propValues.sortBy(_.toString))
        }.map {
            // turn the expected ApiValueV1s in ResourceCreateValueResponseV1 (these are returned by the actor).
            case (propIri: IRI, propValues: Seq[ApiValueV1]) =>
                (propIri, propValues.map {
                    case (propValue: ApiValueV1) =>
                        val valueResponse = CreateValueResponseV1(
                            value = propValue,
                            rights = 6,
                            id = "http://www.knora.org/test/values/test",
                            userdata = incunabulaUser.userData
                        )

                        // convert CreateValueResponseV1 to a ResourceCreateValueResponseV1
                        MessageUtil.convertCreateValueResponseV1ToResourceCreateValueResponseV1(
                            resourceIri = "http://www.knora.org/test",
                            ownerIri = "http://data.knora.org/users/b83acc5f05",
                            propertyIri = propIri,
                            valueResponse = valueResponse
                        )
                })
        }

        // compare expected and received values
        sortedValuesExpected.foreach {
            case (propIri, propValuesExpected) =>
                (propValuesExpected, sortedValuesReceived(propIri)).zipped.foreach {
                    case (expected: ResourceCreateValueResponseV1, received: ResourceCreateValueResponseV1) =>
                        assert(received.value.textval == expected.value.textval, "textval did not match")
                        assert(received.value.ival == expected.value.ival, "ival did not match")
                        assert(received.value.dval == expected.value.dval, "dval did not match")
                        assert(received.value.dateval1 == expected.value.dateval1, "dateval1 did not match")
                        assert(received.value.dateval2 == expected.value.dateval2, "dateval2 did not match")
                        assert(received.value.calendar == expected.value.calendar, "calendar did not match")
                        assert(received.value.dateprecision1 == expected.value.dateprecision1, "dateprecision1 did not match")
                        assert(received.value.dateprecision2 == expected.value.dateprecision2, "dateprecision2 did not match")
                        assert(received.value.timeval1 == expected.value.timeval1, "timeval1 did not match")
                        assert(received.value.timeval2 == expected.value.timeval2, "timeval2 did not match")
                }
        }

    }

    private def getLastModificationDate(resourceIri: IRI): Option[String] = {
        val lastModSparqlQuery = queries.sparql.v1.txt.getLastModificationDate(
            triplestore = settings.triplestoreType,
            resourceIri = resourceIri
        ).toString()

        storeManager ! SparqlSelectRequest(lastModSparqlQuery)

        expectMsgPF(timeout) {
            case response: SparqlSelectResponse =>
                val rows = response.results.bindings
                assert(rows.size <= 1, s"Resource $resourceIri has more than one instance of knora-base:lastModificationDate")

                if (rows.size == 1) {
                    Some(rows.head.rowMap("lastModificationDate"))
                } else {
                    None
                }
        }
    }

    private def comparePropertiesGetResponse(received: PropertiesGetResponseV1, expected: PropertiesGetResponseV1): Unit = {

        assert(received.properties.properties.length == expected.properties.properties.length, "The length of given properties is not correct.")

        expected.properties.properties.sortBy {
            // sort by property Iri
            prop => prop.pid
        }.zip(received.properties.properties.sortBy {
            prop => prop.pid
        }).foreach {
            case (expectedProp: PropertyGetV1, receivedProp: PropertyGetV1) =>

                // sort the values of each property
                val expectedPropValuesSorted = expectedProp.values.sortBy(values => values.textval)

                val receivedPropValuesSorted = receivedProp.values.sortBy(values => values.textval)

                // create PropertyGetV1 with sorted values
                val expectedPropSorted = expectedProp.copy(
                    values = expectedPropValuesSorted
                )

                val receivedPropSorted = receivedProp.copy(
                    values = receivedPropValuesSorted
                )

                assert(receivedPropSorted == expectedPropSorted, "Property did not match")
        }
    }

    private def comparePageContextRegionResponse(received: ResourceContextResponseV1): Unit = {

        assert(received.resource_context.resinfo.nonEmpty)

        assert(received.resource_context.resinfo.get.regions.nonEmpty)

        assert(received.resource_context.resinfo.get.regions.get.length == 2, "Number of given regions is not correct.")

        val regions: Seq[PropsGetForRegionV1] = received.resource_context.resinfo.get.regions.get

        val region1 = regions.filter {
            region => region.res_id == "http://data.knora.org/021ec18f1735"
        }

        val region2 = regions.filter {
            region => region.res_id == "http://data.knora.org/b6b64a62b006"
        }

        assert(region1.length == 1, "No region found with Iri 'http://data.knora.org/021ec18f1735'")

        assert(region2.length == 1, "No region found with Iri 'http://data.knora.org/b6b64a62b006'")

    }

    private def compareNewPageContextResponse(received: ResourceContextResponseV1): Unit = {

        assert(received.resource_context.resinfo.nonEmpty)

        // check that there is a preview
        assert(received.resource_context.resinfo.get.preview.nonEmpty)

        assert(received.resource_context.resinfo.get.locations.nonEmpty)

        // check that there are 7 locations
        assert(received.resource_context.resinfo.get.locations.get.length == 7)

    }

    "Load test data" in {
        storeManager ! ResetTriplestoreContent(rdfDataObjects)
        expectMsg(300.seconds, ResetTriplestoreContentACK())

        responderManager ! LoadOntologiesRequest(incunabulaUser)
        expectMsg(10.seconds, LoadOntologiesResponse())
    }

    "The resources responder" should {
        "return a full description of the book 'Zeitglöcklein des Lebens und Leidens Christi' in the Incunabula test data" in {
            // http://localhost:3333/v1/resources/http%3A%2F%2Fdata.knora.org%2Fc5058f3a
            actorUnderTest ! ResourceFullGetRequestV1(iri = "http://data.knora.org/c5058f3a", userProfile = incunabulaUser)

            expectMsgPF(timeout) {
                case response: ResourceFullResponseV1 => compareResourceFullResponses(received = response, expected = ResourcesResponderV1SpecFullData.expectedBookResourceFullResponse)
            }
        }

        "return a full description of the first page of the book 'Zeitglöcklein des Lebens und Leidens Christi' in the Incunabula test data" in {
            // http://localhost:3333/v1/resources/http%3A%2F%2Fdata.knora.org%2F8a0b1e75
            actorUnderTest ! ResourceFullGetRequestV1(iri = "http://data.knora.org/8a0b1e75", userProfile = incunabulaUser)

            expectMsgPF(timeout) {
                case response: ResourceFullResponseV1 => compareResourceFullResponses(received = response, expected = ResourcesResponderV1SpecFullData.expectedPageResourceFullResponse)
            }
        }

        "return a region with a comment containing standoff information (disabled because of issue 17)" ignore {
            // http://localhost:3333/v1/resources/http%3A%2F%2Fdata.knora.org%2F047db418ae06
            actorUnderTest ! ResourceFullGetRequestV1(iri = "http://data.knora.org/047db418ae06", userProfile = incunabulaUser)

            expectMsgPF(timeout) {
                case response: ResourceFullResponseV1 =>
                    // compareResourceFullResponses(received = response, expected = ResourcesResponderV1SpecFullData.expectedRegionFullResource)
            }
        }

        "return the context (describing 402 pages) of the book 'Zeitglöcklein des Lebens und Leidens Christi' in the Incunabula test data" in {
            // http://localhost:3333/v1/resources/http%3A%2F%2Fdata.knora.org%2Fc5058f3a?reqtype=context&resinfo=true
            actorUnderTest ! ResourceContextGetRequestV1(iri = "http://data.knora.org/c5058f3a", resinfo = true, userProfile = incunabulaUser)

            expectMsgPF(timeout) {
                case response: ResourceContextResponseV1 => compareResourceCompoundContextResponses(received = response, expected = ResourcesResponderV1SpecContextData.expectedBookResourceContextResponse)
            }
        }

        "return the context of a page of the book 'Zeitglöcklein des Lebens und Leidens Christi' in the Incunabula test data" in {
            // http://localhost:3333/v1/resources/http%3A%2F%2Fdata.knora.org%2F8a0b1e75?reqtype=context&resinfo=true
            actorUnderTest ! ResourceContextGetRequestV1(iri = "http://data.knora.org/8a0b1e75", resinfo = true, userProfile = incunabulaUser)

            expectMsgPF(timeout) {
                case response: ResourceContextResponseV1 => compareResourcePartOfContextResponses(received = response, expected = ResourcesResponderV1SpecContextData.expectedPageResourceContextResponse)
            }
        }

        "return 1 resource containing 'Reise in' in its label with three of its values" in {
            // http://localhost:3333/v1/resources?searchstr=Reise+in&numprops=3&limit=11&restype_id=-1
            actorUnderTest ! ResourceSearchGetRequestV1(
                searchString = "Reise in",
                numberOfProps = 3,
                limitOfResults = 11,
                resourceTypeIri = None,
                userProfile = incunabulaUser
            )

            expectMsgPF(timeout) {
                case response: ResourceSearchResponseV1 => compareResourceSearchResults(received = response, expected = ReiseInsHeiligelandThreeValues)
            }
        }

        "return 1 resource of type incunabula:book containing 'Reis' in its label with its label (first property)" in {
            // http://localhost:3333/v1/resources?searchstr=Reis&numprops=1&limit=11&restype_id=http%3A%2F%2Fwww.knora.org%2Fontology%2Fincunabula%23book
            actorUnderTest ! ResourceSearchGetRequestV1(
                searchString = "Reis",
                numberOfProps = 1,
                limitOfResults = 11,
                resourceTypeIri = Some("http://www.knora.org/ontology/incunabula#book"),
                userProfile = incunabulaUser
            )

            expectMsgPF(timeout) {
                case response: ResourceSearchResponseV1 => compareResourceSearchResults(received = response, expected = ReiseInsHeiligelandOneValueRestrictedToBook)
            }
        }

        "return 27 resources containing 'Narrenschiff' in their label" in {
            //http://localhost:3333/v1/resources?searchstr=Narrenschiff&numprops=4&limit=100&restype_id=-1

            // This query is going to return also resources of knora-baseLinkObj with a knora-base:hasComment.
            // Because this resource is directly defined in knora-base, its property knora-base:hasComment
            // has no guiOrder (normally, the guiOrder is defined in project specific ontologies) which used to cause problems in the SPARQL query.
            // Now, the guiOrder was made optional in the SPARQL query, and this test ensures that the query works as expected.

            actorUnderTest ! ResourceSearchGetRequestV1(
                searchString = "Narrenschiff",
                numberOfProps = 4,
                limitOfResults = 100,
                userProfile = incunabulaUser,
                resourceTypeIri = None
            )

            expectMsgPF(timeout) {
                case response: ResourceSearchResponseV1 =>
                    assert(response.resources.size == 27, s"expected 27 resources")
            }
        }

        "return 3 resources containing 'Narrenschiff' in their label of type incunabula:book" in {
            //http://localhost:3333/v1/resources?searchstr=Narrenschiff&numprops=3&limit=100&restype_id=-1

            actorUnderTest ! ResourceSearchGetRequestV1(
                searchString = "Narrenschiff",
                numberOfProps = 3,
                limitOfResults = 100,
                userProfile = incunabulaUser,
                resourceTypeIri = Some("http://www.knora.org/ontology/incunabula#book")
            )

            expectMsgPF(timeout) {
                case response: ResourceSearchResponseV1 =>
                    assert(response.resources.size == 3, s"expected 3 resources")
            }
        }

        "not create a resource when too many values are submitted for a property" in {
            // An incunabula:misc allows at most one color value.
            val valuesToBeCreated = Map(
                "http://www.knora.org/ontology/incunabula#miscHasColor" -> Vector(
                    CreateValueV1WithComment(ColorValueV1("#000000")),
                    CreateValueV1WithComment(ColorValueV1("#FFFFFF"))
                )
            )

            val resourceCreateRequest = ResourceCreateRequestV1(
                resourceTypeIri = "http://www.knora.org/ontology/incunabula#misc",
                label = "Test-Misc",
                projectIri = "http://data.knora.org/projects/77275339",
                values = valuesToBeCreated,
                userProfile = incunabulaUser,
                apiRequestID = UUID.randomUUID
            )

            actorUnderTest ! resourceCreateRequest

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[OntologyConstraintException] should ===(true)
            }
        }

        "not create a resource when a required value is not submitted" in {
            // Title and publoc are required but missing

            val author = Vector(
                CreateValueV1WithComment(TextValueV1(utf8str = "Franciscus de Retza"), None)
            )

            val pubdate = Vector(
                DateValueV1(
                    dateval1 = "1487",
                    dateval2 = "1490",
                    calendar = KnoraCalendarV1.JULIAN
                )
            )

            val valuesToBeCreated = Map(
                "http://www.knora.org/ontology/incunabula#hasAuthor" -> author,
                "http://www.knora.org/ontology/incunabula#pubdate" -> pubdate.map(date => CreateValueV1WithComment(DateUtilV1.dateValueV1ToJulianDayCountValueV1(date), None))
            )

            val resourceCreateRequest = ResourceCreateRequestV1(
                resourceTypeIri = "http://www.knora.org/ontology/incunabula#book",
                label = "Test-Book",
                projectIri = "http://data.knora.org/projects/77275339",
                values = valuesToBeCreated,
                userProfile = incunabulaUser,
                apiRequestID = UUID.randomUUID
            )

            actorUnderTest ! resourceCreateRequest

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[OntologyConstraintException] should ===(true)
            }
        }

        "create a new resource of type incunabula:book with values" in {

            val title1 = TextValueV1("A beautiful book")

            val citation1 = TextValueV1("ein Zitat")
            val citation2 = TextValueV1(
                utf8str = "This citation refers to another resource",
                textattr = Map(
                    StandoffTagV1.bold -> Vector(StandoffPositionV1(
                        start = 5,
                        end = 13
                    )),
                    StandoffTagV1.link -> Vector(StandoffPositionV1(
                        start = 32,
                        end = 40,
                        resid = Some("http://data.knora.org/c5058f3a")
                    ))
                ),
                resource_reference = Set("http://data.knora.org/c5058f3a")
            )
            val citation3 = TextValueV1("und noch eines")
            val citation4 = TextValueV1("noch ein letztes")

            val publoc = TextValueV1("Entenhausen")

            val pubdateRequest = DateUtilV1.createJDCValueV1FromDateString("GREGORIAN:2015-12-03")
            val pubdateResponse = DateValueV1(dateval1 = "2015-12-03", dateval2 = "2015-12-03", calendar = KnoraCalendarV1.GREGORIAN)

            val valuesToBeCreated: Map[IRI, Seq[CreateValueV1WithComment]] = Map(
                "http://www.knora.org/ontology/incunabula#title" -> Vector(CreateValueV1WithComment(title1)),
                "http://www.knora.org/ontology/incunabula#pubdate" -> Vector(CreateValueV1WithComment(pubdateRequest)),
                "http://www.knora.org/ontology/incunabula#citation" -> Vector(
                    CreateValueV1WithComment(citation4, None),
                    CreateValueV1WithComment(citation1, None),
                    CreateValueV1WithComment(citation3, None),
                    CreateValueV1WithComment(citation2, None)
                ),
                "http://www.knora.org/ontology/incunabula#publoc" -> Vector(CreateValueV1WithComment(publoc))
            )

            val valuesExpected = Map(
                "http://www.knora.org/ontology/incunabula#title" -> Vector(title1),
                "http://www.knora.org/ontology/incunabula#pubdate" -> Vector(pubdateResponse),
                "http://www.knora.org/ontology/incunabula#citation" -> Vector(citation3, citation1, citation4, citation2),
                "http://www.knora.org/ontology/incunabula#publoc" -> Vector(publoc)
            )


            actorUnderTest ! ResourceCreateRequestV1(
                resourceTypeIri = "http://www.knora.org/ontology/incunabula#book",
                label = "Test-Book",
                projectIri = "http://data.knora.org/projects/77275339",
                values = valuesToBeCreated,
                userProfile = incunabulaUser,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case response: ResourceCreateResponseV1 =>
                    newBookResourceIri.set(response.res_id)
                    checkResourceCreation(received = response, expected = valuesExpected)
            }

            // Check that the resource doesn't have more than one lastModificationDate.
            getLastModificationDate(newBookResourceIri.get)

            // See if we can query the resource.

            actorUnderTest ! ResourceFullGetRequestV1(iri = newBookResourceIri.get, userProfile = incunabulaUser)

            expectMsgPF(timeout) {
                case response: ResourceFullResponseV1 => () // If we got a ResourceFullResponseV1, the operation succeeded.
            }
        }

        "create an incunabula:page with a resource pointer" in {
            val recto = TextValueV1("recto")
            val origname = TextValueV1("Blatt")
            val seqnum = IntegerValueV1(1)

            val fileValueFull = StillImageFileValueV1(
                internalMimeType = "image/jp2",
                internalFilename = "gaga.jpg",
                originalFilename = "test.jpg",
                originalMimeType = Some("image/jpg"),
                dimX = 1000,
                dimY = 1000,
                qualityLevel = 100,
                qualityName = Some("full"),
                isPreview = false
            )

            val fileValueThumb = StillImageFileValueV1(
                internalMimeType = "image/jpeg",
                internalFilename = "gaga.jpg",
                originalFilename = "test.jpg",
                originalMimeType = Some("image/jpg"),
                dimX = 100,
                dimY = 100,
                qualityLevel = 10,
                qualityName = Some("thumbnail"),
                isPreview = true
            )

            val book = newBookResourceIri.get

            val valuesToBeCreated = Map(
                "http://www.knora.org/ontology/incunabula#hasRightSideband" -> Vector(CreateValueV1WithComment(LinkUpdateV1(targetResourceIri = "http://data.knora.org/482a33d65c36"))),
                "http://www.knora.org/ontology/incunabula#pagenum" -> Vector(CreateValueV1WithComment(recto)),
                "http://www.knora.org/ontology/incunabula#partOf" -> Vector(CreateValueV1WithComment(LinkUpdateV1(book))),
                "http://www.knora.org/ontology/incunabula#origname" -> Vector(CreateValueV1WithComment(origname)),
                "http://www.knora.org/ontology/incunabula#seqnum" -> Vector(CreateValueV1WithComment(seqnum))
            )

            val expected = Map(
                "http://www.knora.org/ontology/incunabula#hasRightSideband" -> Vector(LinkV1(targetResourceIri = "http://data.knora.org/482a33d65c36", valueResourceClass = Some("http://www.knora.org/ontology/incunabula#Sideband"))),
                "http://www.knora.org/ontology/incunabula#pagenum" -> Vector(recto),
                "http://www.knora.org/ontology/incunabula#partOf" -> Vector(LinkV1(book)),
                "http://www.knora.org/ontology/incunabula#origname" -> Vector(origname),
                "http://www.knora.org/ontology/incunabula#seqnum" -> Vector(seqnum),
                OntologyConstants.KnoraBase.HasStillImageFileValue -> Vector(fileValueFull, fileValueThumb)
            )

            actorUnderTest ! ResourceCreateRequestV1(
                resourceTypeIri = "http://www.knora.org/ontology/incunabula#page",
                label = "Test-Page",
                projectIri = "http://data.knora.org/projects/77275339",
                values = valuesToBeCreated,
                file = Some(SipiResponderConversionFileRequestV1(
                    originalFilename = "test.jpg",
                    originalMimeType = "image/jpeg",
                    filename = "./test_server/images/Chlaus.jpg",
                    userProfile = incunabulaUser
                )),
                userProfile = incunabulaUser,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case response: ResourceCreateResponseV1 =>
                    newPageResourceIri.set(response.res_id)
                    checkResourceCreation(received = response, expected = expected)
            }
        }

        "get the context of a newly created incunabula:page and check its locations" in {

            val resIri: IRI = newPageResourceIri.get

            val pageGetContext = ResourceContextGetRequestV1(
                iri = resIri,
                resinfo = true,
                userProfile = incunabulaUser
            )

            actorUnderTest ! pageGetContext

            expectMsgPF(timeout) {
                case response: ResourceContextResponseV1 =>
                    compareNewPageContextResponse(received = response)
            }
        }

        "mark a resource as deleted" in {
            val lastModBeforeUpdate = getLastModificationDate(newPageResourceIri.get)

            val resourceDeleteRequest = ResourceDeleteRequestV1(
                resourceIri = newPageResourceIri.get,
                deleteComment = Some("This page was deleted as a test"),
                userProfile = incunabulaUser,
                apiRequestID = UUID.randomUUID
            )

            actorUnderTest ! resourceDeleteRequest

            expectMsg(timeout, ResourceDeleteResponseV1(id = newPageResourceIri.get, userdata = incunabulaUser.userData))

            // Check that the resource is marked as deleted.
            actorUnderTest ! ResourceInfoGetRequestV1(iri = newPageResourceIri.get, userProfile = incunabulaUser)

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[NotFoundException] should ===(true)
            }

            // Check that the resource's last modification date got updated.
            val lastModAfterUpdate = getLastModificationDate(newPageResourceIri.get)
            lastModBeforeUpdate != lastModAfterUpdate should ===(true)
        }

        "get the properties of a resource" in {

            val propertiesGetRequest = PropertiesGetRequestV1(
                "http://data.knora.org/021ec18f1735",
                incunabulaUser
            )

            actorUnderTest ! propertiesGetRequest

            expectMsgPF(timeout) {
                case response: PropertiesGetResponseV1 => comparePropertiesGetResponse(received = response, expected = propertiesGetResponseV1Region)
            }
        }

        "get the regions of a page pointed to by regions" in {

            val resourceContextPage = ResourceContextGetRequestV1(iri = "http://data.knora.org/9d626dc76c03", resinfo = true, userProfile = incunabulaUser)

            actorUnderTest ! resourceContextPage

            expectMsgPF(timeout) {
                case response: ResourceContextResponseV1 => comparePageContextRegionResponse(received = response)
            }
        }

        "show incoming standoff links if the user has view permission on both resources, but show other incoming links only if the user also has view permission on the link" in {
            // The link's owner, anythingUser1, should see the hasOtherThing link as well as the hasStandoffLinkTo link.

            actorUnderTest ! ResourceFullGetRequestV1(iri = "http://data.knora.org/project-thing-2", userProfile = anythingUser1)

            expectMsgPF(timeout) {
                case response: ResourceFullResponseV1 =>
                    response.incoming.size should ===(2)
                    response.incoming.contains(hasStandoffLinkToIncomingLink) should ===(true)
                    response.incoming.contains(hasOtherThingIncomingLink) should ===(true)
            }

            // But another user should see only the hasStandoffLinkTo link.

            actorUnderTest ! ResourceFullGetRequestV1(iri = "http://data.knora.org/project-thing-2", userProfile = anythingUser2)

            expectMsgPF(timeout) {
                case response: ResourceFullResponseV1 =>
                    response.incoming.contains(hasStandoffLinkToIncomingLink) should ===(true)
                    response.incoming.contains(hasOtherThingIncomingLink) should ===(false)
            }
        }

        "show outgoing standoff links if the user has view permission on both resources, but show other outgoing links only if the user also has view permission on the link" in {
            // The link's owner, anythingUser1, should see the hasOtherThing link as well as the hasStandoffLinkTo link.

            actorUnderTest ! ResourceFullGetRequestV1(iri = "http://data.knora.org/project-thing-1", userProfile = anythingUser1)

            expectMsgPF(timeout) {
                case response: ResourceFullResponseV1 =>
                    val linkProps = response.props.get.properties.filter {
                        prop => prop.values.nonEmpty && prop.valuetype_id.get != OntologyConstants.KnoraBase.TextValue
                    }

                    linkProps.size should ===(2)

                    linkProps.contains(hasStandoffLinkToOutgoingLink) should ===(true)
                    linkProps.contains(hasOtherThingOutgoingLink) should ===(true)
            }

            // But another user should see only the hasStandoffLinkTo link.

            actorUnderTest ! ResourceFullGetRequestV1(iri = "http://data.knora.org/project-thing-1", userProfile = anythingUser2)

            expectMsgPF(timeout) {
                case response: ResourceFullResponseV1 =>
                    val linkProps = response.props.get.properties.filter {
                        prop => prop.values.nonEmpty && prop.valuetype_id.get != OntologyConstants.KnoraBase.TextValue
                    }

                    linkProps.size should ===(1)
                    linkProps.contains(hasStandoffLinkToOutgoingLink) should ===(true)
                    linkProps.contains(hasOtherThingOutgoingLink) should ===(false)
            }
        }

        "show a contained resource in a context request only if the user has permission to see the containing resource, the contained resource, and the link value" in {
            // The owner of the resources and the link should see two contained resources.

            actorUnderTest ! ResourceContextGetRequestV1(iri = "http://data.knora.org/containing-thing", resinfo = true, userProfile = anythingUser1)

            expectMsgPF(timeout) {
                case response: ResourceContextResponseV1 =>
                    response.resource_context.res_id should ===(Some(Vector(
                        "http://data.knora.org/contained-thing-1",
                        "http://data.knora.org/contained-thing-2"
                    )))
            }

            // Another user in the project, who doesn't have permission to see the second link, should see only one contained resource.

            actorUnderTest ! ResourceContextGetRequestV1(iri = "http://data.knora.org/containing-thing", resinfo = true, userProfile = anythingUser2)

            expectMsgPF(timeout) {
                case response: ResourceContextResponseV1 =>
                    response.resource_context.res_id should ===(Some(Vector("http://data.knora.org/contained-thing-1")))
            }

            // A user who's not in the project shouldn't see any contained resources.

            actorUnderTest ! ResourceContextGetRequestV1(iri = "http://data.knora.org/containing-thing", resinfo = true, userProfile = incunabulaUser)

            expectMsgPF(timeout) {
                case response: ResourceContextResponseV1 =>
                    response.resource_context.res_id should ===(None)
            }
        }

        "not create an instance of knora-base:Resource" in {
            actorUnderTest ! ResourceCreateRequestV1(
                resourceTypeIri = "http://www.knora.org/ontology/knora-base#Resource",
                label = "Test Resource",
                projectIri = "http://data.knora.org/projects/77275339",
                values = Map.empty[IRI, Seq[CreateValueV1WithComment]],
                file = None,
                userProfile = incunabulaUser,
                apiRequestID = UUID.randomUUID
            )

            expectMsgPF(timeout) {
                case msg: akka.actor.Status.Failure => msg.cause.isInstanceOf[BadRequestException] should ===(true)
            }
        }
    }
}