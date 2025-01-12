/*
 * Copyright © 2021 Data and Service Center for the Humanities and/or DaSCH Service Platform contributors.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.knora.webapi.routing.v2

import java.time.Instant
import java.util.UUID

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatcher, Route}
import org.knora.webapi._
import org.knora.webapi.exceptions.BadRequestException
import org.knora.webapi.feature.FeatureFactoryConfig
import org.knora.webapi.messages.IriConversions._
import org.knora.webapi.messages.util.rdf.{JsonLDDocument, JsonLDUtil}
import org.knora.webapi.messages.v2.responder.resourcemessages._
import org.knora.webapi.messages.v2.responder.searchmessages.SearchResourcesByProjectAndClassRequestV2
import org.knora.webapi.messages.{SmartIri, StringFormatter}
import org.knora.webapi.routing.{Authenticator, KnoraRoute, KnoraRouteData, RouteUtilV2}

import scala.concurrent.Future

object ResourcesRouteV2 {
  val ResourcesBasePath: PathMatcher[Unit] = PathMatcher("v2" / "resources")
}

/**
 * Provides a routing function for API v2 routes that deal with resources.
 */
class ResourcesRouteV2(routeData: KnoraRouteData) extends KnoraRoute(routeData) with Authenticator {

  import ResourcesRouteV2._

  private val Text_Property = "textProperty"
  private val Mapping_Iri = "mappingIri"
  private val GravsearchTemplate_Iri = "gravsearchTemplateIri"
  private val TEIHeader_XSLT_IRI = "teiHeaderXSLTIri"
  private val Depth = "depth"
  private val ExcludeProperty = "excludeProperty"
  private val Direction = "direction"
  private val Inbound = "inbound"
  private val Outbound = "outbound"
  private val Both = "both"

  /**
   * Returns the route.
   */
  override def makeRoute(featureFactoryConfig: FeatureFactoryConfig): Route =
    getIIIFManifest(featureFactoryConfig) ~
      createResource(featureFactoryConfig) ~
      updateResourceMetadata(featureFactoryConfig) ~
      getResourcesInProject(featureFactoryConfig) ~
      getResourceHistory(featureFactoryConfig) ~
      getResourceHistoryEvents(featureFactoryConfig) ~
      getProjectResourceAndValueHistory(featureFactoryConfig) ~
      getResources(featureFactoryConfig) ~
      getResourcesPreview(featureFactoryConfig) ~
      getResourcesTei(featureFactoryConfig) ~
      getResourcesGraph(featureFactoryConfig) ~
      deleteResource(featureFactoryConfig) ~
      eraseResource(featureFactoryConfig)

  private def getIIIFManifest(featureFactoryConfig: FeatureFactoryConfig): Route =
    path(ResourcesBasePath / "iiifmanifest" / Segment) { resourceIriStr: IRI =>
      get { requestContext =>
        val resourceIri: IRI =
          stringFormatter.validateAndEscapeIri(
            resourceIriStr,
            throw BadRequestException(s"Invalid resource IRI: $resourceIriStr")
          )

        val requestMessageFuture: Future[ResourceIIIFManifestGetRequestV2] = for {
          requestingUser <- getUserADM(
            requestContext = requestContext,
            featureFactoryConfig = featureFactoryConfig
          )
        } yield ResourceIIIFManifestGetRequestV2(
          resourceIri = resourceIri,
          featureFactoryConfig = featureFactoryConfig,
          requestingUser = requestingUser
        )

        RouteUtilV2.runRdfRouteWithFuture(
          requestMessageF = requestMessageFuture,
          requestContext = requestContext,
          featureFactoryConfig = featureFactoryConfig,
          settings = settings,
          responderManager = responderManager,
          log = log,
          targetSchema = ApiV2Complex,
          schemaOptions = RouteUtilV2.getSchemaOptions(requestContext)
        )
      }
    }

