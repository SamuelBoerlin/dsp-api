/*
 * Copyright © 2021 Data and Service Center for the Humanities and/or DaSCH Service Platform contributors.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.knora.webapi.e2e.v1

import java.net.URLEncoder

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.testkit.RouteTestTimeout
import org.knora.webapi._
import org.knora.webapi.http.directives.DSPApiDirectives
import org.knora.webapi.messages.store.triplestoremessages.RdfDataObject
import org.knora.webapi.messages.v1.responder.valuemessages.ApiValueV1JsonProtocol._
import org.knora.webapi.routing.v1.ValuesRouteV1
import org.knora.webapi.sharedtestdata.SharedTestDataV1
import org.knora.webapi.util.{AkkaHttpUtils, MutableTestIri}
import spray.json._

/**
 * Tests the values route.
 */
class ValuesV1R2RSpec extends R2RSpec {

  override def testConfigSource: String =
    """
         # akka.loglevel = "DEBUG"
         # akka.stdout-loglevel = "DEBUG"
        """.stripMargin

  private val valuesPath = DSPApiDirectives.handleErrors(system)(new ValuesRouteV1(routeData).knoraApiPath)

  implicit def default(implicit system: ActorSystem): RouteTestTimeout = RouteTestTimeout(settings.defaultTimeout)

  private val integerValueIri = new MutableTestIri
  private val timeValueIri = new MutableTestIri
  private val textValueIri = new MutableTestIri
  private val linkValueIri = new MutableTestIri
  private val textValueWithLangIri = new MutableTestIri
  private val boringComment = "This is a boring comment."

  override lazy val rdfDataObjects = List(
    RdfDataObject(path = "test_data/all_data/anything-data.ttl", name = "http://www.knora.org/data/0001/anything")
  )

  private val anythingUser = SharedTestDataV1.anythingUser1
  private val anythingUserEmail = anythingUser.userData.email.get
  private val testPass = "test"

  private val mappingIri = "http://rdfh.ch/standoff/mappings/StandardMapping"

