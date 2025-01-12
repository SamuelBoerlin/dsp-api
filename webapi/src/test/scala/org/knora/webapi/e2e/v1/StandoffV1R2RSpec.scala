/*
 * Copyright © 2021 Data and Service Center for the Humanities and/or DaSCH Service Platform contributors.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.knora.webapi.e2e.v1

import java.net.URLEncoder
import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.model.{HttpEntity, _}
import akka.http.scaladsl.testkit.RouteTestTimeout
import org.knora.webapi._
import org.knora.webapi.exceptions.InvalidApiJsonException
import org.knora.webapi.http.directives.DSPApiDirectives
import org.knora.webapi.messages.OntologyConstants
import org.knora.webapi.messages.store.triplestoremessages._
import org.knora.webapi.routing.v1.{StandoffRouteV1, ValuesRouteV1}
import org.knora.webapi.sharedtestdata.SharedTestDataV1._
import org.knora.webapi.sharedtestdata.{SharedTestDataADM, SharedTestDataV1}
import org.knora.webapi.util.{AkkaHttpUtils, FileUtil, MutableTestIri}
import org.xmlunit.builder.{DiffBuilder, Input}
import org.xmlunit.diff.Diff
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

/**
 * End-to-end test specification for the standoff endpoint. This specification uses the Spray Testkit as documented
 * here: http://spray.io/documentation/1.2.2/spray-testkit/
 */
class StandoffV1R2RSpec extends R2RSpec {

  override def testConfigSource: String =
    """
         # akka.loglevel = "DEBUG"
         # akka.stdout-loglevel = "DEBUG"
        """.stripMargin

  private val standoffPath = DSPApiDirectives.handleErrors(system)(new StandoffRouteV1(routeData).knoraApiPath)
  private val valuesPath = DSPApiDirectives.handleErrors(system)(new ValuesRouteV1(routeData).knoraApiPath)

  private val anythingUser = SharedTestDataV1.anythingUser1
  private val anythingUserEmail = anythingUser.userData.email.get

  private val password = SharedTestDataADM.testPass

  implicit def default(implicit system: ActorSystem): RouteTestTimeout = RouteTestTimeout(settings.defaultTimeout)

  implicit val ec: ExecutionContextExecutor = system.dispatcher

  override lazy val rdfDataObjects = List(
    RdfDataObject(path = "test_data/all_data/anything-data.ttl", name = "http://www.knora.org/data/0001/anything"),
    RdfDataObject(path = "test_data/demo_data/images-demo-data.ttl", name = "http://www.knora.org/data/00FF/images"),
    RdfDataObject(path = "test_data/all_data/beol-data.ttl", name = "http://www.knora.org/data/0801/beol"),
    RdfDataObject(path = "test_data/all_data/incunabula-data.ttl", name = "http://www.knora.org/data/0803/incunabula")
  )

  private val firstTextValueIri = new MutableTestIri
  private val secondTextValueIri = new MutableTestIri
  private val thirdTextValueIri = new MutableTestIri
  private val fourthTextValueIri = new MutableTestIri

  object ResponseUtils {

    def getStringMemberFromResponse(response: HttpResponse, memberName: String): IRI = {

      // get the specified string member of the response
      val resIdFuture: Future[String] = response.entity.toStrict(5.seconds).map { responseBody =>
        val resBodyAsString = responseBody.data.decodeString("UTF-8")
        resBodyAsString.parseJson.asJsObject.fields.get(memberName) match {
          case Some(JsString(entitiyId)) => entitiyId
          case None                      => throw InvalidApiJsonException(s"The response does not contain a field called '$memberName'")
          case other =>
            throw InvalidApiJsonException(
              s"The response does not contain a field '$memberName' of type JsString, but $other"
            )
        }
      }

      // wait for the Future to complete
      Await.result(resIdFuture, 5.seconds)

    }

  }

  object RequestParams {

    val paramsCreateLetterMappingFromXML: String =
      s"""
         |{
         |  "project_id": "$ANYTHING_PROJECT_IRI",
         |  "label": "mapping for letters",
         |  "mappingName": "LetterMapping"
         |}
             """.stripMargin

    val pathToLetterMapping = "test_data/test_route/texts/mappingForLetter.xml"

    val pathToLetterXML = "test_data/test_route/texts/letter.xml"

    val pathToLetter2XML = "test_data/test_route/texts/letter2.xml"

    val pathToLetter3XML = "test_data/test_route/texts/letter3.xml"

    val paramsCreateHTMLMappingFromXML: String =
      s"""
         |{
         |  "project_id": "$ANYTHING_PROJECT_IRI",
         |  "label": "mapping for HTML",
         |  "mappingName": "HTMLMapping"
         |}
             """.stripMargin

    // Standard HTML is the html code that can be translated into Standoff markup with the OntologyConstants.KnoraBase.StandardMapping
    val pathToStandardHTML = "test_data/test_route/texts/StandardHTML.xml"

    val pathToStandardHTMLInternalLink = "test_data/test_route/texts/StandardHTML-internal-link.xml"

    val pathToHTMLMapping = "test_data/test_route/texts/mappingForHTML.xml"

    val pathToHTML = "test_data/test_route/texts/HTML.xml"

  }