  private def createResource(featureFactoryConfig: FeatureFactoryConfig): Route = path(ResourcesBasePath) {
    post {
      entity(as[String]) { jsonRequest => requestContext =>
        {
          val requestDoc: JsonLDDocument = JsonLDUtil.parseJsonLD(jsonRequest)

          val requestMessageFuture: Future[CreateResourceRequestV2] = for {
            requestingUser <- getUserADM(
              requestContext = requestContext,
              featureFactoryConfig = featureFactoryConfig
            )

            requestMessage: CreateResourceRequestV2 <- CreateResourceRequestV2.fromJsonLD(
              requestDoc,
              apiRequestID = UUID.randomUUID,
              requestingUser = requestingUser,
              responderManager = responderManager,
              storeManager = storeManager,
              featureFactoryConfig = featureFactoryConfig,
              settings = settings,
              log = log
            )
          } yield requestMessage

          RouteUtilV2.runRdfRouteWithFuture(
            requestMessageF = requestMessageFuture,
            requestContext = requestContext,
            featureFactoryConfig = featureFactoryConfig,
            settings = settings,
            responderManager = responderManager,
            log = log,
            targetSchema = ApiV2Complex,
            schemaOptions = RouteUtilV2.getSchemaOptions(requestContext)
          )
        }
      }
    }
  }

  private def updateResourceMetadata(featureFactoryConfig: FeatureFactoryConfig): Route = path(ResourcesBasePath) {
    put {
      entity(as[String]) { jsonRequest => requestContext =>
        {
          val requestDoc: JsonLDDocument = JsonLDUtil.parseJsonLD(jsonRequest)

          val requestMessageFuture: Future[UpdateResourceMetadataRequestV2] = for {
            requestingUser <- getUserADM(
              requestContext = requestContext,
              featureFactoryConfig = featureFactoryConfig
            )

            requestMessage: UpdateResourceMetadataRequestV2 <- UpdateResourceMetadataRequestV2.fromJsonLD(
              requestDoc,
              apiRequestID = UUID.randomUUID,
              requestingUser = requestingUser,
              responderManager = responderManager,
              storeManager = storeManager,
              featureFactoryConfig = featureFactoryConfig,
              settings = settings,
              log = log
            )
          } yield requestMessage

          RouteUtilV2.runRdfRouteWithFuture(
            requestMessageF = requestMessageFuture,
            requestContext = requestContext,
            featureFactoryConfig = featureFactoryConfig,
            settings = settings,
            responderManager = responderManager,
            log = log,
            targetSchema = ApiV2Complex,
            schemaOptions = RouteUtilV2.getSchemaOptions(requestContext)
          )
        }
      }
    }
  }

