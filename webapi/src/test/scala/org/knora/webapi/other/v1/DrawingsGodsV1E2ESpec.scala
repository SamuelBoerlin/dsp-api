/*
 * Copyright © 2021 Data and Service Center for the Humanities and/or DaSCH Service Platform contributors.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.knora.webapi.other.v1

import java.net.URLEncoder

import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import com.typesafe.config.ConfigFactory
import org.knora.webapi.E2ESpec
import org.knora.webapi.messages.store.triplestoremessages.{RdfDataObject, TriplestoreJsonProtocol}
import org.knora.webapi.util.{MutableTestIri, ResourceResponseExtractorMethods, ValuesResponseExtractorMethods}

object DrawingsGodsV1E2ESpec {
  val config = ConfigFactory.parseString("""
          akka.loglevel = "DEBUG"
          akka.stdout-loglevel = "DEBUG"
        """.stripMargin)
}

/**
 * End-to-End (E2E) test specification for additional testing of permissions.
 */
class DrawingsGodsV1E2ESpec extends E2ESpec(DrawingsGodsV1E2ESpec.config) with TriplestoreJsonProtocol {

  override lazy val rdfDataObjects: List[RdfDataObject] = List(
    RdfDataObject(
      path = "test_data/other.v1.DrawingsGodsV1E2ESpec/rvp-admin-data.ttl",
      name = "http://www.knora.org/data/admin"
    ),
    RdfDataObject(
      path = "test_data/other.v1.DrawingsGodsV1E2ESpec/rvp-permissions-data.ttl",
      name = "http://www.knora.org/data/permissions"
    ),
    RdfDataObject(
      path = "test_data/other.v1.DrawingsGodsV1Spec/drawings-gods_admin-data.ttl",
      name = "http://www.knora.org/data/admin"
    ),
    RdfDataObject(
      path = "test_data/other.v1.DrawingsGodsV1Spec/drawings-gods_permissions-data.ttl",
      name = "http://www.knora.org/data/permissions"
    ),
    RdfDataObject(
      path = "test_data/other.v1.DrawingsGodsV1Spec/drawings-gods_ontology.ttl",
      name = "http://www.knora.org/ontology/0105/drawings-gods"
    ),
    RdfDataObject(
      path = "test_data/other.v1.DrawingsGodsV1Spec/drawings-gods_data.ttl",
      name = "http://www.knora.org/data/0105/drawings-gods"
    ),
    RdfDataObject(
      path = "test_data/other.v1.DrawingsGodsV1Spec/parole-religieuse-dummy-onto.ttl",
      name = "http://www.knora.org/ontology/0106/parole-religieuse"
    )
  )

  /**
   *  1a. parole-religieuse user creates a resource
   *  1b. parole-religieuse user create a value
   *  2a. drawings-gods user changes existing value
   *  2b. drawings-gods user creates a new value (inside parole-religieuse project)
   */
  "issue: https://github.com/dhlab-basel/Knora/issues/408" should {

    val drawingsOfGodsUserEmail = "ddd1@unil.ch"
    val paroleReligieuseUserEmail = "parole@unil.ch"
    val testPass = "test"
    val thingIri = new MutableTestIri
    val firstValueIri = new MutableTestIri
    val secondValueIri = new MutableTestIri

    "allow parole-religieuse user to create a resource inside his own project (1a)" in {

      val params =
        s"""
           |{
           |    "restype_id": "http://www.knora.org/ontology/0106/parole-religieuse#Thing",
           |    "label": "A thing",
           |    "project_id": "http://rdfh.ch/projects/0106",
           |    "properties": {}
           |}
                """.stripMargin

      val request =
        Post(baseApiUrl + s"/v1/resources", HttpEntity(ContentTypes.`application/json`, params)) ~> addCredentials(
          BasicHttpCredentials(paroleReligieuseUserEmail, testPass)
        )
      val response: HttpResponse = singleAwaitingRequest(request)

      assert(response.status === StatusCodes.OK)
      val resId = ResourceResponseExtractorMethods.getResIriFromJsonResponse(response)

      thingIri.set(resId)
      logger.debug(s"1a. thingIri: ${thingIri.get}")
    }

    "allow the parole-religieuse user to add an integer value to a previously created resource (1b)" in {
      val params =
        s"""
           |{
           |    "res_id": "${thingIri.get}",
           |    "prop": "http://www.knora.org/ontology/0106/parole-religieuse#hasInteger",
           |    "int_value": 1234
           |}
                """.stripMargin

      val request =
        Post(baseApiUrl + s"/v1/values", HttpEntity(ContentTypes.`application/json`, params)) ~> addCredentials(
          BasicHttpCredentials(paroleReligieuseUserEmail, testPass)
        )
      val response: HttpResponse = singleAwaitingRequest(request)

      assert(response.status === StatusCodes.OK)
      val valId = ValuesResponseExtractorMethods.getNewValueIriFromJsonResponse(response)

      firstValueIri.set(valId)
      logger.debug(s"1b. firstValueIri: ${firstValueIri.get}")
    }

    "allow the drawings-gods user to change the existing value (2a)" in {
      val params =
        s"""
           |{
           |    "res_id": "${thingIri.get}",
           |    "prop": "http://www.knora.org/ontology/0106/parole-religieuse#hasInteger",
           |    "int_value": 1111
           |}
                """.stripMargin

      val request = Put(
        baseApiUrl + s"/v1/values/${URLEncoder.encode(firstValueIri.get, "UTF-8")}",
        HttpEntity(ContentTypes.`application/json`, params)
      ) ~> addCredentials(BasicHttpCredentials(drawingsOfGodsUserEmail, testPass))
      val response: HttpResponse = singleAwaitingRequest(request)

      assert(response.status === StatusCodes.OK)
      val valId = ValuesResponseExtractorMethods.getNewValueIriFromJsonResponse(response)

      firstValueIri.set(valId)
      logger.debug(s"2a. firstValueIri: ${firstValueIri.get}")
    }

    "allow the drawings-gods user to create a new value inside the parole-religieuse project (2b)" in {
      val params =
        s"""
           |{
           |    "res_id": "${thingIri.get}",
           |    "prop": "http://www.knora.org/ontology/0106/parole-religieuse#hasInteger",
           |    "int_value": 2222
           |}
                """.stripMargin

      val request =
        Post(baseApiUrl + s"/v1/values", HttpEntity(ContentTypes.`application/json`, params)) ~> addCredentials(
          BasicHttpCredentials(drawingsOfGodsUserEmail, testPass)
        )
      val response: HttpResponse = singleAwaitingRequest(request)

      assert(response.status === StatusCodes.OK)
      val valId = ValuesResponseExtractorMethods.getNewValueIriFromJsonResponse(response)

      secondValueIri.set(valId)
      logger.debug(s"2b. secondValueIri: ${secondValueIri.get}")
    }
  }

}