  "The Standoff Endpoint" should {

    "attempt to create a mapping with an invalid standoff class IRI http://www.knora.org/ontology/standoff#StandoffRot" in {

      val brokenMapping: String =
        """<?xml version="1.0" encoding="UTF-8"?>
          |<mapping>
          |    <mappingElement>
          |        <tag>
          |            <name>text</name>
          |            <class>noClass</class>
          |            <namespace>noNamespace</namespace>
          |            <separatesWords>false</separatesWords>
          |        </tag>
          |        <standoffClass>
          |            <classIri>http://www.knora.org/ontology/standoff#StandoffRot</classIri>
          |            <attributes>
          |                <attribute>
          |                    <attributeName>documentType</attributeName>
          |                    <namespace>noNamespace</namespace>
          |                    <propertyIri>http://www.knora.org/ontology/standoff#standoffRootTagHasDocumentType</propertyIri>
          |                </attribute>
          |            </attributes>
          |        </standoffClass>
          |    </mappingElement>
          |
          |    <mappingElement>
          |        <tag>
          |            <name>p</name>
          |            <class>noClass</class>
          |            <namespace>noNamespace</namespace>
          |            <separatesWords>true</separatesWords>
          |        </tag>
          |        <standoffClass>
          |            <classIri>http://www.knora.org/ontology/standoff#StandoffParagraphTag</classIri>
          |        </standoffClass>
          |    </mappingElement>
          |</mapping>
          |    """.stripMargin

      val formDataMapping = Multipart.FormData(
        Multipart.FormData.BodyPart(
          "json",
          HttpEntity(ContentTypes.`application/json`, RequestParams.paramsCreateLetterMappingFromXML)
        ),
        Multipart.FormData.BodyPart(
          "xml",
          HttpEntity(ContentTypes.`text/xml(UTF-8)`, brokenMapping),
          Map("filename" -> "brokenMapping.xml")
        )
      )

      // send mapping xml to route
      Post("/v1/mapping", formDataMapping) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> standoffPath ~> check {

        assert(status == StatusCodes.BadRequest, response.toString)

        //println(responseAs[String])

        // make sure the user gets informed about the non existing standoff class
        assert(responseAs[String].contains("http://www.knora.org/ontology/standoff#StandoffRot"))

      }
    }

    "attempt to create a mapping with an invalid property class IRI http://www.knora.org/ontology/standoff#standoffRootTagHasDoc" in {

      val brokenMapping =
        """<?xml version="1.0" encoding="UTF-8"?>
          |<mapping>
          |    <mappingElement>
          |        <tag>
          |            <name>text</name>
          |            <class>noClass</class>
          |            <namespace>noNamespace</namespace>
          |            <separatesWords>false</separatesWords>
          |        </tag>
          |        <standoffClass>
          |            <classIri>http://www.knora.org/ontology/standoff#StandoffRootTag</classIri>
          |            <attributes>
          |                <attribute>
          |                    <attributeName>documentType</attributeName>
          |                    <namespace>noNamespace</namespace>
          |                    <propertyIri>http://www.knora.org/ontology/standoff#standoffRootTagHasDoc</propertyIri>
          |                </attribute>
          |            </attributes>
          |        </standoffClass>
          |    </mappingElement>
          |
          |    <mappingElement>
          |        <tag>
          |            <name>p</name>
          |            <class>noClass</class>
          |            <namespace>noNamespace</namespace>
          |            <separatesWords>true</separatesWords>
          |        </tag>
          |        <standoffClass>
          |            <classIri>http://www.knora.org/ontology/standoff#StandoffParagraphTag</classIri>
          |        </standoffClass>
          |    </mappingElement>
          |</mapping>
          |    """.stripMargin

      val formDataMapping = Multipart.FormData(
        Multipart.FormData.BodyPart(
          "json",
          HttpEntity(ContentTypes.`application/json`, RequestParams.paramsCreateLetterMappingFromXML)
        ),
        Multipart.FormData.BodyPart(
          "xml",
          HttpEntity(ContentTypes.`text/xml(UTF-8)`, brokenMapping),
          Map("filename" -> "brokenMapping.xml")
        )
      )

      // send mapping xml to route
      Post("/v1/mapping", formDataMapping) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> standoffPath ~> check {

        assert(status == StatusCodes.BadRequest, response.toString)

        //println(responseAs[String])

        // make sure the user gets informed about the non existing standoff property
        assert(responseAs[String].contains("http://www.knora.org/ontology/standoff#standoffRootTagHasDoc"))

      }
    }

    "attempt to create a mapping assigning a property class IRI to a standoff class that does not have a cardinality for that property" in {

      val brokenMapping =
        """<?xml version="1.0" encoding="UTF-8"?>
          |<mapping>
          |    <mappingElement>
          |        <tag>
          |            <name>text</name>
          |            <class>noClass</class>
          |            <namespace>noNamespace</namespace>
          |            <separatesWords>false</separatesWords>
          |        </tag>
          |        <standoffClass>
          |            <classIri>http://www.knora.org/ontology/standoff#StandoffRootTag</classIri>
          |            <attributes>
          |                <attribute>
          |                    <attributeName>documentType</attributeName>
          |                    <namespace>noNamespace</namespace>
          |                    <propertyIri>http://www.knora.org/ontology/standoff#standoffRootTagHasDocumentType</propertyIri>
          |                </attribute>
          |            </attributes>
          |        </standoffClass>
          |    </mappingElement>
          |
          |    <mappingElement>
          |        <tag>
          |            <name>p</name>
          |            <class>noClass</class>
          |            <namespace>noNamespace</namespace>
          |            <separatesWords>true</separatesWords>
          |        </tag>
          |        <standoffClass>
          |            <classIri>http://www.knora.org/ontology/standoff#StandoffParagraphTag</classIri>
          |            <attributes>
          |                <attribute>
          |                    <attributeName>documentType</attributeName>
          |                    <namespace>noNamespace</namespace>
          |                    <propertyIri>http://www.knora.org/ontology/standoff#standoffRootTagHasDocumentType</propertyIri>
          |                </attribute>
          |            </attributes>
          |        </standoffClass>
          |    </mappingElement>
          |</mapping>
          |    """.stripMargin

      val formDataMapping = Multipart.FormData(
        Multipart.FormData.BodyPart(
          "json",
          HttpEntity(ContentTypes.`application/json`, RequestParams.paramsCreateLetterMappingFromXML)
        ),
        Multipart.FormData.BodyPart(
          "xml",
          HttpEntity(ContentTypes.`text/xml(UTF-8)`, brokenMapping),
          Map("filename" -> "brokenMapping.xml")
        )
      )

      // send mapping xml to route
      Post("/v1/mapping", formDataMapping) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> standoffPath ~> check {

        assert(status == StatusCodes.BadRequest, response.toString)

        //println(responseAs[String])

        // make sure the user gets informed about the missing cardinality for StandoffParagraphTag
        assert(responseAs[String].contains("http://www.knora.org/ontology/standoff#StandoffParagraphTag"))
        assert(responseAs[String].contains("http://www.knora.org/ontology/standoff#standoffRootTagHasDocumentType"))

      }
    }

    "attempt to create a mapping not assigning a required property class IRI to a standoff class" in {

      val brokenMapping =
        """<?xml version="1.0" encoding="UTF-8"?>
          |<mapping>
          |    <mappingElement>
          |        <tag>
          |            <name>text</name>
          |            <class>noClass</class>
          |            <namespace>noNamespace</namespace>
          |            <separatesWords>false</separatesWords>
          |        </tag>
          |        <standoffClass>
          |            <classIri>http://www.knora.org/ontology/standoff#StandoffRootTag</classIri>
          |            <attributes>
          |                <attribute>
          |                    <attributeName>documentType</attributeName>
          |                    <namespace>noNamespace</namespace>
          |                    <propertyIri>http://www.knora.org/ontology/standoff#standoffRootTagHasDocumentType</propertyIri>
          |                </attribute>
          |            </attributes>
          |        </standoffClass>
          |    </mappingElement>
          |
          |    <mappingElement>
          |        <tag>
          |            <name>event</name>
          |            <class>noClass</class>
          |            <namespace>noNamespace</namespace>
          |            <separatesWords>true</separatesWords>
          |        </tag>
          |        <standoffClass>
          |            <classIri>http://www.knora.org/ontology/0001/anything#StandoffEventTag</classIri>
          |            <!-- attribute definition for http://www.knora.org/ontology/0001/anything#standoffEventTagHasDescription missing-->
          |            <datatype>
          |                <type>http://www.knora.org/ontology/knora-base#StandoffDateTag</type>
          |                <attributeName>src</attributeName>
          |            </datatype>
          |        </standoffClass>
          |    </mappingElement>
          |</mapping>
          |    """.stripMargin

      val formDataMapping = Multipart.FormData(
        Multipart.FormData.BodyPart(
          "json",
          HttpEntity(ContentTypes.`application/json`, RequestParams.paramsCreateLetterMappingFromXML)
        ),
        Multipart.FormData.BodyPart(
          "xml",
          HttpEntity(ContentTypes.`text/xml(UTF-8)`, brokenMapping),
          Map("filename" -> "brokenMapping.xml")
        )
      )

      // send mapping xml to route
      Post("/v1/mapping", formDataMapping) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> standoffPath ~> check {

        assert(status == StatusCodes.BadRequest, response.toString)

        //println(responseAs[String])

        // make sure the user gets informed about the missing required property anything:standoffEventTagHasDescription
        assert(responseAs[String].contains("http://www.knora.org/ontology/0001/anything#StandoffEventTag"))
        assert(
          responseAs[String].contains("http://www.knora.org/ontology/0001/anything#standoffEventTagHasDescription")
        )

      }
    }

    "attempt to create a mapping not assigning a data type to a standoff class that has a data type" in {

      val brokenMapping =
        """<?xml version="1.0" encoding="UTF-8"?>
          |<mapping>
          |    <mappingElement>
          |        <tag>
          |            <name>text</name>
          |            <class>noClass</class>
          |            <namespace>noNamespace</namespace>
          |            <separatesWords>false</separatesWords>
          |        </tag>
          |        <standoffClass>
          |            <classIri>http://www.knora.org/ontology/standoff#StandoffRootTag</classIri>
          |            <attributes>
          |                <attribute>
          |                    <attributeName>documentType</attributeName>
          |                    <namespace>noNamespace</namespace>
          |                    <propertyIri>http://www.knora.org/ontology/standoff#standoffRootTagHasDocumentType</propertyIri>
          |                </attribute>
          |            </attributes>
          |        </standoffClass>
          |    </mappingElement>
          |
          |    <mappingElement>
          |        <tag>
          |            <name>event</name>
          |            <class>noClass</class>
          |            <namespace>noNamespace</namespace>
          |            <separatesWords>true</separatesWords>
          |        </tag>
          |        <standoffClass>
          |            <classIri>http://www.knora.org/ontology/0001/anything#StandoffEventTag</classIri>
          |             <attributes>
          |                <attribute>
          |                    <attributeName>desc</attributeName>
          |                    <namespace>noNamespace</namespace>
          |                    <propertyIri>http://www.knora.org/ontology/0001/anything#standoffEventTagHasDescription</propertyIri>
          |                </attribute>
          |            </attributes>
          |            <!-- no data type provided, but required -->
          |        </standoffClass>
          |    </mappingElement>
          |</mapping>
          |    """.stripMargin

      val formDataMapping = Multipart.FormData(
        Multipart.FormData.BodyPart(
          "json",
          HttpEntity(ContentTypes.`application/json`, RequestParams.paramsCreateLetterMappingFromXML)
        ),
        Multipart.FormData.BodyPart(
          "xml",
          HttpEntity(ContentTypes.`text/xml(UTF-8)`, brokenMapping),
          Map("filename" -> "brokenMapping.xml")
        )
      )

      // send mapping xml to route
      Post("/v1/mapping", formDataMapping) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> standoffPath ~> check {

        assert(status == StatusCodes.BadRequest, response.toString)

        //println(responseAs[String])

        // make sure the user gets informed about the missing data type http://www.knora.org/ontology/knora-base#StandoffDateTag
        assert(responseAs[String].contains("http://www.knora.org/ontology/0001/anything#StandoffEventTag"))
        assert(responseAs[String].contains("http://www.knora.org/ontology/knora-base#StandoffDateTag"))

      }
    }

    "attempt to create a mapping assigning a wrong data type to a standoff class that has a data type" in {

      val brokenMapping =
        """<?xml version="1.0" encoding="UTF-8"?>
          |<mapping>
          |    <mappingElement>
          |        <tag>
          |            <name>text</name>
          |            <class>noClass</class>
          |            <namespace>noNamespace</namespace>
          |            <separatesWords>false</separatesWords>
          |        </tag>
          |        <standoffClass>
          |            <classIri>http://www.knora.org/ontology/standoff#StandoffRootTag</classIri>
          |            <attributes>
          |                <attribute>
          |                    <attributeName>documentType</attributeName>
          |                    <namespace>noNamespace</namespace>
          |                    <propertyIri>http://www.knora.org/ontology/standoff#standoffRootTagHasDocumentType</propertyIri>
          |                </attribute>
          |            </attributes>
          |        </standoffClass>
          |    </mappingElement>
          |
          |    <mappingElement>
          |        <tag>
          |            <name>event</name>
          |            <class>noClass</class>
          |            <namespace>noNamespace</namespace>
          |            <separatesWords>true</separatesWords>
          |        </tag>
          |        <standoffClass>
          |            <classIri>http://www.knora.org/ontology/0001/anything#StandoffEventTag</classIri>
          |             <attributes>
          |                <attribute>
          |                    <attributeName>desc</attributeName>
          |                    <namespace>noNamespace</namespace>
          |                    <propertyIri>http://www.knora.org/ontology/0001/anything#standoffEventTagHasDescription</propertyIri>
          |                </attribute>
          |            </attributes>
          |            <datatype>
          |                <type>http://www.knora.org/ontology/knora-base#StandoffUriTag</type>
          |                <attributeName>src</attributeName>
          |            </datatype>
          |        </standoffClass>
          |    </mappingElement>
          |</mapping>
          |    """.stripMargin

      val formDataMapping = Multipart.FormData(
        Multipart.FormData.BodyPart(
          "json",
          HttpEntity(ContentTypes.`application/json`, RequestParams.paramsCreateLetterMappingFromXML)
        ),
        Multipart.FormData.BodyPart(
          "xml",
          HttpEntity(ContentTypes.`text/xml(UTF-8)`, brokenMapping),
          Map("filename" -> "brokenMapping.xml")
        )
      )

      // send mapping xml to route
      Post("/v1/mapping", formDataMapping) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> standoffPath ~> check {

        assert(status == StatusCodes.BadRequest, response.toString)

        //println(responseAs[String])

        // make sure the user gets informed about the wrong data type http://www.knora.org/ontology/knora-base#StandoffUriTag
        assert(responseAs[String].contains("http://www.knora.org/ontology/knora-base#StandoffUriTag"))
        assert(responseAs[String].contains("http://www.knora.org/ontology/knora-base#StandoffDateTag"))

      }
    }

    "attempt to create a mapping assigning a data type to a standoff class that does not have a data type" in {

      val brokenMapping =
        """<?xml version="1.0" encoding="UTF-8"?>
          |<mapping>
          |    <mappingElement>
          |        <tag>
          |            <name>text</name>
          |            <class>noClass</class>
          |            <namespace>noNamespace</namespace>
          |            <separatesWords>false</separatesWords>
          |        </tag>
          |        <standoffClass>
          |            <classIri>http://www.knora.org/ontology/standoff#StandoffRootTag</classIri>
          |            <attributes>
          |                <attribute>
          |                    <attributeName>documentType</attributeName>
          |                    <namespace>noNamespace</namespace>
          |                    <propertyIri>http://www.knora.org/ontology/standoff#standoffRootTagHasDocumentType</propertyIri>
          |                </attribute>
          |            </attributes>
          |            <datatype>
          |                <type>http://www.knora.org/ontology/knora-base#StandoffDateTag</type>
          |                <attributeName>src</attributeName>
          |            </datatype>
          |        </standoffClass>
          |    </mappingElement>
          |
          |    <mappingElement>
          |        <tag>
          |            <name>event</name>
          |            <class>noClass</class>
          |            <namespace>noNamespace</namespace>
          |            <separatesWords>true</separatesWords>
          |        </tag>
          |        <standoffClass>
          |            <classIri>http://www.knora.org/ontology/0001/anything#StandoffEventTag</classIri>
          |             <attributes>
          |                <attribute>
          |                    <attributeName>desc</attributeName>
          |                    <namespace>noNamespace</namespace>
          |                    <propertyIri>http://www.knora.org/ontology/0001/anything#standoffEventTagHasDescription</propertyIri>
          |                </attribute>
          |            </attributes>
          |            <datatype>
          |                <type>http://www.knora.org/ontology/knora-base#StandoffDateTag</type>
          |                <attributeName>src</attributeName>
          |            </datatype>
          |        </standoffClass>
          |    </mappingElement>
          |</mapping>
          |    """.stripMargin

      val formDataMapping = Multipart.FormData(
        Multipart.FormData.BodyPart(
          "json",
          HttpEntity(ContentTypes.`application/json`, RequestParams.paramsCreateLetterMappingFromXML)
        ),
        Multipart.FormData.BodyPart(
          "xml",
          HttpEntity(ContentTypes.`text/xml(UTF-8)`, brokenMapping),
          Map("filename" -> "brokenMapping.xml")
        )
      )

      // send mapping xml to route
      Post("/v1/mapping", formDataMapping) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> standoffPath ~> check {

        assert(status == StatusCodes.BadRequest, response.toString)

        //println(responseAs[String])

        // make sure the user gets informed about the data type for http://www.knora.org/ontology/standoff#StandoffRootTag that has no data type
        assert(responseAs[String].contains("http://www.knora.org/ontology/standoff#StandoffRootTag"))
        assert(responseAs[String].contains("http://www.knora.org/ontology/knora-base#StandoffDateTag"))

      }
    }

    "create a mapping resource for standoff conversion for letters" in {

      val mappingFileToSend = Paths.get(RequestParams.pathToLetterMapping)

      val formDataMapping = Multipart.FormData(
        Multipart.FormData.BodyPart(
          "json",
          HttpEntity(ContentTypes.`application/json`, RequestParams.paramsCreateLetterMappingFromXML)
        ),
        Multipart.FormData.BodyPart(
          "xml",
          HttpEntity.fromPath(ContentTypes.`text/xml(UTF-8)`, mappingFileToSend),
          Map("filename" -> mappingFileToSend.getFileName.toString)
        )
      )

      // send mapping xml to route
      Post("/v1/mapping", formDataMapping) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> standoffPath ~> check {

        assert(
          status == StatusCodes.OK,
          "standoff mapping creation route returned a non successful HTTP status code: " + responseAs[String]
        )

        // check if mappingIri is correct
        val mappingIri = ResponseUtils.getStringMemberFromResponse(response, "mappingIri")

        assert(mappingIri == ANYTHING_PROJECT_IRI + "/mappings/LetterMapping", "Iri of the new mapping is not correct")

      }

    }

    "create a TextValue from an XML representing a letter" in {

      val xmlFileToSend = FileUtil.readTextFile(Paths.get(RequestParams.pathToLetterXML))

      val newValueParams =
        s"""
                        {
                          "project_id": "http://rdfh.ch/projects/0001",
                          "res_id": "http://rdfh.ch/0001/a-thing",
                          "prop": "http://www.knora.org/ontology/0001/anything#hasText",
                          "richtext_value": {
                                "xml": ${JsString(xmlFileToSend)},
                                "mapping_id": "$ANYTHING_PROJECT_IRI/mappings/LetterMapping"
                          }
                        }
                        """

      // create standoff from XML
      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, newValueParams)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> valuesPath ~> check {

        assert(
          status == StatusCodes.OK,
          "creation of a TextValue from XML returned a non successful HTTP status code: " + responseAs[String]
        )

        firstTextValueIri.set(ResponseUtils.getStringMemberFromResponse(response, "id"))

      }

    }