  private def getResourcesInProject(featureFactoryConfig: FeatureFactoryConfig): Route = path(ResourcesBasePath) {
    get { requestContext =>
      val projectIri: SmartIri = RouteUtilV2
        .getProject(requestContext)
        .getOrElse(throw BadRequestException(s"This route requires the request header ${RouteUtilV2.PROJECT_HEADER}"))
      val params: Map[String, String] = requestContext.request.uri.query().toMap

      val resourceClassStr: String =
        params.getOrElse(
          "resourceClass",
          throw BadRequestException(s"This route requires the parameter 'resourceClass'")
        )
      val resourceClass: SmartIri =
        resourceClassStr.toSmartIriWithErr(throw BadRequestException(s"Invalid resource class IRI: $resourceClassStr"))

      if (!(resourceClass.isKnoraApiV2EntityIri && resourceClass.getOntologySchema.contains(ApiV2Complex))) {
        throw BadRequestException(s"Invalid resource class IRI: $resourceClassStr")
      }

      val maybeOrderByPropertyStr: Option[String] = params.get("orderByProperty")
      val maybeOrderByProperty: Option[SmartIri] = maybeOrderByPropertyStr.map { orderByPropertyStr =>
        val orderByProperty =
          orderByPropertyStr.toSmartIriWithErr(throw BadRequestException(s"Invalid property IRI: $orderByPropertyStr"))

        if (!(orderByProperty.isKnoraApiV2EntityIri && orderByProperty.getOntologySchema.contains(ApiV2Complex))) {
          throw BadRequestException(s"Invalid property IRI: $orderByPropertyStr")
        }

        orderByProperty.toOntologySchema(ApiV2Complex)
      }

      val pageStr: String =
        params.getOrElse("page", throw BadRequestException(s"This route requires the parameter 'page'"))
      val page: Int =
        stringFormatter.validateInt(pageStr, throw BadRequestException(s"Invalid page number: $pageStr"))

      val schemaOptions: Set[SchemaOption] = RouteUtilV2.getSchemaOptions(requestContext)

      val targetSchema: ApiV2Schema = RouteUtilV2.getOntologySchema(requestContext)

      val requestMessageFuture: Future[SearchResourcesByProjectAndClassRequestV2] = for {
        requestingUser <- getUserADM(
          requestContext = requestContext,
          featureFactoryConfig = featureFactoryConfig
        )
      } yield SearchResourcesByProjectAndClassRequestV2(
        projectIri = projectIri,
        resourceClass = resourceClass.toOntologySchema(ApiV2Complex),
        orderByProperty = maybeOrderByProperty,
        page = page,
        targetSchema = targetSchema,
        schemaOptions = schemaOptions,
        featureFactoryConfig = featureFactoryConfig,
        requestingUser = requestingUser
      )

      RouteUtilV2.runRdfRouteWithFuture(
        requestMessageF = requestMessageFuture,
        requestContext = requestContext,
        featureFactoryConfig = featureFactoryConfig,
        settings = settings,
        responderManager = responderManager,
        log = log,
        targetSchema = ApiV2Complex,
        schemaOptions = schemaOptions
      )
    }
  }

  private def getResourceHistory(featureFactoryConfig: FeatureFactoryConfig): Route =
    path(ResourcesBasePath / "history" / Segment) { resourceIriStr: IRI =>
      get { requestContext =>
        val resourceIri =
          stringFormatter.validateAndEscapeIri(
            resourceIriStr,
            throw BadRequestException(s"Invalid resource IRI: $resourceIriStr")
          )
        val params: Map[String, String] = requestContext.request.uri.query().toMap
        val startDate: Option[Instant] = params
          .get("startDate")
          .map(dateStr =>
            stringFormatter
              .xsdDateTimeStampToInstant(dateStr, throw BadRequestException(s"Invalid start date: $dateStr"))
          )
        val endDate = params
          .get("endDate")
          .map(dateStr =>
            stringFormatter.xsdDateTimeStampToInstant(dateStr, throw BadRequestException(s"Invalid end date: $dateStr"))
          )

        val requestMessageFuture: Future[ResourceVersionHistoryGetRequestV2] = for {
          requestingUser <- getUserADM(
            requestContext = requestContext,
            featureFactoryConfig = featureFactoryConfig
          )
        } yield ResourceVersionHistoryGetRequestV2(
          resourceIri = resourceIri,
          startDate = startDate,
          endDate = endDate,
          featureFactoryConfig = featureFactoryConfig,
          requestingUser = requestingUser
        )

        RouteUtilV2.runRdfRouteWithFuture(
          requestMessageF = requestMessageFuture,
          requestContext = requestContext,
          featureFactoryConfig = featureFactoryConfig,
          settings = settings,
          responderManager = responderManager,
          log = log,
          targetSchema = ApiV2Complex,
          schemaOptions = RouteUtilV2.getSchemaOptions(requestContext)
        )
      }
    }

