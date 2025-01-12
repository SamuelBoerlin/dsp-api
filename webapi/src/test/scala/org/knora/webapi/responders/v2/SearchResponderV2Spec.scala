/*
 * Copyright © 2021 Data and Service Center for the Humanities and/or DaSCH Service Platform contributors.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.knora.webapi.responders.v2

import akka.testkit.ImplicitSender
import org.knora.webapi.messages.IriConversions._
import org.knora.webapi.messages.store.triplestoremessages.RdfDataObject
import org.knora.webapi.messages.v2.responder.resourcemessages._
import org.knora.webapi.messages.v2.responder.searchmessages._
import org.knora.webapi.messages.v2.responder.valuemessages.{
  HierarchicalListValueContentV2,
  ReadValueV2,
  StillImageFileValueContentV2
}
import org.knora.webapi.messages.{SmartIri, StringFormatter}
import org.knora.webapi.responders.v2.ResourcesResponseCheckerV2.compareReadResourcesSequenceV2Response
import org.knora.webapi.sharedtestdata.SharedTestDataADM
import org.knora.webapi.{ApiV2Complex, CoreSpec, IRI, SchemaOptions}

import java.util.UUID
import scala.concurrent.duration._

/**
 * Tests [[SearchResponderV2]].
 */
class SearchResponderV2Spec extends CoreSpec() with ImplicitSender {

  private implicit val stringFormatter: StringFormatter = StringFormatter.getGeneralInstance
  override lazy val rdfDataObjects = List(
    RdfDataObject(path = "test_data/all_data/incunabula-data.ttl", name = "http://www.knora.org/data/0803/incunabula"),
    RdfDataObject(path = "test_data/demo_data/images-demo-data.ttl", name = "http://www.knora.org/data/00FF/images"),
    RdfDataObject(path = "test_data/all_data/anything-data.ttl", name = "http://www.knora.org/data/0001/anything"),
    RdfDataObject(path = "test_data/all_data/incunabula-data.ttl", name = "http://www.knora.org/data/0803/incunabula"),
    RdfDataObject(path = "test_data/ontologies/books-onto.ttl", name = "http://www.knora.org/ontology/0001/anything"),
    RdfDataObject(path = "test_data/all_data/books-data.ttl", name = "http://www.knora.org/data/0001/anything")
  )
  private val searchResponderV2SpecFullData = new SearchResponderV2SpecFullData
  private val anythingUserProfile = SharedTestDataADM.anythingUser2
  // The default timeout for receiving reply messages from actors.
  private val timeout = 10.seconds