    "read the XML TextValue back to XML and compare it to the XML that was originally sent" in {

      val xmlFile = Paths.get(RequestParams.pathToLetterXML)
      val xmlStr = FileUtil.readTextFile(xmlFile)

      Get("/v1/values/" + URLEncoder.encode(firstTextValueIri.get, "UTF-8")) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> valuesPath ~> check {

        assert(response.status == StatusCodes.OK, "reading back text value to XML failed")

        val xml: String = AkkaHttpUtils.httpResponseToJson(response).fields.get("value") match {
          case Some(value: JsObject) =>
            value.fields.get("xml") match {
              case Some(JsString(xml: String)) => xml
              case _                           => throw new InvalidApiJsonException("member 'xml' not given")
            }
          case _ => throw new InvalidApiJsonException("member 'value' not given")
        }

        // Compare the original XML with the regenerated XML.
        val xmlDiff: Diff = DiffBuilder.compare(Input.fromString(xmlStr)).withTest(Input.fromString(xml)).build()

        xmlDiff.hasDifferences should be(false)

      }

    }

    "change a text value from XML representing a letter" in {

      val xmlFileToSend = Paths.get(RequestParams.pathToLetter2XML)
      val xmlStrToSend = FileUtil.readTextFile(xmlFileToSend)

      val newValueParams =
        s"""
                    {
                      "project_id": "http://rdfh.ch/projects/0001",
                      "richtext_value": {
                            "xml": ${JsString(xmlStrToSend)},
                            "mapping_id": "$ANYTHING_PROJECT_IRI/mappings/LetterMapping"
                      }
                    }
                """

      // change standoff from XML
      Put(
        "/v1/values/" + URLEncoder.encode(firstTextValueIri.get, "UTF-8"),
        HttpEntity(ContentTypes.`application/json`, newValueParams)
      ) ~> addCredentials(BasicHttpCredentials(anythingUserEmail, password)) ~> valuesPath ~> check {

        assert(
          status == StatusCodes.OK,
          "standoff creation route returned a non successful HTTP status code: " + responseAs[String]
        )

        firstTextValueIri.set(ResponseUtils.getStringMemberFromResponse(response, "id"))

      }

    }