  private def getResourceHistoryEvents(featureFactoryConfig: FeatureFactoryConfig): Route =
    path(ResourcesBasePath / "resourceHistoryEvents" / Segment) { resourceIri: IRI =>
      get { requestContext =>
        val requestMessageFuture: Future[ResourceHistoryEventsGetRequestV2] = for {
          requestingUser <- getUserADM(
            requestContext = requestContext,
            featureFactoryConfig = featureFactoryConfig
          )
        } yield ResourceHistoryEventsGetRequestV2(
          resourceIri = resourceIri,
          featureFactoryConfig = featureFactoryConfig,
          requestingUser = requestingUser
        )

        RouteUtilV2.runRdfRouteWithFuture(
          requestMessageF = requestMessageFuture,
          requestContext = requestContext,
          featureFactoryConfig = featureFactoryConfig,
          settings = settings,
          responderManager = responderManager,
          log = log,
          targetSchema = ApiV2Complex,
          schemaOptions = RouteUtilV2.getSchemaOptions(requestContext)
        )
      }
    }

  private def getProjectResourceAndValueHistory(featureFactoryConfig: FeatureFactoryConfig): Route =
    path(ResourcesBasePath / "projectHistoryEvents" / Segment) { projectIri: IRI =>
      get { requestContext =>
        val requestMessageFuture: Future[ProjectResourcesWithHistoryGetRequestV2] = for {
          requestingUser <- getUserADM(
            requestContext = requestContext,
            featureFactoryConfig = featureFactoryConfig
          )
        } yield ProjectResourcesWithHistoryGetRequestV2(
          projectIri = projectIri,
          featureFactoryConfig = featureFactoryConfig,
          requestingUser = requestingUser
        )

        RouteUtilV2.runRdfRouteWithFuture(
          requestMessageF = requestMessageFuture,
          requestContext = requestContext,
          featureFactoryConfig = featureFactoryConfig,
          settings = settings,
          responderManager = responderManager,
          log = log,
          targetSchema = ApiV2Complex,
          schemaOptions = RouteUtilV2.getSchemaOptions(requestContext)
        )
      }
    }

  private def getResources(featureFactoryConfig: FeatureFactoryConfig): Route = path(ResourcesBasePath / Segments) {
    resIris: Seq[String] =>
      get { requestContext =>
        if (resIris.size > settings.v2ResultsPerPage)
          throw BadRequestException(s"List of provided resource Iris exceeds limit of ${settings.v2ResultsPerPage}")

        val resourceIris: Seq[IRI] = resIris.map { resIri: String =>
          stringFormatter.validateAndEscapeIri(resIri, throw BadRequestException(s"Invalid resource IRI: <$resIri>"))
        }

        val params: Map[String, String] = requestContext.request.uri.query().toMap

        // Was a version date provided?
        val versionDate: Option[Instant] = params.get("version").map { versionStr =>
          def errorFun: Nothing = throw BadRequestException(s"Invalid version date: $versionStr")

          // Yes. Try to parse it as an xsd:dateTimeStamp.
          try {
            stringFormatter.xsdDateTimeStampToInstant(versionStr, errorFun)
          } catch {
            // If that doesn't work, try to parse it as a Knora ARK timestamp.
            case _: Exception => stringFormatter.arkTimestampToInstant(versionStr, errorFun)
          }
        }

        val targetSchema: ApiV2Schema = RouteUtilV2.getOntologySchema(requestContext)
        val schemaOptions: Set[SchemaOption] = RouteUtilV2.getSchemaOptions(requestContext)

        val requestMessageFuture: Future[ResourcesGetRequestV2] = for {
          requestingUser <- getUserADM(
            requestContext = requestContext,
            featureFactoryConfig = featureFactoryConfig
          )
        } yield ResourcesGetRequestV2(
          resourceIris = resourceIris,
          versionDate = versionDate,
          targetSchema = targetSchema,
          schemaOptions = schemaOptions,
          featureFactoryConfig = featureFactoryConfig,
          requestingUser = requestingUser
        )

        RouteUtilV2.runRdfRouteWithFuture(
          requestMessageF = requestMessageFuture,
          requestContext = requestContext,
          featureFactoryConfig = featureFactoryConfig,
          settings = settings,
          responderManager = responderManager,
          log = log,
          targetSchema = targetSchema,
          schemaOptions = schemaOptions
        )
      }
  }