  "The Values Endpoint" should {
    "add an integer value to a resource" in {
      val params =
        """
          |{
          |    "res_id": "http://rdfh.ch/0001/a-thing",
          |    "prop": "http://www.knora.org/ontology/0001/anything#hasInteger",
          |    "int_value": 1234
          |}
                """.stripMargin

      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, params)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, testPass)
      ) ~> valuesPath ~> check {
        assert(status == StatusCodes.OK, response.toString)
        val responseJson: Map[String, JsValue] = responseAs[String].parseJson.asJsObject.fields
        val valueIri: IRI = responseJson("id").asInstanceOf[JsString].value
        integerValueIri.set(valueIri)
      }
    }

    "change an integer value" in {
      val params =
        """
          |{
          |    "res_id": "http://rdfh.ch/0001/a-thing",
          |    "prop": "http://www.knora.org/ontology/0001/anything#hasInteger",
          |    "int_value": 4321
          |}
                """.stripMargin

      Put(
        s"/v1/values/${URLEncoder.encode(integerValueIri.get, "UTF-8")}",
        HttpEntity(ContentTypes.`application/json`, params)
      ) ~> addCredentials(BasicHttpCredentials(anythingUserEmail, testPass)) ~> valuesPath ~> check {
        assert(status == StatusCodes.OK, response.toString)
        val responseJson: Map[String, JsValue] = responseAs[String].parseJson.asJsObject.fields
        val valueIri: IRI = responseJson("id").asInstanceOf[JsString].value
        integerValueIri.set(valueIri)
      }
    }

    "mark an integer value as deleted" in {
      Delete(
        s"/v1/values/${URLEncoder.encode(integerValueIri.get, "UTF-8")}?deleteComment=deleted%20for%20testing"
      ) ~> addCredentials(BasicHttpCredentials(anythingUserEmail, testPass)) ~> valuesPath ~> check {
        assert(status == StatusCodes.OK, response.toString)
      }
    }

    "add a time value to a resource" in {
      val params =
        """
          |{
          |    "res_id": "http://rdfh.ch/0001/a-thing",
          |    "prop": "http://www.knora.org/ontology/0001/anything#hasTimeStamp",
          |    "time_value": "2019-08-28T14:40:17.215927Z"
          |}
                """.stripMargin

      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, params)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, testPass)
      ) ~> valuesPath ~> check {
        assert(status == StatusCodes.OK, response.toString)
        val responseJson: Map[String, JsValue] = responseAs[String].parseJson.asJsObject.fields
        val valueIri: IRI = responseJson("id").asInstanceOf[JsString].value
        timeValueIri.set(valueIri)
      }
    }

    "change a time value" in {
      val params =
        """
          |{
          |    "res_id": "http://rdfh.ch/0001/a-thing",
          |    "prop": "http://www.knora.org/ontology/0001/anything#hasTimeStamp",
          |    "time_value": "2019-08-28T14:45:37.756142Z"
          |}
                """.stripMargin

      Put(
        s"/v1/values/${URLEncoder.encode(timeValueIri.get, "UTF-8")}",
        HttpEntity(ContentTypes.`application/json`, params)
      ) ~> addCredentials(BasicHttpCredentials(anythingUserEmail, testPass)) ~> valuesPath ~> check {
        assert(status == StatusCodes.OK, response.toString)
        val responseJson: Map[String, JsValue] = responseAs[String].parseJson.asJsObject.fields
        val valueIri: IRI = responseJson("id").asInstanceOf[JsString].value
        timeValueIri.set(valueIri)
      }
    }

    "get a link value" in {
      Get(
        s"/v1/links/${URLEncoder.encode("http://rdfh.ch/0001/contained-thing-1", "UTF-8")}/${URLEncoder
          .encode("http://www.knora.org/ontology/0001/anything#isPartOfOtherThing", "UTF-8")}/${URLEncoder.encode("http://rdfh.ch/0001/containing-thing", "UTF-8")}"
      ) ~> addCredentials(BasicHttpCredentials(anythingUserEmail, testPass)) ~> valuesPath ~> check {
        assert(status == StatusCodes.OK, response.toString)

        val linkValue = AkkaHttpUtils.httpResponseToJson(response).fields("value").asJsObject.fields

        assert(
          linkValue("subjectIri").asInstanceOf[JsString].value == "http://rdfh.ch/0001/contained-thing-1" &&
            linkValue("predicateIri")
              .asInstanceOf[JsString]
              .value == "http://www.knora.org/ontology/0001/anything#isPartOfOtherThing" &&
            linkValue("objectIri").asInstanceOf[JsString].value == "http://rdfh.ch/0001/containing-thing" &&
            linkValue("referenceCount").asInstanceOf[JsNumber].value.toInt == 1
        )
      }
    }

    "not add an empty text value to a resource" in {
      val params =
        """
          |{
          |    "res_id": "http://rdfh.ch/0001/a-thing",
          |    "prop": "http://www.knora.org/ontology/0001/anything#hasText",
          |    "richtext_value": {"utf8str":""}
          |}
                """.stripMargin

      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, params)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, testPass)
      ) ~> valuesPath ~> check {
        assert(status == StatusCodes.BadRequest, response.toString)
      }
    }

    "add a text value containing a standoff reference to another resource" in {
      val xmlStr =
        """<?xml version="1.0" encoding="UTF-8"?>
          |<text>
          |   This text links to another <a class="salsah-link" href="http://rdfh.ch/0001/another-thing">resource</a>.
          |</text>
                """.stripMargin

      val params =
        s"""
           |{
           |    "res_id": "http://rdfh.ch/0001/a-thing",
           |    "prop": "http://www.knora.org/ontology/0001/anything#hasText",
           |    "richtext_value": {"xml": ${xmlStr.toJson.compactPrint}, "mapping_id": "$mappingIri"}
           |}
                """.stripMargin

      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, params)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, testPass)
      ) ~> valuesPath ~> check {
        assert(status == StatusCodes.OK, response.toString)
        val responseJson: Map[String, JsValue] = responseAs[String].parseJson.asJsObject.fields

        // check for standoff link in value creation response
        assert(
          responseJson("value")
            .asInstanceOf[JsObject]
            .fields("xml")
            .toString
            .contains("http://rdfh.ch/0001/another-thing"),
          "standoff link target is not contained in value creation response"
        )

        val valueIri: IRI = responseJson("id").asInstanceOf[JsString].value
        textValueIri.set(valueIri)
      }
    }

    "change a text value containing a standoff reference to another resource" in {
      val xmlStr =
        """<?xml version="1.0" encoding="UTF-8"?>
          |<text>
          |   This new version of the text links to another <a class="salsah-link" href="http://rdfh.ch/0001/a-thing-with-text-values">resource</a>.
          |</text>
                """.stripMargin

      val params =
        s"""
           |{
           |    "res_id": "http://rdfh.ch/0001/a-thing",
           |    "prop": "http://www.knora.org/ontology/0001/anything#hasText",
           |    "richtext_value": {"xml": ${xmlStr.toJson.compactPrint}, "mapping_id": "$mappingIri"}
           |}
                """.stripMargin

      Put(
        s"/v1/values/${URLEncoder.encode(textValueIri.get, "UTF-8")}",
        HttpEntity(ContentTypes.`application/json`, params)
      ) ~> addCredentials(BasicHttpCredentials(anythingUserEmail, testPass)) ~> valuesPath ~> check {
        assert(status == StatusCodes.OK, response.toString)
        val responseJson: Map[String, JsValue] = responseAs[String].parseJson.asJsObject.fields

        // check for standoff link in value creation response
        assert(
          responseJson("value")
            .asInstanceOf[JsObject]
            .fields("xml")
            .toString
            .contains("http://rdfh.ch/0001/a-thing-with-text-values"),
          "standoff link target is not contained in value creation response"
        )

        val valueIri: IRI = responseJson("id").asInstanceOf[JsString].value
        textValueIri.set(valueIri)
      }
    }

    "get the version history of a value" in {
      Get(
        s"/v1/values/history/${URLEncoder.encode("http://rdfh.ch/0001/a-thing", "UTF-8")}/${URLEncoder
          .encode("http://www.knora.org/ontology/0001/anything#hasText", "UTF-8")}/${URLEncoder.encode(textValueIri.get, "UTF-8")}"
      ) ~> addCredentials(BasicHttpCredentials(anythingUserEmail, testPass)) ~> valuesPath ~> check {
        assert(status == StatusCodes.OK, response.toString)

        val versionHistory: JsValue = AkkaHttpUtils.httpResponseToJson(response).fields("valueVersions")

        val (mostRecentVersion, originalVersion) = versionHistory match {
          case JsArray(Vector(mostRecent, original)) => (mostRecent.asJsObject.fields, original.asJsObject.fields)
        }

        assert(
          mostRecentVersion("previousValue").asInstanceOf[JsString].value == originalVersion("valueObjectIri")
            .asInstanceOf[JsString]
            .value
        )
        assert(originalVersion("previousValue") == JsNull)
      }
    }

    "mark as deleted a text value containing a standoff reference to another resource" in {
      Delete(
        s"/v1/values/${URLEncoder.encode(textValueIri.get, "UTF-8")}?deleteComment=deleted%20for%20testing"
      ) ~> addCredentials(BasicHttpCredentials(anythingUserEmail, testPass)) ~> valuesPath ~> check {
        assert(status == StatusCodes.OK, response.toString)
      }
    }

    "add a link value to a resource" in {
      val params =
        """
          |{
          |    "res_id": "http://rdfh.ch/0001/a-thing",
          |    "prop": "http://www.knora.org/ontology/0001/anything#hasOtherThing",
          |    "link_value": "http://rdfh.ch/0001/another-thing"
          |}
                """.stripMargin

      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, params)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, testPass)
      ) ~> valuesPath ~> check {
        assert(status == StatusCodes.OK, response.toString)
        val responseJson: Map[String, JsValue] = responseAs[String].parseJson.asJsObject.fields
        val valueIri: IRI = responseJson("id").asInstanceOf[JsString].value
        linkValueIri.set(valueIri)
      }
    }

    "mark a link value as deleted" in {
      Delete(
        s"/v1/values/${URLEncoder.encode(linkValueIri.get, "UTF-8")}?deleteComment=deleted%20for%20testing"
      ) ~> addCredentials(BasicHttpCredentials(anythingUserEmail, testPass)) ~> valuesPath ~> check {
        assert(status == StatusCodes.OK, response.toString)
      }
    }

    "add a link value with a comment to a resource" in {
      val params =
        s"""
           |{
           |    "res_id": "http://rdfh.ch/0001/a-thing",
           |    "prop": "http://www.knora.org/ontology/0001/anything#hasOtherThing",
           |    "link_value": "http://rdfh.ch/0001/another-thing",
           |    "comment":"$boringComment"
           |}
                """.stripMargin

      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, params)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, testPass)
      ) ~> valuesPath ~> check {
        assert(status == StatusCodes.OK, response.toString)
        val responseJson: Map[String, JsValue] = responseAs[String].parseJson.asJsObject.fields
        val valueIri: IRI = responseJson("id").asInstanceOf[JsString].value
        linkValueIri.set(valueIri)
      }
    }

    "get a link value with a comment" in {
      Get(
        s"/v1/links/${URLEncoder.encode("http://rdfh.ch/0001/a-thing", "UTF-8")}/${URLEncoder
          .encode("http://www.knora.org/ontology/0001/anything#hasOtherThing", "UTF-8")}/${URLEncoder.encode("http://rdfh.ch/0001/another-thing", "UTF-8")}"
      ) ~> addCredentials(BasicHttpCredentials(anythingUserEmail, testPass)) ~> valuesPath ~> check {
        assert(status == StatusCodes.OK, response.toString)

        val responseObj = AkkaHttpUtils.httpResponseToJson(response).fields
        val comment = responseObj("comment").asInstanceOf[JsString].value
        val linkValue = responseObj("value").asJsObject.fields

        assert(
          linkValue("subjectIri").asInstanceOf[JsString].value == "http://rdfh.ch/0001/a-thing" &&
            linkValue("predicateIri")
              .asInstanceOf[JsString]
              .value == "http://www.knora.org/ontology/0001/anything#hasOtherThing" &&
            linkValue("objectIri").asInstanceOf[JsString].value == "http://rdfh.ch/0001/another-thing" &&
            linkValue("referenceCount").asInstanceOf[JsNumber].value.toInt == 1 &&
            comment == boringComment
        )
      }
    }
    "add a text value with language to a resource" in {
      val params =
        """
          |{
          |    "res_id": "http://rdfh.ch/0001/a-thing-with-text-valuesLanguage",
          |    "prop": "http://www.knora.org/ontology/0001/anything#hasText",
          |    "richtext_value": {"utf8str":"Guten Tag", "language": "de"}
          |}
                """.stripMargin

      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, params)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, testPass)
      ) ~> valuesPath ~> check {
        assert(status == StatusCodes.OK, response.toString)
        val responseJson: Map[String, JsValue] = responseAs[String].parseJson.asJsObject.fields
        assert(responseJson("value").asInstanceOf[JsObject].fields("utf8str").toString.contains("Guten Tag"))
        assert(responseJson("value").asInstanceOf[JsObject].fields("language").toString.contains("de"))
        val valueIri: IRI = responseJson("id").asInstanceOf[JsString].value
        textValueWithLangIri.set(valueIri)
      }
    }

    "change the previous text value with German language to Persian" in {

      val params =
        s"""
           |{
           |    "res_id": "http://rdfh.ch/0001/a-thing-with-text-valuesLanguage",
           |    "prop": "http://www.knora.org/ontology/0001/anything#hasText",
           |    "richtext_value": {"utf8str": "Salam", "language": "fa"}
           |}
                """.stripMargin

      Put(
        s"/v1/values/${URLEncoder.encode(textValueWithLangIri.get, "UTF-8")}",
        HttpEntity(ContentTypes.`application/json`, params)
      ) ~> addCredentials(BasicHttpCredentials(anythingUserEmail, testPass)) ~> valuesPath ~> check {
        assert(status == StatusCodes.OK, response.toString)
        val responseJson: Map[String, JsValue] = responseAs[String].parseJson.asJsObject.fields
        assert(responseJson("value").asInstanceOf[JsObject].fields("utf8str").toString.contains("Salam"))
        assert(responseJson("value").asInstanceOf[JsObject].fields("language").toString.contains("fa"))

        val valueIri: IRI = responseJson("id").asInstanceOf[JsString].value

        textValueWithLangIri.set(valueIri)
      }
    }
  }
}