    "read the changed TextValue back to XML and compare it to the XML that was originally sent" in {

      val xmlFile = Paths.get(RequestParams.pathToLetter2XML)
      val xmlStr = FileUtil.readTextFile(xmlFile)

      Get("/v1/values/" + URLEncoder.encode(firstTextValueIri.get, "UTF-8")) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> valuesPath ~> check {

        assert(response.status == StatusCodes.OK, "reading back text value to XML failed")

        val xml = AkkaHttpUtils.httpResponseToJson(response).fields.get("value") match {
          case Some(value: JsObject) =>
            value.fields.get("xml") match {
              case Some(JsString(xml: String)) => xml
              case _                           => throw new InvalidApiJsonException("member 'xml' not given")
            }
          case _ => throw new InvalidApiJsonException("member 'value' not given")
        }

        // Compare the original XML with the regenerated XML.
        val xmlDiff: Diff = DiffBuilder.compare(Input.fromString(xmlStr)).withTest(Input.fromString(xml)).build()

        xmlDiff.hasDifferences should be(false)

      }

    }

    "create a TextValue from complex XML representing a letter" in {

      val xmlFileToSend = Paths.get(RequestParams.pathToLetter3XML)
      val xmlStrToSend = FileUtil.readTextFile(xmlFileToSend)

      val newValueParams =
        s"""
                {
                  "project_id": "$ANYTHING_PROJECT_IRI",
                  "res_id": "http://rdfh.ch/0001/a-thing",
                  "prop": "http://www.knora.org/ontology/0001/anything#hasText",
                  "richtext_value": {
                        "xml": ${JsString(xmlStrToSend)},
                        "mapping_id": "$ANYTHING_PROJECT_IRI/mappings/LetterMapping"
                  }
                }
                """

      // create standoff from XML
      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, newValueParams)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> valuesPath ~> check {

        assert(
          status == StatusCodes.OK,
          "creation of a TextValue from XML returned a non successful HTTP status code: " + responseAs[String]
        )

        secondTextValueIri.set(ResponseUtils.getStringMemberFromResponse(response, "id"))

      }

    }

    "read the complex TextValue back to XML and compare it to the XML that was originally sent" in {

      val xmlFile = Paths.get(RequestParams.pathToLetter3XML)
      val xmlStr = FileUtil.readTextFile(xmlFile)

      Get("/v1/values/" + URLEncoder.encode(secondTextValueIri.get, "UTF-8")) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> valuesPath ~> check {

        assert(response.status == StatusCodes.OK, "reading back text value to XML failed")

        val xml = AkkaHttpUtils.httpResponseToJson(response).fields.get("value") match {
          case Some(value: JsObject) =>
            value.fields.get("xml") match {
              case Some(JsString(xml: String)) => xml
              case _                           => throw new InvalidApiJsonException("member 'xml' not given")
            }
          case _ => throw new InvalidApiJsonException("member 'value' not given")
        }

        // Compare the original XML with the regenerated XML.
        val xmlDiff: Diff = DiffBuilder.compare(Input.fromString(xmlStr)).withTest(Input.fromString(xml)).build()

        xmlDiff.hasDifferences should be(false)

      }

    }

    "create a mapping resource for standoff conversion for HTML" in {

      val mappingFileToSend = Paths.get(RequestParams.pathToHTMLMapping)

      val formDataMapping = Multipart.FormData(
        Multipart.FormData.BodyPart(
          "json",
          HttpEntity(ContentTypes.`application/json`, RequestParams.paramsCreateHTMLMappingFromXML)
        ),
        Multipart.FormData.BodyPart(
          "xml",
          HttpEntity.fromPath(ContentTypes.`text/xml(UTF-8)`, mappingFileToSend),
          Map("filename" -> mappingFileToSend.getFileName.toString)
        )
      )

      // send mapping xml to route
      Post("/v1/mapping", formDataMapping) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> standoffPath ~> check {

        assert(
          status == StatusCodes.OK,
          "standoff mapping creation route returned a non successful HTTP status code: " + responseAs[String]
        )

        // check if mappingIri is correct
        val mappingIri = ResponseUtils.getStringMemberFromResponse(response, "mappingIri")

        assert(mappingIri == ANYTHING_PROJECT_IRI + "/mappings/HTMLMapping", "Iri of the new mapping is not correct")

      }

    }

    "create a TextValue from StandardXML representing HTML (in strict XML notation)" in {

      val xmlFileToSend = Paths.get(RequestParams.pathToStandardHTML)
      val xmlStrToSend = FileUtil.readTextFile(xmlFileToSend)

      val newValueParams =
        s"""
                {
                  "project_id": "http://rdfh.ch/projects/0001",
                  "res_id": "http://rdfh.ch/0001/a-thing",
                  "prop": "http://www.knora.org/ontology/0001/anything#hasText",
                  "richtext_value": {
                        "xml": ${JsString(xmlStrToSend)},
                        "mapping_id": "${OntologyConstants.KnoraBase.StandardMapping}"
                  }
                }
                """

      // create standoff from XML
      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, newValueParams)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> valuesPath ~> check {

        assert(
          status == StatusCodes.OK,
          "creation of a TextValue from XML returned a non successful HTTP status code: " + responseAs[String]
        )

        thirdTextValueIri.set(ResponseUtils.getStringMemberFromResponse(response, "id"))

      }

    }

    "read the TextValue back to XML and compare it to the (Standard) HTML that was originally sent" in {

      val htmlFile = Paths.get(RequestParams.pathToStandardHTML)
      val htmlStr = FileUtil.readTextFile(htmlFile)

      Get("/v1/values/" + URLEncoder.encode(thirdTextValueIri.get, "UTF-8")) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> valuesPath ~> check {

        assert(response.status == StatusCodes.OK, "reading back text value to XML failed")

        val xml = AkkaHttpUtils.httpResponseToJson(response).fields.get("value") match {
          case Some(value: JsObject) =>
            value.fields.get("xml") match {
              case Some(JsString(xml: String)) => xml
              case _                           => throw new InvalidApiJsonException("member 'xml' not given")
            }
          case _ => throw new InvalidApiJsonException("member 'value' not given")
        }

        // Compare the original XML with the regenerated XML.
        val xmlDiff: Diff = DiffBuilder.compare(Input.fromString(htmlStr)).withTest(Input.fromString(xml)).build()

        xmlDiff.hasDifferences should be(false)

      }

    }

    "create a TextValue from StandardXML representing HTML with internal anchor link" in {

      val xmlFileToSend = Paths.get(RequestParams.pathToStandardHTMLInternalLink)
      val xmlStrToSend = FileUtil.readTextFile(xmlFileToSend)

      val newValueParams =
        s"""
                {
                  "project_id": "http://rdfh.ch/projects/0001",
                  "res_id": "http://rdfh.ch/0001/a-thing",
                  "prop": "http://www.knora.org/ontology/0001/anything#hasText",
                  "richtext_value": {
                        "xml": ${JsString(xmlStrToSend)},
                        "mapping_id": "${OntologyConstants.KnoraBase.StandardMapping}"
                  }
                }
                """

      // create standoff from XML
      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, newValueParams)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> valuesPath ~> check {

        assert(
          status == StatusCodes.OK,
          "creation of a TextValue from XML returned a non successful HTTP status code: " + responseAs[String]
        )

        fourthTextValueIri.set(ResponseUtils.getStringMemberFromResponse(response, "id"))

      }

    }

    "read the TextValue back to XML and compare it to the (Standard) HTML with internal anchor link that was originally sent" in {

      val htmlFile = Paths.get(RequestParams.pathToStandardHTMLInternalLink)
      val htmlStr = FileUtil.readTextFile(htmlFile)

      Get("/v1/values/" + URLEncoder.encode(fourthTextValueIri.get, "UTF-8")) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> valuesPath ~> check {

        assert(response.status == StatusCodes.OK, "reading back text value to XML failed")

        val xml = AkkaHttpUtils.httpResponseToJson(response).fields.get("value") match {
          case Some(value: JsObject) =>
            value.fields.get("xml") match {
              case Some(JsString(xml: String)) => xml
              case _                           => throw new InvalidApiJsonException("member 'xml' not given")
            }
          case _ => throw new InvalidApiJsonException("member 'value' not given")
        }

        // Compare the original XML with the regenerated XML.
        val xmlDiff: Diff = DiffBuilder.compare(Input.fromString(htmlStr)).withTest(Input.fromString(xml)).build()

        xmlDiff.hasDifferences should be(false)

      }

    }

    "create a TextValue from XML representing HTML (in strict XML notation)" in {

      val xmlFileToSend = Paths.get(RequestParams.pathToHTML)
      val xmlStrToSend = FileUtil.readTextFile(xmlFileToSend)

      val newValueParams =
        s"""
                {
                  "project_id": "http://rdfh.ch/projects/0001",
                  "res_id": "http://rdfh.ch/0001/a-thing",
                  "prop": "http://www.knora.org/ontology/0001/anything#hasText",
                  "richtext_value": {
                        "xml": ${JsString(xmlStrToSend)},
                        "mapping_id": "$ANYTHING_PROJECT_IRI/mappings/HTMLMapping"
                  }
                }
                """

      // create standoff from XML
      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, newValueParams)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> valuesPath ~> check {

        assert(
          status == StatusCodes.OK,
          "creation of a TextValue from XML returned a non successful HTTP status code: " + responseAs[String]
        )

        thirdTextValueIri.set(ResponseUtils.getStringMemberFromResponse(response, "id"))

      }

    }

    "read the TextValue back to XML and compare it to the HTML that was originally sent" in {

      val htmlFile = Paths.get(RequestParams.pathToHTML)
      val htmlStr = FileUtil.readTextFile(htmlFile)

      Get("/v1/values/" + URLEncoder.encode(thirdTextValueIri.get, "UTF-8")) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> valuesPath ~> check {

        assert(response.status == StatusCodes.OK, "reading back text value to XML failed")

        val xml = AkkaHttpUtils.httpResponseToJson(response).fields.get("value") match {
          case Some(value: JsObject) =>
            value.fields.get("xml") match {
              case Some(JsString(xml: String)) => xml
              case _                           => throw new InvalidApiJsonException("member 'xml' not given")
            }
          case _ => throw new InvalidApiJsonException("member 'value' not given")
        }

        // Compare the original XML with the regenerated XML.
        val xmlDiff: Diff = DiffBuilder.compare(Input.fromString(htmlStr)).withTest(Input.fromString(xml)).build()

        xmlDiff.hasDifferences should be(false)

      }

    }

    "attempt to create a TextValue from XML representing HTML (in strict XML notation) not defining a required attribute" in {

      val wrongXML = """<?xml version="1.0" encoding="UTF-8"?>
                <text documentType="html">
                    <p>
                        This an <span data-date="GREGORIAN:2017-01-27" class="event">event</span> without a description.
                    </p>
                </text>""".stripMargin

      val newValueParams =
        s"""
                {
                  "project_id": "http://rdfh.ch/projects/0001",
                  "res_id": "http://rdfh.ch/0001/a-thing",
                  "prop": "http://www.knora.org/ontology/0001/anything#hasText",
                  "richtext_value": {
                        "xml": ${JsString(wrongXML)},
                        "mapping_id": "$ANYTHING_PROJECT_IRI/mappings/HTMLMapping"
                  }
                }
                """

      // create standoff from XML
      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, newValueParams)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> valuesPath ~> check {

        assert(status == StatusCodes.BadRequest, response.toString)

        // the error message should inform the user that the required property http://www.knora.org/ontology/0001/anything#standoffEventTagHasDescription is missing
        assert(
          responseAs[String].contains("http://www.knora.org/ontology/0001/anything#standoffEventTagHasDescription")
        )

      }

    }

    "attempt to create a TextValue from XML representing HTML (in strict XML notation) submitting an attribute that is not defined in the mapping (for an element that has an another attribute)" in {

      val wrongXML =
        """<?xml version="1.0" encoding="UTF-8"?>
                <text documentType="html">
                    <p>
                        This an <span notDefined="true" data-description="an event" data-date="GREGORIAN:2017-01-27" class="event">event</span> without a description.
                    </p>
                </text>""".stripMargin

      val newValueParams =
        s"""
                {
                  "project_id": "http://rdfh.ch/projects/0001",
                  "res_id": "http://rdfh.ch/0001/a-thing",
                  "prop": "http://www.knora.org/ontology/0001/anything#hasText",
                  "richtext_value": {
                        "xml": ${JsString(wrongXML)},
                        "mapping_id": "$ANYTHING_PROJECT_IRI/mappings/HTMLMapping"
                  }
                }
                """

      // create standoff from XML
      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, newValueParams)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> valuesPath ~> check {

        assert(status == StatusCodes.BadRequest, response.toString)

        // the error message should inform the user that mapping does not support the attribute "notDefined"
        assert(responseAs[String].contains("notDefined"))

      }

    }

    "attempt to create a TextValue from XML representing HTML (in strict XML notation) submitting an attribute that is not defined in the mapping (for an element that hasn't an another attribute)" in {

      val wrongXML = """<?xml version="1.0" encoding="UTF-8"?>
                <text documentType="html">
                    <p notDefined="true">
                        This is text.
                    </p>
                </text>""".stripMargin

      val newValueParams =
        s"""
                {
                  "project_id": "http://rdfh.ch/projects/0001",
                  "res_id": "http://rdfh.ch/0001/a-thing",
                  "prop": "http://www.knora.org/ontology/0001/anything#hasText",
                  "richtext_value": {
                        "xml": ${JsString(wrongXML)},
                        "mapping_id": "$ANYTHING_PROJECT_IRI/mappings/HTMLMapping"
                  }
                }
                """

      // create standoff from XML
      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, newValueParams)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> valuesPath ~> check {

        assert(status == StatusCodes.BadRequest, response.toString)

        // the error message should inform the user that mapping does not support the attribute "notDefined"
        assert(responseAs[String].contains("notDefined"))

      }

    }

    "attempt to create a TextValue from XML representing HTML (in strict XML notation) submitting a date element without the data type attribute" in {

      val wrongXML = """<?xml version="1.0" encoding="UTF-8"?>
                <text documentType="html">
                    <p>
                        This an <span data-description="an event" class="event">event</span> without a date attribute.
                    </p>
                </text>""".stripMargin

      val newValueParams =
        s"""
                {
                  "project_id": "http://rdfh.ch/projects/0001",
                  "res_id": "http://rdfh.ch/0001/a-thing",
                  "prop": "http://www.knora.org/ontology/0001/anything#hasText",
                  "richtext_value": {
                        "xml": ${JsString(wrongXML)},
                        "mapping_id": "$ANYTHING_PROJECT_IRI/mappings/HTMLMapping"
                  }
                }
                """

      // create standoff from XML
      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, newValueParams)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> valuesPath ~> check {

        assert(status == StatusCodes.BadRequest, response.toString)

        // the error message should inform the user the attribute "data-date" is missing (data type attribute defined in mapping)
        // for a standoff class of type http://www.knora.org/ontology/knora-base#StandoffDateTag
        assert(responseAs[String].contains("data-date"))
        assert(responseAs[String].contains("http://www.knora.org/ontology/knora-base#StandoffDateTag"))

      }

    }

    "attempt to create a TextValue from XML representing HTML (in strict XML notation) submitting a date element with an invalid date (missing calendar)" in {

      val wrongXML =
        """<?xml version="1.0" encoding="UTF-8"?>
                <text documentType="html">
                    <p>
                        This an <span data-description="an event" data-date="2017" class="event">event</span> without a date attribute.
                    </p>
                </text>""".stripMargin

      val newValueParams =
        s"""
                {
                  "project_id": "http://rdfh.ch/projects/0001",
                  "res_id": "http://rdfh.ch/0001/a-thing",
                  "prop": "http://www.knora.org/ontology/0001/anything#hasText",
                  "richtext_value": {
                        "xml": ${JsString(wrongXML)},
                        "mapping_id": "$ANYTHING_PROJECT_IRI/mappings/HTMLMapping"
                  }
                }
                """

      // create standoff from XML
      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, newValueParams)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> valuesPath ~> check {

        assert(status == StatusCodes.BadRequest, response.toString)

        // the error message should inform the user that the format of the date is invalid
        assert(responseAs[String].contains("2017"))

      }

    }

    "attempt to create a TextValue providing an invalid mapping Iri" in {

      val xml = """<?xml version="1.0" encoding="UTF-8"?>
                <text documentType="html">
                    <p>
                        This an a text with an invalid mapping.
                    </p>
                </text>""".stripMargin

      val newValueParams =
        s"""
                {
                  "project_id": "http://rdfh.ch/projects/0001",
                  "res_id": "http://rdfh.ch/0001/a-thing",
                  "prop": "http://www.knora.org/ontology/0001/anything#hasText",
                  "richtext_value": {
                        "xml": ${JsString(xml)},
                        "mapping_id": "$ANYTHING_PROJECT_IRI/invalidPathForMappings/HTMLMapping"
                  }
                }
                """

      // create standoff from XML
      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, newValueParams)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> valuesPath ~> check {

        assert(status == StatusCodes.BadRequest, response.toString)

        // the error message should inform the user that the provided mapping Iri is invalid
        assert(
          responseAs[String].contains(
            s"mapping $ANYTHING_PROJECT_IRI/invalidPathForMappings/HTMLMapping does not exist"
          )
        )

      }

    }

    "create a mapping containing an element with a class that separates words" in {

      val mapping =
        """<?xml version="1.0" encoding="UTF-8"?>
                <mapping xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="../../../src/main/resources/mappingXMLToStandoff.xsd">
                    <mappingElement>
                        <tag><name>text</name>
                            <class>noClass</class>
                            <namespace>noNamespace</namespace>
                            <separatesWords>false</separatesWords></tag>
                        <standoffClass>
                            <classIri>http://www.knora.org/ontology/standoff#StandoffRootTag</classIri>
                            <attributes>
                                <attribute>
                                    <attributeName>documentType</attributeName>
                                    <namespace>noNamespace</namespace>
                                    <propertyIri>http://www.knora.org/ontology/standoff#standoffRootTagHasDocumentType</propertyIri>
                                </attribute>
                            </attributes>
                        </standoffClass>
                    </mappingElement>

                    <mappingElement>
                        <tag>
                            <name>div</name>
                            <class>paragraph</class>
                            <namespace>noNamespace</namespace>
                            <separatesWords>true</separatesWords>
                        </tag>
                        <standoffClass>
                            <classIri>http://www.knora.org/ontology/standoff#StandoffParagraphTag</classIri>
                        </standoffClass>
                    </mappingElement>
                </mapping>""".stripMargin

      val params =
        s"""
           |{
           |  "project_id": "$ANYTHING_PROJECT_IRI",
           |  "label": "mapping for elements separating words",
           |  "mappingName": "MappingSeparatingWords"
           |}
             """.stripMargin

      val formDataMapping = Multipart.FormData(
        Multipart.FormData.BodyPart(
          "json",
          HttpEntity(ContentTypes.`application/json`, params)
        ),
        Multipart.FormData.BodyPart(
          "xml",
          HttpEntity(ContentTypes.`text/xml(UTF-8)`, mapping),
          Map("filename" -> "mapping.xml")
        )
      )

      // send mapping xml to route
      Post("/v1/mapping", formDataMapping) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> standoffPath ~> check {

        assert(status == StatusCodes.OK)

      }

    }

    "create a TextValue from a XML using an element with a class that separates words" in {

      val xmlToSend =
        """<?xml version="1.0" encoding="UTF-8"?>
                <text documentType="html">
                    <div class="paragraph">
                        This an element that has a class and it separates words.
                    </div>
                </text>""".stripMargin

      val newValueParams =
        s"""
                    {
                      "project_id": "http://rdfh.ch/projects/0001",
                      "res_id": "http://rdfh.ch/0001/a-thing",
                      "prop": "http://www.knora.org/ontology/0001/anything#hasText",
                      "richtext_value": {
                            "xml": ${JsString(xmlToSend)},
                            "mapping_id": "$ANYTHING_PROJECT_IRI/mappings/MappingSeparatingWords"
                      }
                    }""".stripMargin

      // create standoff from XML
      Post("/v1/values", HttpEntity(ContentTypes.`application/json`, newValueParams)) ~> addCredentials(
        BasicHttpCredentials(anythingUserEmail, password)
      ) ~> valuesPath ~> check {

        assert(
          status == StatusCodes.OK,
          "creation of a TextValue from XML returned a non successful HTTP status code: " + responseAs[String]
        )

      }

    }

  }
}