  private def getResourcesPreview(featureFactoryConfig: FeatureFactoryConfig): Route =
    path("v2" / "resourcespreview" / Segments) { resIris: Seq[String] =>
      get { requestContext =>
        if (resIris.size > settings.v2ResultsPerPage)
          throw BadRequestException(s"List of provided resource Iris exceeds limit of ${settings.v2ResultsPerPage}")

        val resourceIris: Seq[IRI] = resIris.map { resIri: String =>
          stringFormatter.validateAndEscapeIri(resIri, throw BadRequestException(s"Invalid resource IRI: <$resIri>"))
        }

        val targetSchema: ApiV2Schema = RouteUtilV2.getOntologySchema(requestContext)

        val requestMessageFuture: Future[ResourcesPreviewGetRequestV2] = for {
          requestingUser <- getUserADM(
            requestContext = requestContext,
            featureFactoryConfig = featureFactoryConfig
          )
        } yield ResourcesPreviewGetRequestV2(
          resourceIris = resourceIris,
          targetSchema = targetSchema,
          featureFactoryConfig = featureFactoryConfig,
          requestingUser = requestingUser
        )

        RouteUtilV2.runRdfRouteWithFuture(
          requestMessageF = requestMessageFuture,
          requestContext = requestContext,
          featureFactoryConfig = featureFactoryConfig,
          settings = settings,
          responderManager = responderManager,
          log = log,
          targetSchema = RouteUtilV2.getOntologySchema(requestContext),
          schemaOptions = RouteUtilV2.getSchemaOptions(requestContext)
        )
      }
    }

  private def getResourcesTei(featureFactoryConfig: FeatureFactoryConfig): Route = path("v2" / "tei" / Segment) {
    resIri: String =>
      get { requestContext =>
        val resourceIri: IRI =
          stringFormatter.validateAndEscapeIri(resIri, throw BadRequestException(s"Invalid resource IRI: <$resIri>"))

        val params: Map[String, String] = requestContext.request.uri.query().toMap

        // the the property that represents the text
        val textProperty: SmartIri = getTextPropertyFromParams(params)

        val mappingIri: Option[IRI] = getMappingIriFromParams(params)

        val gravsearchTemplateIri: Option[IRI] = getGravsearchTemplateIriFromParams(params)

        val headerXSLTIri = getHeaderXSLTIriFromParams(params)

        val requestMessageFuture: Future[ResourceTEIGetRequestV2] = for {
          requestingUser <- getUserADM(
            requestContext = requestContext,
            featureFactoryConfig = featureFactoryConfig
          )
        } yield ResourceTEIGetRequestV2(
          resourceIri = resourceIri,
          textProperty = textProperty,
          mappingIri = mappingIri,
          gravsearchTemplateIri = gravsearchTemplateIri,
          headerXSLTIri = headerXSLTIri,
          featureFactoryConfig = featureFactoryConfig,
          requestingUser = requestingUser
        )

        RouteUtilV2.runTEIXMLRoute(
          requestMessageF = requestMessageFuture,
          requestContext = requestContext,
          featureFactoryConfig = featureFactoryConfig,
          settings = settings,
          responderManager = responderManager,
          log = log,
          targetSchema = RouteUtilV2.getOntologySchema(requestContext)
        )
      }
  }