  "The search responder v2" should {

    "perform a fulltext search for 'Narr'" in {

      responderManager ! FulltextSearchRequestV2(
        searchValue = "Narr",
        offset = 0,
        limitToProject = None,
        limitToResourceClass = None,
        limitToStandoffClass = None,
        returnFiles = false,
        targetSchema = ApiV2Complex,
        schemaOptions = SchemaOptions.ForStandoffWithTextValues,
        featureFactoryConfig = defaultFeatureFactoryConfig,
        requestingUser = SharedTestDataADM.anonymousUser
      )

      expectMsgPF(timeout) { case response: ReadResourcesSequenceV2 =>
        compareReadResourcesSequenceV2Response(
          expected = searchResponderV2SpecFullData.fulltextSearchForNarr,
          received = response
        )
      }
    }

    "perform a fulltext search for 'Dinge'" in {

      responderManager ! FulltextSearchRequestV2(
        searchValue = "Dinge",
        offset = 0,
        limitToProject = None,
        limitToResourceClass = None,
        limitToStandoffClass = None,
        returnFiles = false,
        targetSchema = ApiV2Complex,
        schemaOptions = SchemaOptions.ForStandoffWithTextValues,
        featureFactoryConfig = defaultFeatureFactoryConfig,
        requestingUser = SharedTestDataADM.anythingUser1
      )

      expectMsgPF(timeout) { case response: ReadResourcesSequenceV2 =>
        compareReadResourcesSequenceV2Response(
          expected = searchResponderV2SpecFullData.fulltextSearchForDinge,
          received = response
        )
      }

    }

    "return files attached to full-text search results" in {

      responderManager ! FulltextSearchRequestV2(
        searchValue = "p7v",
        offset = 0,
        limitToProject = None,
        limitToResourceClass = None,
        limitToStandoffClass = None,
        returnFiles = true,
        targetSchema = ApiV2Complex,
        schemaOptions = SchemaOptions.ForStandoffWithTextValues,
        featureFactoryConfig = defaultFeatureFactoryConfig,
        requestingUser = SharedTestDataADM.anythingUser1
      )

      expectMsgPF(timeout) { case response: ReadResourcesSequenceV2 =>
        val hasImageFileValues: Boolean =
          response.resources.flatMap(_.values.values.flatten).exists { readValueV2: ReadValueV2 =>
            readValueV2.valueContent match {
              case _: StillImageFileValueContentV2 => true
              case _                               => false
            }
          }

        assert(hasImageFileValues)
      }

    }

    "perform an extended search for books that have the title 'Zeitglöcklein des Lebens'" in {

      responderManager ! GravsearchRequestV2(
        constructQuery = searchResponderV2SpecFullData.constructQueryForBooksWithTitleZeitgloecklein,
        targetSchema = ApiV2Complex,
        schemaOptions = SchemaOptions.ForStandoffWithTextValues,
        featureFactoryConfig = defaultFeatureFactoryConfig,
        requestingUser = SharedTestDataADM.anonymousUser
      )

      // extended search sort by resource Iri by default if no order criterion is indicated
      expectMsgPF(timeout) { case response: ReadResourcesSequenceV2 =>
        compareReadResourcesSequenceV2Response(
          expected = searchResponderV2SpecFullData.booksWithTitleZeitgloeckleinResponse,
          received = response
        )
      }

    }

    "perform an extended search for books that do not have the title 'Zeitglöcklein des Lebens'" in {

      responderManager ! GravsearchRequestV2(
        constructQuery = searchResponderV2SpecFullData.constructQueryForBooksWithoutTitleZeitgloecklein,
        targetSchema = ApiV2Complex,
        schemaOptions = SchemaOptions.ForStandoffWithTextValues,
        featureFactoryConfig = defaultFeatureFactoryConfig,
        requestingUser = SharedTestDataADM.anonymousUser
      )

      // extended search sort by resource Iri by default if no order criterion is indicated
      expectMsgPF(timeout) { case response: ReadResourcesSequenceV2 =>
        // TODO: do better testing once JSON-LD can be converted back into case classes
        assert(response.resources.size == 18, s"18 books were expected, but ${response.resources.size} given.")
      }

    }

    "perform a search by label for incunabula:book that contain 'Narrenschiff'" in {

      responderManager ! SearchResourceByLabelRequestV2(
        searchValue = "Narrenschiff",
        offset = 0,
        limitToProject = None,
        limitToResourceClass = Some("http://www.knora.org/ontology/0803/incunabula#book".toSmartIri), // internal Iri!
        targetSchema = ApiV2Complex,
        featureFactoryConfig = defaultFeatureFactoryConfig,
        requestingUser = SharedTestDataADM.anonymousUser
      )

      expectMsgPF(timeout) { case response: ReadResourcesSequenceV2 =>
        assert(response.resources.size == 3, s"3 results were expected, but ${response.resources.size} given")
      }

    }

    "perform a search by label for incunabula:book that contain 'Das Narrenschiff'" in {

      responderManager ! SearchResourceByLabelRequestV2(
        searchValue = "Das Narrenschiff",
        offset = 0,
        limitToProject = None,
        limitToResourceClass = Some("http://www.knora.org/ontology/0803/incunabula#book".toSmartIri), // internal Iri!
        targetSchema = ApiV2Complex,
        featureFactoryConfig = defaultFeatureFactoryConfig,
        requestingUser = SharedTestDataADM.anonymousUser
      )

      expectMsgPF(timeout) { case response: ReadResourcesSequenceV2 =>
        assert(response.resources.size == 3, s"3 results were expected, but ${response.resources.size} given")
      }

    }

    "perform a count search query by label for incunabula:book that contain 'Narrenschiff'" in {

      responderManager ! SearchResourceByLabelCountRequestV2(
        searchValue = "Narrenschiff",
        limitToProject = None,
        limitToResourceClass = Some("http://www.knora.org/ontology/0803/incunabula#book".toSmartIri), // internal Iri!
        featureFactoryConfig = defaultFeatureFactoryConfig,
        requestingUser = SharedTestDataADM.anonymousUser
      )

      expectMsgPF(timeout) { case response: ResourceCountV2 =>
        assert(response.numberOfResources == 3, s"3 results were expected, but ${response.numberOfResources} given")
      }

    }

    "perform a a count search query by label for incunabula:book that contain 'Das Narrenschiff'" in {

      responderManager ! SearchResourceByLabelCountRequestV2(
        searchValue = "Das Narrenschiff",
        limitToProject = None,
        limitToResourceClass = Some("http://www.knora.org/ontology/0803/incunabula#book".toSmartIri), // internal Iri!
        featureFactoryConfig = defaultFeatureFactoryConfig,
        requestingUser = SharedTestDataADM.anonymousUser
      )

      expectMsgPF(timeout) { case response: ResourceCountV2 =>
        assert(response.numberOfResources == 3, s"3 results were expected, but ${response.numberOfResources} given")
      }

    }

    "search by project and resource class" in {
      responderManager ! SearchResourcesByProjectAndClassRequestV2(
        projectIri = SharedTestDataADM.incunabulaProject.id.toSmartIri,
        resourceClass = "http://0.0.0.0:3333/ontology/0803/incunabula/v2#book".toSmartIri,
        orderByProperty = Some("http://0.0.0.0:3333/ontology/0803/incunabula/v2#title".toSmartIri),
        schemaOptions = SchemaOptions.ForStandoffWithTextValues,
        page = 0,
        targetSchema = ApiV2Complex,
        featureFactoryConfig = defaultFeatureFactoryConfig,
        requestingUser = SharedTestDataADM.incunabulaProjectAdminUser
      )

      expectMsgPF(timeout) { case response: ReadResourcesSequenceV2 =>
        response.resources.size should ===(19)
      }
    }

    "search for list label" in {

      responderManager ! FulltextSearchRequestV2(
        searchValue = "non fiction",
        offset = 0,
        limitToProject = None,
        limitToResourceClass = None,
        limitToStandoffClass = None,
        returnFiles = false,
        targetSchema = ApiV2Complex,
        schemaOptions = SchemaOptions.ForStandoffWithTextValues,
        featureFactoryConfig = defaultFeatureFactoryConfig,
        requestingUser = SharedTestDataADM.anythingUser1
      )

      expectMsgPF(timeout) { case response: ReadResourcesSequenceV2 =>
        compareReadResourcesSequenceV2Response(
          expected = searchResponderV2SpecFullData.expectedResultFulltextSearchForListNodeLabel,
          received = response
        )
      }
    }

    "search for list label and find sub-nodes" in {

      responderManager ! FulltextSearchRequestV2(
        searchValue = "novel",
        offset = 0,
        limitToProject = None,
        limitToResourceClass = None,
        limitToStandoffClass = None,
        returnFiles = false,
        targetSchema = ApiV2Complex,
        schemaOptions = SchemaOptions.ForStandoffWithTextValues,
        featureFactoryConfig = defaultFeatureFactoryConfig,
        requestingUser = SharedTestDataADM.anythingUser1
      )

      expectMsgPF(timeout) { case response: ReadResourcesSequenceV2 =>
        compareReadResourcesSequenceV2Response(
          expected = searchResponderV2SpecFullData.expectedResultFulltextSearchForListNodeLabelWithSubnodes,
          received = response
        )
      }
    }

  }

}