  private def getResourcesGraph(featureFactoryConfig: FeatureFactoryConfig): Route = path("v2" / "graph" / Segment) {
    resIriStr: String =>
      get { requestContext =>
        val resourceIri: IRI =
          stringFormatter.validateAndEscapeIri(
            resIriStr,
            throw BadRequestException(s"Invalid resource IRI: <$resIriStr>")
          )
        val params: Map[String, String] = requestContext.request.uri.query().toMap
        val depth: Int = params.get(Depth).map(_.toInt).getOrElse(settings.defaultGraphDepth)

        if (depth < 1) {
          throw BadRequestException(s"$Depth must be at least 1")
        }

        if (depth > settings.maxGraphDepth) {
          throw BadRequestException(s"$Depth cannot be greater than ${settings.maxGraphDepth}")
        }

        val direction: String = params.getOrElse(Direction, Outbound)
        val excludeProperty: Option[SmartIri] = params
          .get(ExcludeProperty)
          .map(propIriStr =>
            propIriStr.toSmartIriWithErr(throw BadRequestException(s"Invalid property IRI: <$propIriStr>"))
          )

        val (inbound: Boolean, outbound: Boolean) = direction match {
          case Inbound  => (true, false)
          case Outbound => (false, true)
          case Both     => (true, true)
          case other    => throw BadRequestException(s"Invalid direction: $other")
        }

        val requestMessageFuture: Future[GraphDataGetRequestV2] = for {
          requestingUser <- getUserADM(
            requestContext = requestContext,
            featureFactoryConfig = featureFactoryConfig
          )
        } yield GraphDataGetRequestV2(
          resourceIri = resourceIri,
          depth = depth,
          inbound = inbound,
          outbound = outbound,
          excludeProperty = excludeProperty,
          requestingUser = requestingUser
        )

        RouteUtilV2.runRdfRouteWithFuture(
          requestMessageF = requestMessageFuture,
          requestContext = requestContext,
          featureFactoryConfig = featureFactoryConfig,
          settings = settings,
          responderManager = responderManager,
          log = log,
          targetSchema = RouteUtilV2.getOntologySchema(requestContext),
          schemaOptions = RouteUtilV2.getSchemaOptions(requestContext)
        )
      }
  }

  private def deleteResource(featureFactoryConfig: FeatureFactoryConfig): Route = path(ResourcesBasePath / "delete") {
    post {
      entity(as[String]) { jsonRequest => requestContext =>
        {
          val requestDoc: JsonLDDocument = JsonLDUtil.parseJsonLD(jsonRequest)

          val requestMessageFuture: Future[DeleteOrEraseResourceRequestV2] = for {
            requestingUser <- getUserADM(
              requestContext = requestContext,
              featureFactoryConfig = featureFactoryConfig
            )
            requestMessage: DeleteOrEraseResourceRequestV2 <- DeleteOrEraseResourceRequestV2.fromJsonLD(
              requestDoc,
              apiRequestID = UUID.randomUUID,
              requestingUser = requestingUser,
              responderManager = responderManager,
              storeManager = storeManager,
              featureFactoryConfig = featureFactoryConfig,
              settings = settings,
              log = log
            )
          } yield requestMessage

          RouteUtilV2.runRdfRouteWithFuture(
            requestMessageF = requestMessageFuture,
            requestContext = requestContext,
            featureFactoryConfig = featureFactoryConfig,
            settings = settings,
            responderManager = responderManager,
            log = log,
            targetSchema = ApiV2Complex,
            schemaOptions = RouteUtilV2.getSchemaOptions(requestContext)
          )
        }
      }
    }
  }

  private def eraseResource(featureFactoryConfig: FeatureFactoryConfig): Route = path(ResourcesBasePath / "erase") {
    post {
      entity(as[String]) { jsonRequest => requestContext =>
        {
          val requestDoc: JsonLDDocument = JsonLDUtil.parseJsonLD(jsonRequest)

          val requestMessageFuture: Future[DeleteOrEraseResourceRequestV2] = for {
            requestingUser <- getUserADM(
              requestContext = requestContext,
              featureFactoryConfig = featureFactoryConfig
            )

            requestMessage: DeleteOrEraseResourceRequestV2 <- DeleteOrEraseResourceRequestV2.fromJsonLD(
              requestDoc,
              apiRequestID = UUID.randomUUID,
              requestingUser = requestingUser,
              responderManager = responderManager,
              storeManager = storeManager,
              featureFactoryConfig = featureFactoryConfig,
              settings = settings,
              log = log
            )
          } yield requestMessage.copy(erase = true)

          RouteUtilV2.runRdfRouteWithFuture(
            requestMessageF = requestMessageFuture,
            requestContext = requestContext,
            featureFactoryConfig = featureFactoryConfig,
            settings = settings,
            responderManager = responderManager,
            log = log,
            targetSchema = ApiV2Complex,
            schemaOptions = RouteUtilV2.getSchemaOptions(requestContext)
          )
        }
      }
    }
  }

  /**
   * Gets the Iri of the property that represents the text of the resource.
   *
   * @param params the GET parameters.
   * @return the internal resource class, if any.
   */
  private def getTextPropertyFromParams(params: Map[String, String]): SmartIri = {
    implicit val stringFormatter: StringFormatter = StringFormatter.getGeneralInstance
    val textProperty = params.get(Text_Property)

    textProperty match {
      case Some(textPropIriStr: String) =>
        val externalResourceClassIri =
          textPropIriStr.toSmartIriWithErr(throw BadRequestException(s"Invalid property IRI: <$textPropIriStr>"))

        if (!externalResourceClassIri.isKnoraApiV2EntityIri) {
          throw BadRequestException(s"<$textPropIriStr> is not a valid knora-api property IRI")
        }

        externalResourceClassIri.toOntologySchema(InternalSchema)

      case None => throw BadRequestException(s"param $Text_Property not set")
    }
  }

  /**
   * Gets the Iri of the mapping to be used to convert standoff to XML.
   *
   * @param params the GET parameters.
   * @return the internal resource class, if any.
   */
  private def getMappingIriFromParams(params: Map[String, String]): Option[IRI] = {
    implicit val stringFormatter: StringFormatter = StringFormatter.getGeneralInstance
    val mappingIriStr = params.get(Mapping_Iri)

    mappingIriStr match {
      case Some(mapping: String) =>
        Some(
          stringFormatter.validateAndEscapeIri(mapping, throw BadRequestException(s"Invalid mapping IRI: <$mapping>"))
        )

      case None => None
    }
  }

  /**
   * Gets the Iri of Gravsearch template to be used to query for the resource's metadata.
   *
   * @param params the GET parameters.
   * @return the internal resource class, if any.
   */
  private def getGravsearchTemplateIriFromParams(params: Map[String, String]): Option[IRI] = {
    implicit val stringFormatter: StringFormatter = StringFormatter.getGeneralInstance
    val gravsearchTemplateIriStr = params.get(GravsearchTemplate_Iri)

    gravsearchTemplateIriStr match {
      case Some(gravsearch: String) =>
        Some(
          stringFormatter.validateAndEscapeIri(
            gravsearch,
            throw BadRequestException(s"Invalid template IRI: <$gravsearch>")
          )
        )

      case None => None
    }
  }

  /**
   * Gets the Iri of the XSL transformation to be used to convert the TEI header's metadata.
   *
   * @param params the GET parameters.
   * @return the internal resource class, if any.
   */
  private def getHeaderXSLTIriFromParams(params: Map[String, String]): Option[IRI] = {
    implicit val stringFormatter: StringFormatter = StringFormatter.getGeneralInstance
    val headerXSLTIriStr = params.get(TEIHeader_XSLT_IRI)

    headerXSLTIriStr match {
      case Some(xslt: String) =>
        Some(stringFormatter.validateAndEscapeIri(xslt, throw BadRequestException(s"Invalid XSLT IRI: <$xslt>")))

      case None => None
    }
  }
}
