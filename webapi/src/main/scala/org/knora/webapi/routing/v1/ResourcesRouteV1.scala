/*
 * Copyright © 2021 Data and Service Center for the Humanities and/or DaSCH Service Platform contributors.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.knora.webapi.routing.v1

import java.io._
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.util.FastFuture
import akka.pattern._
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.{Schema, SchemaFactory, Validator}
import org.knora.webapi._
import org.knora.webapi.exceptions.{
  AssertionException,
  BadRequestException,
  ForbiddenException,
  InconsistentRepositoryDataException
}
import org.knora.webapi.feature.FeatureFactoryConfig
import org.knora.webapi.messages.IriConversions._
import org.knora.webapi.messages.StringFormatter.XmlImportNamespaceInfoV1
import org.knora.webapi.messages.admin.responder.projectsmessages.{
  ProjectGetRequestADM,
  ProjectGetResponseADM,
  ProjectIdentifierADM
}
import org.knora.webapi.messages.admin.responder.usersmessages.UserADM
import org.knora.webapi.messages.store.sipimessages.{GetFileMetadataRequest, GetFileMetadataResponse}
import org.knora.webapi.messages.twirl.ResourceHtmlView
import org.knora.webapi.messages.util.DateUtilV1
import org.knora.webapi.messages.util.standoff.StandoffTagUtilV2.TextWithStandoffTagsV2
import org.knora.webapi.messages.v1.responder.ontologymessages._
import org.knora.webapi.messages.v1.responder.resourcemessages.ResourceV1JsonProtocol._
import org.knora.webapi.messages.v1.responder.resourcemessages._
import org.knora.webapi.messages.v1.responder.valuemessages._
import org.knora.webapi.messages.{OntologyConstants, SmartIri}
import org.knora.webapi.routing.{Authenticator, KnoraRoute, KnoraRouteData, RouteUtilV1}
import org.knora.webapi.util.{ActorUtil, FileUtil}
import org.w3c.dom.ls.{LSInput, LSResourceResolver}
import org.xml.sax.SAXException

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.xml._

/**
 * Provides API routes that deal with resources.
 */
class ResourcesRouteV1(routeData: KnoraRouteData) extends KnoraRoute(routeData) with Authenticator {
  // A scala.xml.PrettyPrinter for formatting generated XML import schemas.
  private val xmlPrettyPrinter = new scala.xml.PrettyPrinter(width = 160, step = 4)

  /**
   * Returns the route.
   */
  override def makeRoute(featureFactoryConfig: FeatureFactoryConfig): Route = {

    def makeResourceRequestMessage(
      resIri: String,
      resinfo: Boolean,
      requestType: String,
      userADM: UserADM
    ): ResourcesResponderRequestV1 = {
      val validResIri =
        stringFormatter.validateAndEscapeIri(resIri, throw BadRequestException(s"Invalid resource IRI: $resIri"))

      requestType match {
        case "info" =>
          ResourceInfoGetRequestV1(
            iri = validResIri,
            featureFactoryConfig = featureFactoryConfig,
            userProfile = userADM
          )

        case "rights" =>
          ResourceRightsGetRequestV1(
            iri = validResIri,
            featureFactoryConfig = featureFactoryConfig,
            userProfile = userADM
          )

        case "context" =>
          ResourceContextGetRequestV1(
            iri = validResIri,
            featureFactoryConfig = featureFactoryConfig,
            userProfile = userADM,
            resinfo = resinfo
          )

        case "" =>
          ResourceFullGetRequestV1(
            iri = validResIri,
            featureFactoryConfig = featureFactoryConfig,
            userADM = userADM
          )

        case other => throw BadRequestException(s"Invalid request type: $other")
      }
    }

    def makeResourceSearchRequestMessage(
      searchString: String,
      resourceTypeIri: Option[IRI],
      numberOfProps: Int,
      limitOfResults: Int,
      userProfile: UserADM
    ): ResourceSearchGetRequestV1 =
      ResourceSearchGetRequestV1(
        searchString = searchString,
        resourceTypeIri = resourceTypeIri,
        numberOfProps = numberOfProps,
        limitOfResults = limitOfResults,
        userProfile = userProfile
      )

    def valuesToCreate(
      properties: Map[IRI, Seq[CreateResourceValueV1]],
      acceptStandoffLinksToClientIDs: Boolean,
      userProfile: UserADM
    ): Map[IRI, Future[Seq[CreateValueV1WithComment]]] = {
      properties.map { case (propIri: IRI, values: Seq[CreateResourceValueV1]) =>
        (
          stringFormatter.validateAndEscapeIri(propIri, throw BadRequestException(s"Invalid property IRI $propIri")),
          values.map { givenValue: CreateResourceValueV1 =>
            givenValue.getValueClassIri match {
              // create corresponding UpdateValueV1

              case OntologyConstants.KnoraBase.TextValue =>
                val richtext: CreateRichtextV1 = givenValue.richtext_value.get

                // check if text has markup
                if (richtext.utf8str.nonEmpty && richtext.xml.isEmpty && richtext.mapping_id.isEmpty) {
                  // simple text

                  Future(
                    CreateValueV1WithComment(
                      TextValueSimpleV1(
                        utf8str = stringFormatter.toSparqlEncodedString(
                          richtext.utf8str.get,
                          throw BadRequestException(s"Invalid text: '${richtext.utf8str.get}'")
                        ),
                        language = richtext.language
                      ),
                      givenValue.comment
                    )
                  )

                } else if (richtext.xml.nonEmpty && richtext.mapping_id.nonEmpty) {
                  // XML: text with markup

                  val mappingIri = stringFormatter.validateAndEscapeIri(
                    richtext.mapping_id.get,
                    throw BadRequestException(s"mapping_id ${richtext.mapping_id.get} is invalid")
                  )

                  for {

                    textWithStandoffTags: TextWithStandoffTagsV2 <- RouteUtilV1.convertXMLtoStandoffTagV1(
                      xml = richtext.xml.get,
                      mappingIri = mappingIri,
                      acceptStandoffLinksToClientIDs = acceptStandoffLinksToClientIDs,
                      userProfile = userProfile,
                      featureFactoryConfig = featureFactoryConfig,
                      settings = settings,
                      responderManager = responderManager,
                      log = log
                    )

                    // collect the resource references from the linking standoff nodes
                    resourceReferences: Set[IRI] = stringFormatter.getResourceIrisFromStandoffTags(
                      textWithStandoffTags.standoffTagV2
                    )

                  } yield CreateValueV1WithComment(
                    TextValueWithStandoffV1(
                      utf8str = stringFormatter.toSparqlEncodedString(
                        textWithStandoffTags.text,
                        throw InconsistentRepositoryDataException("utf8str for TextValue contains invalid characters")
                      ),
                      language = richtext.language,
                      resource_reference = resourceReferences,
                      standoff = textWithStandoffTags.standoffTagV2,
                      mappingIri = textWithStandoffTags.mapping.mappingIri,
                      mapping = textWithStandoffTags.mapping.mapping
                    ),
                    givenValue.comment
                  )

                } else {
                  throw BadRequestException("invalid parameters given for TextValueV1")
                }

              case OntologyConstants.KnoraBase.LinkValue =>
                (givenValue.link_value, givenValue.link_to_client_id) match {
                  case (Some(targetIri: IRI), None) =>
                    // This is a link to an existing Knora IRI, so make sure the IRI is valid.
                    val validatedTargetIri = stringFormatter.validateAndEscapeIri(
                      targetIri,
                      throw BadRequestException(s"Invalid Knora resource IRI: $targetIri")
                    )
                    Future(CreateValueV1WithComment(LinkUpdateV1(validatedTargetIri), givenValue.comment))

                  case (None, Some(clientIDForTargetResource: String)) =>
                    // This is a link to the client's ID for a resource that hasn't been created yet.
                    Future(
                      CreateValueV1WithComment(LinkToClientIDUpdateV1(clientIDForTargetResource), givenValue.comment)
                    )

                  case (_, _) => throw AssertionException(s"Invalid link: $givenValue")
                }

              case OntologyConstants.KnoraBase.IntValue =>
                Future(CreateValueV1WithComment(IntegerValueV1(givenValue.int_value.get), givenValue.comment))

              case OntologyConstants.KnoraBase.DecimalValue =>
                Future(CreateValueV1WithComment(DecimalValueV1(givenValue.decimal_value.get), givenValue.comment))

              case OntologyConstants.KnoraBase.BooleanValue =>
                Future(CreateValueV1WithComment(BooleanValueV1(givenValue.boolean_value.get), givenValue.comment))

              case OntologyConstants.KnoraBase.UriValue =>
                val uriValue = stringFormatter.validateAndEscapeIri(
                  givenValue.uri_value.get,
                  throw BadRequestException(s"Invalid URI: ${givenValue.uri_value.get}")
                )
                Future(CreateValueV1WithComment(UriValueV1(uriValue), givenValue.comment))

              case OntologyConstants.KnoraBase.DateValue =>
                val dateVal: JulianDayNumberValueV1 =
                  DateUtilV1.createJDNValueV1FromDateString(givenValue.date_value.get)
                Future(CreateValueV1WithComment(dateVal, givenValue.comment))

              case OntologyConstants.KnoraBase.ColorValue =>
                val colorValue = stringFormatter.validateColor(
                  givenValue.color_value.get,
                  throw BadRequestException(s"Invalid color value: ${givenValue.color_value.get}")
                )
                Future(CreateValueV1WithComment(ColorValueV1(colorValue), givenValue.comment))

              case OntologyConstants.KnoraBase.GeomValue =>
                val geometryValue = stringFormatter.validateGeometryString(
                  givenValue.geom_value.get,
                  throw BadRequestException(s"Invalid geometry value: ${givenValue.geom_value.get}")
                )
                Future(CreateValueV1WithComment(GeomValueV1(geometryValue), givenValue.comment))

              case OntologyConstants.KnoraBase.ListValue =>
                val listNodeIri = stringFormatter.validateAndEscapeIri(
                  givenValue.hlist_value.get,
                  throw BadRequestException(s"Invalid value IRI: ${givenValue.hlist_value.get}")
                )
                Future(CreateValueV1WithComment(HierarchicalListValueV1(listNodeIri), givenValue.comment))

              case OntologyConstants.KnoraBase.IntervalValue =>
                val timeVals: Seq[BigDecimal] = givenValue.interval_value.get
                if (timeVals.length != 2) throw BadRequestException("parameters for interval_value invalid")
                Future(CreateValueV1WithComment(IntervalValueV1(timeVals.head, timeVals(1)), givenValue.comment))

              case OntologyConstants.KnoraBase.TimeValue =>
                val timeValStr: String = givenValue.time_value.get
                val timeStamp: Instant = stringFormatter.xsdDateTimeStampToInstant(
                  timeValStr,
                  throw BadRequestException(s"Invalid timestamp: $timeValStr")
                )
                Future(CreateValueV1WithComment(TimeValueV1(timeStamp), givenValue.comment))

              case OntologyConstants.KnoraBase.GeonameValue =>
                Future(CreateValueV1WithComment(GeonameValueV1(givenValue.geoname_value.get), givenValue.comment))

              case _ => throw BadRequestException(s"No value submitted")

            }

          }
        )
      }.map {
        // transform Seq of Futures to a Future of a Seq
        case (propIri: IRI, values: Seq[Future[CreateValueV1WithComment]]) =>
          (propIri, Future.sequence(values))
      }

    }

    def makeCreateResourceRequestMessage(
      apiRequest: CreateResourceApiRequestV1,
      featureFactoryConfig: FeatureFactoryConfig,
      userADM: UserADM
    ): Future[ResourceCreateRequestV1] = {
      val projectIri = stringFormatter.validateAndEscapeIri(
        apiRequest.project_id,
        throw BadRequestException(s"Invalid project IRI: ${apiRequest.project_id}")
      )
      val resourceTypeIri = stringFormatter.validateAndEscapeIri(
        apiRequest.restype_id,
        throw BadRequestException(s"Invalid resource IRI: ${apiRequest.restype_id}")
      )
      val label = stringFormatter.toSparqlEncodedString(
        apiRequest.label,
        throw BadRequestException(s"Invalid label: '${apiRequest.label}'")
      )

      for {
        projectShortcode: String <- for {
          projectResponse: ProjectGetResponseADM <- (responderManager ? ProjectGetRequestADM(
            ProjectIdentifierADM(maybeIri = Some(projectIri)),
            featureFactoryConfig = featureFactoryConfig,
            requestingUser = userADM
          )).mapTo[ProjectGetResponseADM]
        } yield projectResponse.project.shortcode

        file: Option[FileValueV1] <- apiRequest.file match {
          case Some(filename) =>
            // Ask Sipi about the file's metadata.
            val tempFileUrl = stringFormatter.makeSipiTempFileUrl(settings, filename)

            for {
              fileMetadataResponse: GetFileMetadataResponse <- (storeManager ? GetFileMetadataRequest(
                fileUrl = tempFileUrl,
                requestingUser = userADM
              )).mapTo[GetFileMetadataResponse]
            } yield Some(
              RouteUtilV1.makeFileValue(
                filename = filename,
                fileMetadataResponse = fileMetadataResponse,
                projectShortcode = projectShortcode
              )
            )

          case None => FastFuture.successful(None)
        }

        valuesToBeCreatedWithFuture: Map[IRI, Future[Seq[CreateValueV1WithComment]]] = valuesToCreate(
          properties = apiRequest.properties,
          acceptStandoffLinksToClientIDs = false,
          userProfile = userADM
        )

        // make the whole Map a Future
        valuesToBeCreated: Map[IRI, Seq[CreateValueV1WithComment]] <- ActorUtil.sequenceFutureSeqsInMap(
          valuesToBeCreatedWithFuture
        )
      } yield ResourceCreateRequestV1(
        resourceTypeIri = resourceTypeIri,
        label = label,
        projectIri = projectIri,
        values = valuesToBeCreated,
        file = file,
        featureFactoryConfig = featureFactoryConfig,
        userProfile = userADM,
        apiRequestID = UUID.randomUUID
      )
    }

    def createOneResourceRequestFromXmlImport(
      resourceRequest: CreateResourceFromXmlImportRequestV1,
      projectShortcode: String,
      userProfile: UserADM
    ): Future[OneOfMultipleResourceCreateRequestV1] = {
      val values: Map[IRI, Future[Seq[CreateValueV1WithComment]]] = valuesToCreate(
        properties = resourceRequest.properties,
        acceptStandoffLinksToClientIDs = true,
        userProfile = userProfile
      )

      // make the whole Map a Future

      for {
        valuesToBeCreated: Map[IRI, Seq[CreateValueV1WithComment]] <- ActorUtil.sequenceFutureSeqsInMap(values)

        convertedFile <- resourceRequest.file match {
          case Some(filename) =>
            // Ask Sipi about the file's metadata.
            val tempFileUrl = stringFormatter.makeSipiTempFileUrl(settings, filename)

            for {
              fileMetadataResponse: GetFileMetadataResponse <- (storeManager ? GetFileMetadataRequest(
                fileUrl = tempFileUrl,
                requestingUser = userProfile
              )).mapTo[GetFileMetadataResponse]
            } yield Some(
              RouteUtilV1.makeFileValue(
                filename = filename,
                fileMetadataResponse = fileMetadataResponse,
                projectShortcode = projectShortcode
              )
            )

          case None => FastFuture.successful(None)
        }
      } yield OneOfMultipleResourceCreateRequestV1(
        resourceTypeIri = resourceRequest.restype_id,
        clientResourceID = resourceRequest.client_id,
        label = stringFormatter.toSparqlEncodedString(
          resourceRequest.label,
          throw BadRequestException(s"The resource label is invalid: '${resourceRequest.label}'")
        ),
        values = valuesToBeCreated,
        file = convertedFile,
        creationDate = resourceRequest.creationDate
      )
    }

    def makeMultiResourcesRequestMessage(
      resourceRequest: Seq[CreateResourceFromXmlImportRequestV1],
      projectId: IRI,
      apiRequestID: UUID,
      featureFactoryConfig: FeatureFactoryConfig,
      userProfile: UserADM
    ): Future[MultipleResourceCreateRequestV1] = {
      // Make sure there are no duplicate client resource IDs.

      val duplicateClientIDs: immutable.Iterable[String] = resourceRequest.map(_.client_id).groupBy(identity).collect {
        case (clientID, occurrences) if occurrences.size > 1 => clientID
      }

      if (duplicateClientIDs.nonEmpty) {
        throw BadRequestException(
          s"One or more client resource IDs were used for multiple resources: ${duplicateClientIDs.mkString(", ")}"
        )
      }

      for {
        projectShortcode: String <- for {
          projectResponse: ProjectGetResponseADM <- (responderManager ? ProjectGetRequestADM(
            identifier = ProjectIdentifierADM(maybeIri = Some(projectId)),
            featureFactoryConfig = featureFactoryConfig,
            requestingUser = userProfile
          )).mapTo[ProjectGetResponseADM]
        } yield projectResponse.project.shortcode

        resourcesToCreate: Seq[Future[OneOfMultipleResourceCreateRequestV1]] = resourceRequest.map {
          createResourceRequest =>
            createOneResourceRequestFromXmlImport(
              resourceRequest = createResourceRequest,
              projectShortcode = projectShortcode,
              userProfile = userProfile
            )
        }

        resToCreateCollection: Seq[OneOfMultipleResourceCreateRequestV1] <- Future.sequence(resourcesToCreate)
      } yield MultipleResourceCreateRequestV1(
        resourcesToCreate = resToCreateCollection,
        projectIri = projectId,
        featureFactoryConfig = featureFactoryConfig,
        userProfile = userProfile,
        apiRequestID = apiRequestID
      )
    }

    def makeGetPropertiesRequestMessage(resIri: IRI, userADM: UserADM): PropertiesGetRequestV1 =
      PropertiesGetRequestV1(
        iri = resIri,
        featureFactoryConfig = featureFactoryConfig,
        userProfile = userADM
      )

    def makeResourceDeleteMessage(
      resIri: IRI,
      deleteComment: Option[String],
      userADM: UserADM
    ): ResourceDeleteRequestV1 =
      ResourceDeleteRequestV1(
        resourceIri =
          stringFormatter.validateAndEscapeIri(resIri, throw BadRequestException(s"Invalid resource IRI: $resIri")),
        deleteComment = deleteComment.map(comment =>
          stringFormatter.toSparqlEncodedString(comment, throw BadRequestException(s"Invalid comment: '$comment'"))
        ),
        featureFactoryConfig = featureFactoryConfig,
        userADM = userADM,
        apiRequestID = UUID.randomUUID
      )

    /**
     * Given the IRI the main internal ontology to be used in an XML import, recursively gets instances of
     * [[NamedGraphEntityInfoV1]] for that ontology, for `knora-base`, and for any other ontologies containing
     * classes used in object class constraints in the main ontology.
     *
     * @param mainOntologyIri the IRI of the main ontology used in the XML import.
     * @param userProfile     the profile of the user making the request.
     * @return a map of internal ontology IRIs to [[NamedGraphEntityInfoV1]] objects.
     */
    def getNamedGraphInfos(mainOntologyIri: IRI, userProfile: UserADM): Future[Map[IRI, NamedGraphEntityInfoV1]] = {

      /**
       * Does the actual recursion for `getNamedGraphInfos`, loading only information about project-specific
       * ontologies (i.e. ontologies other than `knora-base`).
       *
       * @param initialOntologyIri  the IRI of the internal project-specific ontology to start with.
       * @param intermediateResults the intermediate results collected so far (a map of internal ontology IRIs to
       *                            [[NamedGraphEntityInfoV1]] objects). When this method is first called, this
       *                            collection must already contain a [[NamedGraphEntityInfoV1]] for
       *                            the `knora-base` ontology. This is an optimisation to avoid getting
       *                            information about `knora-base` repeatedly, since every project-specific
       *                            ontology depends on `knora-base`.
       * @param userProfile         the profile of the user making the request.
       * @return a map of internal ontology IRIs to [[NamedGraphEntityInfoV1]] objects.
       */
      def getNamedGraphInfosRec(
        initialOntologyIri: IRI,
        intermediateResults: Map[IRI, NamedGraphEntityInfoV1],
        userProfile: UserADM
      ): Future[Map[IRI, NamedGraphEntityInfoV1]] = {
        assert(intermediateResults.contains(OntologyConstants.KnoraBase.KnoraBaseOntologyIri))

        for {
          // Get a NamedGraphEntityInfoV1 listing the IRIs of the classes and properties defined in the initial ontology.
          initialNamedGraphInfo: NamedGraphEntityInfoV1 <- (responderManager ? NamedGraphEntityInfoRequestV1(
            initialOntologyIri,
            userProfile
          )).mapTo[NamedGraphEntityInfoV1]

          // Get details about those classes and properties.
          entityInfoResponse: EntityInfoGetResponseV1 <- (responderManager ? EntityInfoGetRequestV1(
            resourceClassIris = initialNamedGraphInfo.resourceClasses,
            propertyIris = initialNamedGraphInfo.propertyIris,
            userProfile = userProfile
          )).mapTo[EntityInfoGetResponseV1]

          // Look at the base classes of all the resource classes in the initial ontology. Make a set of
          // the ontologies containing the definitions of those classes, not including including the initial ontology itself
          // or any other ontologies we've already looked at.
          ontologyIrisFromBaseClasses: Set[IRI] = entityInfoResponse.resourceClassInfoMap.foldLeft(Set.empty[IRI]) {
            case (acc, (resourceClassIri, resourceClassInfo)) =>
              val subClassOfOntologies: Set[IRI] = resourceClassInfo.subClassOf
                .map(_.toSmartIri)
                .filter(_.isKnoraDefinitionIri)
                .map(_.getOntologyFromEntity.toString)
              acc ++ subClassOfOntologies
          } -- intermediateResults.keySet - initialOntologyIri

          // Look at the properties that have cardinalities in the resource classes in the initial ontology.
          // Make a set of the ontologies containing the definitions of those properties, not including the initial ontology itself
          // or any other ontologies we've already looked at.
          ontologyIrisFromCardinalities: Set[IRI] = entityInfoResponse.resourceClassInfoMap.foldLeft(Set.empty[IRI]) {
            case (acc, (resourceClassIri, resourceClassInfo)) =>
              val resourceCardinalityOntologies: Set[IRI] = resourceClassInfo.knoraResourceCardinalities.map {
                case (propertyIri, _) => propertyIri.toSmartIri.getOntologyFromEntity.toString
              }.toSet

              acc ++ resourceCardinalityOntologies
          } -- intermediateResults.keySet - initialOntologyIri

          // Look at the object class constraints of the properties in the initial ontology. Make a set of the ontologies containing those classes,
          // not including the initial ontology itself or any other ontologies we've already looked at.
          ontologyIrisFromObjectClassConstraints: Set[IRI] = entityInfoResponse.propertyInfoMap.map {
            case (propertyIri, propertyInfo) =>
              val propertyObjectClassConstraint =
                propertyInfo.getPredicateObject(OntologyConstants.KnoraBase.ObjectClassConstraint).getOrElse {
                  throw InconsistentRepositoryDataException(
                    s"Property $propertyIri has no knora-base:objectClassConstraint"
                  )
                }

              propertyObjectClassConstraint.toSmartIri.getOntologyFromEntity.toString
          }.toSet -- intermediateResults.keySet - initialOntologyIri

          // Make a set of all the ontologies referenced by the initial ontology.
          referencedOntologies: Set[IRI] =
            ontologyIrisFromBaseClasses ++ ontologyIrisFromCardinalities ++ ontologyIrisFromObjectClassConstraints

          // Recursively get NamedGraphEntityInfoV1 instances for each of those ontologies.
          lastResults: Map[IRI, NamedGraphEntityInfoV1] <- referencedOntologies.foldLeft(
            FastFuture.successful(intermediateResults + (initialOntologyIri -> initialNamedGraphInfo))
          ) { case (accFuture, ontologyIri) =>
            for {
              acc: Map[IRI, NamedGraphEntityInfoV1] <- accFuture

              // Has a previous recursion already dealt with this ontology?
              nextResults: Map[IRI, NamedGraphEntityInfoV1] <-
                if (acc.contains(ontologyIri)) {
                  // Yes, so there's no need to get it again.
                  FastFuture.successful(acc)
                } else {
                  // No. Recursively get it and the ontologies it depends on.
                  getNamedGraphInfosRec(
                    initialOntologyIri = ontologyIri,
                    intermediateResults = acc,
                    userProfile = userProfile
                  )
                }
            } yield acc ++ nextResults
          }
        } yield lastResults
      }

      for {
        // Get a NamedGraphEntityInfoV1 for the knora-base ontology.
        knoraBaseGraphEntityInfo <- (responderManager ? NamedGraphEntityInfoRequestV1(
          OntologyConstants.KnoraBase.KnoraBaseOntologyIri,
          userProfile
        )).mapTo[NamedGraphEntityInfoV1]

        // Recursively get NamedGraphEntityInfoV1 instances for the main ontology to be used in the XML import,
        // as well as any other project-specific ontologies it depends on.
        graphInfos <- getNamedGraphInfosRec(
          initialOntologyIri = mainOntologyIri,
          intermediateResults = Map(OntologyConstants.KnoraBase.KnoraBaseOntologyIri -> knoraBaseGraphEntityInfo),
          userProfile = userProfile
        )
      } yield graphInfos
    }

    /**
     * Given the IRI of an internal project-specific ontology, generates an [[XmlImportSchemaBundleV1]] for validating
     * XML imports for that ontology and any other ontologies it depends on.
     *
     * @param internalOntologyIri the IRI of the main internal project-specific ontology to be used in the XML import.
     * @param userProfile         the profile of the user making the request.
     * @return an [[XmlImportSchemaBundleV1]] for validating the import.
     */
    def generateSchemasFromOntologies(
      internalOntologyIri: IRI,
      userProfile: UserADM
    ): Future[XmlImportSchemaBundleV1] = {

      /**
       * Called by the schema generation template to get the prefix label for an internal ontology
       * entity IRI. The schema generation template gets these IRIs from resource cardinalities
       * and property object class constraints, which we get from the ontology responder.
       *
       * @param internalEntityIri an internal ontology entity IRI.
       * @return the prefix label that Knora uses to refer to the ontology.
       */
      def getNamespacePrefixLabel(internalEntityIri: IRI): String = {
        val prefixLabel = internalEntityIri.toSmartIri.getLongPrefixLabel

        // If the schema generation template asks for the prefix label of something in knora-base, return
        // the prefix label of the Knora XML import v1 namespace instead.
        if (prefixLabel == OntologyConstants.KnoraBase.KnoraBaseOntologyLabel) {
          OntologyConstants.KnoraXmlImportV1.KnoraXmlImportNamespacePrefixLabel
        } else {
          prefixLabel
        }
      }

      /**
       * Called by the schema generation template to get the entity name (i.e. the local name part) of an
       * internal ontology entity IRI. The schema generation template gets these IRIs from resource cardinalities
       * and property object class constraints, which we get from the ontology responder.
       *
       * @param internalEntityIri an internal ontology entity IRI.
       * @return the local name of the entity.
       */
      def getEntityName(internalEntityIri: IRI): String =
        internalEntityIri.toSmartIri.getEntityName

      for {
        // Get a NamedGraphEntityInfoV1 for each ontology that we need to generate an XML schema for.
        namedGraphInfos: Map[IRI, NamedGraphEntityInfoV1] <- getNamedGraphInfos(
          mainOntologyIri = internalOntologyIri,
          userProfile = userProfile
        )

        // Get information about the resource classes and properties in each ontology.
        entityInfoResponseFutures: immutable.Iterable[Future[(IRI, EntityInfoGetResponseV1)]] = namedGraphInfos.map {
          case (ontologyIri: IRI, namedGraphInfo: NamedGraphEntityInfoV1) =>
            for {
              entityInfoResponse: EntityInfoGetResponseV1 <- (responderManager ? EntityInfoGetRequestV1(
                resourceClassIris = namedGraphInfo.resourceClasses,
                propertyIris = namedGraphInfo.propertyIris,
                userProfile = userProfile
              )).mapTo[EntityInfoGetResponseV1]
            } yield ontologyIri -> entityInfoResponse
        }

        // Sequence the futures of entity info responses.
        entityInfoResponses: immutable.Iterable[(IRI, EntityInfoGetResponseV1)] <- Future.sequence(
          entityInfoResponseFutures
        )

        // Make a Map of internal ontology IRIs to EntityInfoGetResponseV1 objects.
        entityInfoResponsesMap: Map[IRI, EntityInfoGetResponseV1] = entityInfoResponses.toMap

        // Collect all the property definitions in a single Map. Since any schema could use any property, we will
        // pass this Map to the schema generation template for every schema.
        propertyInfoMap: Map[IRI, PropertyInfoV1] = entityInfoResponsesMap.values.flatMap(_.propertyInfoMap).toMap

        // Make a map of internal ontology IRIs to XmlImportNamespaceInfoV1 objects describing the XML namespace
        // of each schema to be generated. Don't generate a schema for knora-base, because the built-in Knora
        // types are specified in the handwritten standard Knora XML import v1 schema.
        schemasToGenerate: Map[IRI, XmlImportNamespaceInfoV1] =
          (namedGraphInfos.keySet - OntologyConstants.KnoraBase.KnoraBaseOntologyIri).map { ontologyIri =>
            ontologyIri -> stringFormatter.internalOntologyIriToXmlNamespaceInfoV1(ontologyIri.toSmartIri)
          }.toMap

        // Make an XmlImportNamespaceInfoV1 for the standard Knora XML import v1 schema's namespace.
        knoraXmlImportSchemaNamespaceInfo: XmlImportNamespaceInfoV1 = XmlImportNamespaceInfoV1(
          namespace = OntologyConstants.KnoraXmlImportV1.KnoraXmlImportNamespaceV1,
          prefixLabel = OntologyConstants.KnoraXmlImportV1.KnoraXmlImportNamespacePrefixLabel
        )

        // Read the standard Knora XML import v1 schema from a file.
        knoraXmlImportSchemaXml: String = FileUtil.readTextResource(
          OntologyConstants.KnoraXmlImportV1.KnoraXmlImportNamespacePrefixLabel + ".xsd"
        )

        // Construct an XmlImportSchemaV1 for the standard Knora XML import v1 schema.
        knoraXmlImportSchema: XmlImportSchemaV1 = XmlImportSchemaV1(
          namespaceInfo = knoraXmlImportSchemaNamespaceInfo,
          schemaXml = knoraXmlImportSchemaXml
        )

        // Generate a schema for each project-specific ontology.
        generatedSchemas: Map[IRI, XmlImportSchemaV1] = schemasToGenerate.map { case (ontologyIri, namespaceInfo) =>
          // Each schema imports all the other generated schemas, plus the standard Knora XML import v1 schema.
          // Sort the imports to make schema generation deterministic.
          val importedNamespaceInfos: Seq[XmlImportNamespaceInfoV1] =
            (schemasToGenerate - ontologyIri).values.toVector.sortBy { importedNamespaceInfo =>
              importedNamespaceInfo.prefixLabel
            } :+ knoraXmlImportSchemaNamespaceInfo

          // Generate the schema using a Twirl template.
          val unformattedSchemaXml = org.knora.webapi.messages.twirl.xsd.v1.xml
            .xmlImport(
              targetNamespaceInfo = namespaceInfo,
              importedNamespaces = importedNamespaceInfos,
              knoraXmlImportNamespacePrefixLabel =
                OntologyConstants.KnoraXmlImportV1.KnoraXmlImportNamespacePrefixLabel,
              resourceClassInfoMap = entityInfoResponsesMap(ontologyIri).resourceClassInfoMap,
              propertyInfoMap = propertyInfoMap,
              getNamespacePrefixLabel = internalEntityIri => getNamespacePrefixLabel(internalEntityIri),
              getEntityName = internalEntityIri => getEntityName(internalEntityIri)
            )
            .toString()
            .trim

          // Parse the generated XML schema.
          val parsedSchemaXml =
            try {
              XML.loadString(unformattedSchemaXml)
            } catch {
              case parseEx: org.xml.sax.SAXParseException =>
                throw AssertionException(
                  s"Generated XML schema for namespace ${namespaceInfo.namespace} is not valid XML. Please report this as a bug.",
                  parseEx,
                  log
                )
            }

          // Format the generated XML schema nicely.
          val formattedSchemaXml = xmlPrettyPrinter.format(parsedSchemaXml)

          // Wrap it in an XmlImportSchemaV1 object along with its XML namespace information.
          val schema = XmlImportSchemaV1(
            namespaceInfo = namespaceInfo,
            schemaXml = formattedSchemaXml
          )

          namespaceInfo.namespace -> schema
        }

        // The schema bundle to be returned contains the generated schemas plus the standard Knora XML import v1 schema.
        allSchemasForBundle: Map[IRI, XmlImportSchemaV1] =
          generatedSchemas + (OntologyConstants.KnoraXmlImportV1.KnoraXmlImportNamespaceV1 -> knoraXmlImportSchema)
      } yield XmlImportSchemaBundleV1(
        mainNamespace = schemasToGenerate(internalOntologyIri).namespace,
        schemas = allSchemasForBundle
      )
    }

    /**
     * Generates a byte array representing a Zip file containing XML schemas for validating XML import data.
     *
     * @param internalOntologyIri the IRI of the main internal ontology for which data will be imported.
     * @param userProfile         the profile of the user making the request.
     * @return a byte array representing a Zip file containing XML schemas.
     */
    def generateSchemaZipFile(internalOntologyIri: IRI, userProfile: UserADM): Future[Array[Byte]] =
      for {
        // Generate a bundle of XML schemas.
        schemaBundle: XmlImportSchemaBundleV1 <- generateSchemasFromOntologies(
          internalOntologyIri = internalOntologyIri,
          userProfile = userProfile
        )

        // Generate the contents of the Zip file: a Map of file names to file contents (byte arrays).
        zipFileContents: Map[String, Array[Byte]] = schemaBundle.schemas.values.map { schema: XmlImportSchemaV1 =>
          val schemaFilename: String = schema.namespaceInfo.prefixLabel + ".xsd"
          val schemaXmlBytes: Array[Byte] = schema.schemaXml.getBytes(StandardCharsets.UTF_8)
          schemaFilename -> schemaXmlBytes
        }.toMap
      } yield FileUtil.createZipFileBytes(zipFileContents)

    /**
     * Validates bulk import XML using project-specific XML schemas and the Knora XML import schema v1.
     *
     * @param xml              the XML to be validated.
     * @param defaultNamespace the default namespace of the submitted XML. This should be the Knora XML import
     *                         namespace corresponding to the main internal ontology used in the import.
     * @param userADM          the profile of the user making the request.
     * @return a `Future` containing `()` if successful, otherwise a failed future.
     */
    def validateImportXml(xml: String, defaultNamespace: IRI, userADM: UserADM): Future[Unit] = {
      // Convert the default namespace of the submitted XML to an internal ontology IRI. This should be the
      // IRI of the main ontology used in the import.

      val mainOntologyIri: SmartIri = stringFormatter.xmlImportNamespaceToInternalOntologyIriV1(
        defaultNamespace,
        throw BadRequestException(s"Invalid XML import namespace: $defaultNamespace")
      )

      val validationFuture: Future[Unit] = for {
        // Generate a bundle of XML schemas for validating the submitted XML.
        schemaBundle: XmlImportSchemaBundleV1 <- generateSchemasFromOntologies(mainOntologyIri.toString, userADM)

        // Make a javax.xml.validation.SchemaFactory for instantiating XML schemas.
        schemaFactory: SchemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)

        // Tell the SchemaFactory to find additional schemas using our SchemaBundleResolver, which gets them
        // from the XmlImportSchemaBundleV1 we generated.
        _ = schemaFactory.setResourceResolver(new SchemaBundleResolver(schemaBundle))

        // Use the SchemaFactory to instantiate a javax.xml.validation.Schema representing the main schema in
        // the bundle.
        mainSchemaXml: String = schemaBundle.schemas(schemaBundle.mainNamespace).schemaXml
        schemaInstance: Schema = schemaFactory.newSchema(new StreamSource(new StringReader(mainSchemaXml)))

        // Validate the submitted XML using a validator based on the main schema.
        schemaValidator: Validator = schemaInstance.newValidator()
        _ = schemaValidator.validate(new StreamSource(new StringReader(xml)))
      } yield ()

      // If the XML fails schema validation, return a failed Future containing a BadRequestException.
      validationFuture.recover { case e @ (_: IllegalArgumentException | _: SAXException) =>
        throw BadRequestException(s"XML import did not pass XML schema validation: $e")
      }
    }

    /**
     * Converts parsed import XML into a sequence of [[CreateResourceFromXmlImportRequestV1]] for each resource
     * described in the XML.
     *
     * @param rootElement the root element of an XML document describing multiple resources to be created.
     * @return Seq[CreateResourceFromXmlImportRequestV1] a collection of resource creation requests.
     */
    def importXmlToCreateResourceRequests(rootElement: Elem): Seq[CreateResourceFromXmlImportRequestV1] = {
      rootElement.head.child
        .filter(node => node.label != "#PCDATA")
        .map { resourceNode =>
          // Get the client's unique ID for the resource.
          val clientIDForResource: String = (resourceNode \ "@id").toString

          // Get the optional resource creation date.
          val creationDate: Option[Instant] = resourceNode
            .attribute("creationDate")
            .map(creationDateNode =>
              stringFormatter.xsdDateTimeStampToInstant(
                creationDateNode.text,
                throw BadRequestException(s"Invalid resource creation date: ${creationDateNode.text}")
              )
            )

          // Convert the XML element's label and namespace to an internal resource class IRI.

          val elementNamespace: String = resourceNode.getNamespace(resourceNode.prefix)

          val restype_id = stringFormatter.xmlImportElementNameToInternalOntologyIriV1(
            namespace = elementNamespace,
            elementLabel = resourceNode.label,
            errorFun = throw BadRequestException(s"Invalid XML namespace: $elementNamespace")
          )

          // Get the child elements of the resource element.
          val childElements: Seq[Node] = resourceNode.child.filterNot(_.label == "#PCDATA")

          // The label must be the first child element of the resource element.
          val resourceLabel: String = childElements.headOption match {
            case Some(firstChildElem) => firstChildElem.text
            case None =>
              throw BadRequestException(
                s"Resource '$clientIDForResource' contains no ${OntologyConstants.KnoraXmlImportV1.KnoraXmlImportNamespacePrefixLabel}:label element"
              )
          }

          val childElementsAfterLabel = childElements.tail

          // Get the name of the resource's file, if any. This represents a file that in Sipi's temporary storage.
          // If provided, it must be the second child element of the resource element.
          val file: Option[String] = childElementsAfterLabel.headOption match {
            case Some(secondChildElem) =>
              if (secondChildElem.label == "file") {
                Some(secondChildElem.attribute("filename").get.text)
              } else {
                None
              }

            case None => None
          }

          // Any remaining child elements of the resource element represent property values.
          val propertyElements = if (file.isDefined) {
            childElementsAfterLabel.tail
          } else {
            childElementsAfterLabel
          }

          // Traverse the property value elements. This produces a sequence in which the same property IRI
          // can occur multiple times, once for each value.
          val propertiesWithValues: Seq[(IRI, CreateResourceValueV1)] = propertyElements.map { propertyNode =>
            // Is this a property from another ontology (in the form prefixLabel__localName)?
            val propertyIri = stringFormatter.toPropertyIriFromOtherOntologyInXmlImport(propertyNode.label) match {
              case Some(iri) =>
                // Yes. Use the corresponding entity IRI for it.
                iri

              case None =>
                // No. Convert the XML element's label and namespace to an internal property IRI.

                val propertyNodeNamespace = propertyNode.getNamespace(propertyNode.prefix)

                stringFormatter.xmlImportElementNameToInternalOntologyIriV1(
                  namespace = propertyNodeNamespace,
                  elementLabel = propertyNode.label,
                  errorFun = throw BadRequestException(s"Invalid XML namespace: $propertyNodeNamespace")
                )
            }

            // If the property element has one child element with a knoraType attribute, it's a link
            // property, otherwise it's an ordinary value property.

            val valueNodes: Seq[Node] = propertyNode.child.filterNot(_.label == "#PCDATA")

            if (valueNodes.size == 1 && valueNodes.head.attribute("knoraType").isDefined) {
              propertyIri -> knoraDataTypeXml(valueNodes.head)
            } else {
              propertyIri -> knoraDataTypeXml(propertyNode)
            }
          }

          // Group the values by property IRI.
          val groupedPropertiesWithValues: Map[IRI, Seq[CreateResourceValueV1]] = propertiesWithValues.groupBy {
            case (propertyIri: IRI, _) => propertyIri
          }.map { case (propertyIri: IRI, resultsForProperty: Seq[(IRI, CreateResourceValueV1)]) =>
            propertyIri -> resultsForProperty.map { case (_, propertyValue: CreateResourceValueV1) =>
              propertyValue
            }
          }

          CreateResourceFromXmlImportRequestV1(
            restype_id = restype_id,
            client_id = clientIDForResource,
            label = resourceLabel,
            properties = groupedPropertiesWithValues,
            file = file,
            creationDate = creationDate
          )
        }
        .toSeq
    }

    /**
     * Given an XML element representing a property value in an XML import, returns a [[CreateResourceValueV1]]
     * describing the value to be created.
     *
     * @param node the XML element.
     * @return a [[CreateResourceValueV1]] requesting the creation of the value described by the element.
     */
    def knoraDataTypeXml(node: Node): CreateResourceValueV1 = {
      val knoraType: Seq[Node] = node
        .attribute("knoraType")
        .getOrElse(throw BadRequestException(s"Attribute 'knoraType' missing in element '${node.label}'"))
      val elementValue = node.text

      if (knoraType.nonEmpty) {
        val language = node.attribute("lang").map(s => s.head.toString)
        knoraType.toString match {
          case "richtext_value" =>
            val maybeMappingID: Option[Seq[Node]] = node.attributes.get("mapping_id").map(_.toSeq)

            maybeMappingID match {
              case Some(mappingID) =>
                val mappingIri: Option[IRI] = Some(
                  stringFormatter.validateAndEscapeIri(
                    mappingID.toString,
                    throw BadRequestException(s"Invalid mapping ID in element '${node.label}: '$mappingID")
                  )
                )
                val childElements = node.child.filterNot(_.label == "#PCDATA")

                if (childElements.nonEmpty) {
                  val embeddedXmlRootNode = childElements.head
                  val embeddedXmlDoc = """<?xml version="1.0" encoding="UTF-8"?>""" + embeddedXmlRootNode.toString
                  CreateResourceValueV1(
                    richtext_value = Some(
                      CreateRichtextV1(
                        utf8str = None,
                        language = language,
                        xml = Some(embeddedXmlDoc),
                        mapping_id = mappingIri
                      )
                    )
                  )
                } else {
                  throw BadRequestException(
                    s"Element '${node.label}' provides a mapping_id, but its content is not XML"
                  )
                }

              case None =>
                // We don't escape the input string here, because it will be escaped by valuesToCreate().
                CreateResourceValueV1(
                  richtext_value = Some(CreateRichtextV1(utf8str = Some(elementValue), language = language))
                )
            }

          case "link_value" =>
            val linkType = node.attribute("linkType").get.headOption match {
              case Some(linkTypeNode: Node) => linkTypeNode.text
              case None                     => throw BadRequestException(s"Attribute 'linkType' missing in element '${node.label}'")
            }

            node.attribute("target").get.headOption match {
              case Some(targetNode: Node) =>
                val target = targetNode.text

                linkType match {
                  case "ref" => CreateResourceValueV1(link_to_client_id = Some(target))
                  case "iri" =>
                    CreateResourceValueV1(
                      link_value = Some(
                        stringFormatter.validateAndEscapeIri(
                          target,
                          throw BadRequestException(s"Invalid IRI in element '${node.label}': '$target'")
                        )
                      )
                    )
                  case other =>
                    throw BadRequestException(
                      s"Unrecognised value '$other' in attribute 'linkType' of element '${node.label}'"
                    )
                }

              case None => throw BadRequestException(s"Attribute 'ref' missing in element '${node.label}'")
            }

          case "int_value" =>
            CreateResourceValueV1(
              int_value = Some(
                stringFormatter.validateInt(
                  elementValue,
                  throw BadRequestException(s"Invalid integer value in element '${node.label}: '$elementValue'")
                )
              )
            )

          case "decimal_value" =>
            CreateResourceValueV1(
              decimal_value = Some(
                stringFormatter.validateBigDecimal(
                  elementValue,
                  throw BadRequestException(s"Invalid decimal value in element '${node.label}: '$elementValue'")
                )
              )
            )

          case "boolean_value" =>
            CreateResourceValueV1(
              boolean_value = Some(
                stringFormatter.validateBoolean(
                  elementValue,
                  throw BadRequestException(s"Invalid boolean value in element '${node.label}: '$elementValue'")
                )
              )
            )

          case "uri_value" =>
            CreateResourceValueV1(
              uri_value = Some(
                stringFormatter.validateAndEscapeIri(
                  elementValue,
                  throw BadRequestException(s"Invalid URI value in element '${node.label}: '$elementValue'")
                )
              )
            )

          case "date_value" =>
            CreateResourceValueV1(
              date_value = Some(
                stringFormatter.validateDate(
                  elementValue,
                  throw BadRequestException(s"Invalid date value in element '${node.label}: '$elementValue'")
                )
              )
            )

          case "color_value" =>
            CreateResourceValueV1(
              color_value = Some(
                stringFormatter.validateColor(
                  elementValue,
                  throw BadRequestException(s"Invalid date value in element '${node.label}: '$elementValue'")
                )
              )
            )

          case "geom_value" =>
            CreateResourceValueV1(
              geom_value = Some(
                stringFormatter.validateGeometryString(
                  elementValue,
                  throw BadRequestException(s"Invalid geometry value in element '${node.label}: '$elementValue'")
                )
              )
            )

          case "hlist_value" =>
            CreateResourceValueV1(
              hlist_value = Some(
                stringFormatter.validateAndEscapeIri(
                  elementValue,
                  throw BadRequestException(s"Invalid hlist value in element '${node.label}: '$elementValue'")
                )
              )
            )

          case "interval_value" =>
            Try(elementValue.split(",")) match {
              case Success(timeVals) =>
                if (timeVals.length != 2)
                  throw BadRequestException(s"Invalid interval value in element '${node.label}: '$elementValue'")

                val tVals: Seq[BigDecimal] = timeVals.map { timeVal =>
                  stringFormatter.validateBigDecimal(
                    timeVal,
                    throw BadRequestException(s"Invalid decimal value in element '${node.label}: '$timeVal'")
                  )
                }

                CreateResourceValueV1(interval_value = Some(tVals))

              case Failure(_) =>
                throw BadRequestException(s"Invalid interval value in element '${node.label}: '$elementValue'")
            }

          case "time_value" =>
            val timeStamp: Instant = stringFormatter.xsdDateTimeStampToInstant(
              elementValue,
              throw BadRequestException(s"Invalid timestamp in element '${node.label}': $elementValue")
            )
            CreateResourceValueV1(time_value = Some(timeStamp.toString))

          case "geoname_value" =>
            CreateResourceValueV1(geoname_value = Some(elementValue))
          case other => throw BadRequestException(s"Invalid 'knoraType' in element '${node.label}': '$other'")
        }
      } else {
        throw BadRequestException(s"Attribute 'knoraType' missing in element '${node.label}'")
      }
    }

    path("v1" / "resources") {
      get {
        // search for resources matching the given search string (searchstr) and return their Iris.
        requestContext =>
          val requestMessage = for {
            userProfile <- getUserADM(
              requestContext = requestContext,
              featureFactoryConfig = featureFactoryConfig
            )
            params = requestContext.request.uri.query().toMap
            searchstr = params.getOrElse("searchstr", throw BadRequestException(s"required param searchstr is missing"))

            // default -1 means: no restriction at all
            restype = params.getOrElse("restype_id", "-1")

            numprops = params.getOrElse("numprops", "1")
            limit = params.getOrElse("limit", "11")

            // input validation

            searchString = stringFormatter.toSparqlEncodedString(
              searchstr,
              throw BadRequestException(s"Invalid search string: '$searchstr'")
            )

            resourceTypeIri: Option[IRI] = restype match {
              case "-1" => None
              case restype: IRI =>
                Some(
                  stringFormatter.validateAndEscapeIri(
                    restype,
                    throw BadRequestException(s"Invalid param restype: $restype")
                  )
                )
            }

            numberOfProps: Int = stringFormatter.validateInt(
              numprops,
              throw BadRequestException(s"Invalid param numprops: $numprops")
            ) match {
              case number: Int => if (number < 1) 1 else number // numberOfProps must not be smaller than 1
            }

            limitOfResults = stringFormatter.validateInt(
              limit,
              throw BadRequestException(s"Invalid param limit: $limit")
            )

          } yield makeResourceSearchRequestMessage(
            searchString = searchString,
            resourceTypeIri = resourceTypeIri,
            numberOfProps = numberOfProps,
            limitOfResults = limitOfResults,
            userProfile = userProfile
          )

          RouteUtilV1.runJsonRouteWithFuture(
            requestMessageF = requestMessage,
            requestContext = requestContext,
            settings = settings,
            responderManager = responderManager,
            log = log
          )
      } ~ post {
        // Create a new resource with the given type and possibly a file.
        // The binary file is already managed by Sipi.
        // For further details, please read the docs: Sipi -> Interaction Between Sipi and Knora.
        entity(as[CreateResourceApiRequestV1]) { apiRequest => requestContext =>
          val requestMessageFuture = for {
            userProfile <- getUserADM(
              requestContext = requestContext,
              featureFactoryConfig = featureFactoryConfig
            )
            request <- makeCreateResourceRequestMessage(
              apiRequest = apiRequest,
              featureFactoryConfig = featureFactoryConfig,
              userADM = userProfile
            )
          } yield request

          RouteUtilV1.runJsonRouteWithFuture(
            requestMessageF = requestMessageFuture,
            requestContext = requestContext,
            settings = settings,
            responderManager = responderManager,
            log = log
          )
        }
      }
    } ~ path("v1" / "resources" / Segment) { resIri =>
      get {
        parameters("reqtype".?, "resinfo".as[Boolean].?) { (reqtypeParam, resinfoParam) => requestContext =>
          val requestMessage =
            for {
              userADM <- getUserADM(
                requestContext = requestContext,
                featureFactoryConfig = featureFactoryConfig
              )
              requestType = reqtypeParam.getOrElse("")
              resinfo = resinfoParam.getOrElse(false)
            } yield makeResourceRequestMessage(
              resIri = resIri,
              resinfo = resinfo,
              requestType = requestType,
              userADM = userADM
            )

          RouteUtilV1.runJsonRouteWithFuture(
            requestMessageF = requestMessage,
            requestContext = requestContext,
            settings = settings,
            responderManager = responderManager,
            log = log
          )
        }
      } ~ delete {
        parameters("deleteComment".?) { deleteCommentParam => requestContext =>
          val requestMessage = for {
            userADM <- getUserADM(
              requestContext = requestContext,
              featureFactoryConfig = featureFactoryConfig
            )
          } yield makeResourceDeleteMessage(resIri = resIri, deleteComment = deleteCommentParam, userADM = userADM)

          RouteUtilV1.runJsonRouteWithFuture(
            requestMessageF = requestMessage,
            requestContext = requestContext,
            settings = settings,
            responderManager = responderManager,
            log = log
          )
        }
      }
    } ~ path("v1" / "resources.html" / Segment) { iri =>
      get { requestContext =>
        val params = requestContext.request.uri.query().toMap
        val requestType = params.getOrElse("reqtype", "")

        val requestMessage = requestType match {
          case "properties" =>
            for {
              userADM <- getUserADM(
                requestContext = requestContext,
                featureFactoryConfig = featureFactoryConfig
              )
              resIri = stringFormatter.validateAndEscapeIri(
                iri,
                throw BadRequestException(s"Invalid param resource IRI: $iri")
              )
            } yield ResourceFullGetRequestV1(
              iri = resIri,
              featureFactoryConfig = featureFactoryConfig,
              userADM = userADM
            )
          case other => throw BadRequestException(s"Invalid request type: $other")
        }

        RouteUtilV1.runHtmlRoute[ResourcesResponderRequestV1, ResourceFullResponseV1](
          requestMessageF = requestMessage,
          viewHandler = ResourceHtmlView.propertiesHtmlView,
          requestContext = requestContext,
          settings = settings,
          responderManager = responderManager,
          log = log
        )
      }
    } ~ path("v1" / "properties" / Segment) { iri =>
      get { requestContext =>
        val requestMessage = for {
          userADM <- getUserADM(
            requestContext = requestContext,
            featureFactoryConfig = featureFactoryConfig
          )
          resIri = stringFormatter.validateAndEscapeIri(
            iri,
            throw BadRequestException(s"Invalid param resource IRI: $iri")
          )
        } yield makeGetPropertiesRequestMessage(resIri, userADM)

        RouteUtilV1.runJsonRouteWithFuture(
          requestMessageF = requestMessage,
          requestContext = requestContext,
          settings = settings,
          responderManager = responderManager,
          log = log
        )

      }
    } ~ path("v1" / "resources" / "label" / Segment) { iri =>
      put {
        entity(as[ChangeResourceLabelApiRequestV1]) { apiRequest => requestContext =>
          val requestMessage = for {
            userADM <- getUserADM(
              requestContext = requestContext,
              featureFactoryConfig = featureFactoryConfig
            )
            resIri = stringFormatter.validateAndEscapeIri(
              iri,
              throw BadRequestException(s"Invalid param resource IRI: $iri")
            )
            label = stringFormatter.toSparqlEncodedString(
              apiRequest.label,
              throw BadRequestException(s"Invalid label: '${apiRequest.label}'")
            )
          } yield ChangeResourceLabelRequestV1(
            resourceIri = resIri,
            label = label,
            apiRequestID = UUID.randomUUID,
            featureFactoryConfig = featureFactoryConfig,
            userADM = userADM
          )

          RouteUtilV1.runJsonRouteWithFuture(
            requestMessageF = requestMessage,
            requestContext = requestContext,
            settings = settings,
            responderManager = responderManager,
            log = log
          )
        }
      }
    } ~ path("v1" / "graphdata" / Segment) { iri =>
      get {
        parameters("depth".as[Int].?) { depth => requestContext =>
          val requestMessage = for {
            userADM <- getUserADM(
              requestContext = requestContext,
              featureFactoryConfig = featureFactoryConfig
            )
            resourceIri = stringFormatter.validateAndEscapeIri(
              iri,
              throw BadRequestException(s"Invalid param resource IRI: $iri")
            )
          } yield GraphDataGetRequestV1(resourceIri, depth.getOrElse(4), userADM)

          RouteUtilV1.runJsonRouteWithFuture(
            requestMessageF = requestMessage,
            requestContext = requestContext,
            settings = settings,
            responderManager = responderManager,
            log = log
          )
        }
      }

    } ~ path("v1" / "error" / Segment) { errorType =>
      get { requestContext =>
        val msg = if (errorType == "unitMsg") {
          UnexpectedMessageRequest()
        } else if (errorType == "iseMsg") {
          InternalServerExceptionMessageRequest()
        } else {
          InternalServerExceptionMessageRequest()
        }

        RouteUtilV1.runJsonRoute(
          requestMessage = msg,
          requestContext = requestContext,
          settings = settings,
          responderManager = responderManager,
          log = log
        )
      }
    } ~ path("v1" / "resources" / "xmlimport" / Segment) { projectId =>
      post {
        entity(as[String]) { xml => requestContext =>
          val requestMessage = for {
            userADM <- getUserADM(
              requestContext = requestContext,
              featureFactoryConfig = featureFactoryConfig
            )

            _ = if (userADM.isAnonymousUser) {
              throw ForbiddenException(
                "You are not logged in, and only a system administrator or project administrator can perform a bulk import"
              )
            }

            _ = if (!(userADM.permissions.isSystemAdmin || userADM.permissions.isProjectAdmin(projectId))) {
              throw ForbiddenException(
                s"You are logged in as ${userADM.email}, but only a system administrator or project administrator can perform a bulk import"
              )
            }

            // Parse the submitted XML.
            rootElement: Elem = XML.loadString(xml)

            // Make sure that the root element is knoraXmlImport:resources.
            _ = if (rootElement.namespace + rootElement.label != OntologyConstants.KnoraXmlImportV1.Resources) {
              throw BadRequestException(s"Root XML element must be ${OntologyConstants.KnoraXmlImportV1.Resources}")
            }

            // Get the default namespace of the submitted XML. This should be the Knora XML import
            // namespace corresponding to the main internal ontology used in the import.
            defaultNamespace = rootElement.getNamespace(null)

            // Validate the XML using XML schemas.
            _ <- validateImportXml(
              xml = xml,
              defaultNamespace = defaultNamespace,
              userADM = userADM
            )

            // Make a CreateResourceFromXmlImportRequestV1 for each resource to be created.
            resourcesToCreate: Seq[CreateResourceFromXmlImportRequestV1] = importXmlToCreateResourceRequests(
              rootElement
            )

            // Make a MultipleResourceCreateRequestV1 for the creation of all the resources.
            apiRequestID: UUID = UUID.randomUUID

            updateRequest: MultipleResourceCreateRequestV1 <- makeMultiResourcesRequestMessage(
              resourceRequest = resourcesToCreate,
              projectId = projectId,
              apiRequestID = apiRequestID,
              featureFactoryConfig = featureFactoryConfig,
              userProfile = userADM
            )
          } yield updateRequest

          RouteUtilV1.runJsonRouteWithFuture(
            requestMessageF = requestMessage,
            requestContext = requestContext,
            settings = settings,
            responderManager = responderManager,
            log = log
          )(timeout = settings.triplestoreUpdateTimeout, executionContext = executionContext)
        }
      }
    } ~ path("v1" / "resources" / "xmlimportschemas" / Segment) { internalOntologyIri =>
      get {
        // Get the prefix label of the specified internal ontology.
        val internalOntologySmartIri: SmartIri = internalOntologyIri.toSmartIriWithErr(
          throw BadRequestException(s"Invalid internal project-specific ontology IRI: $internalOntologyIri")
        )

        if (!internalOntologySmartIri.isKnoraOntologyIri || internalOntologySmartIri.isKnoraBuiltInDefinitionIri) {
          throw BadRequestException(s"Invalid internal project-specific ontology IRI: $internalOntologyIri")
        }

        val internalOntologyPrefixLabel: String = internalOntologySmartIri.getLongPrefixLabel

        // Respond with a Content-Disposition header specifying the filename of the generated Zip file.
        respondWithHeader(
          `Content-Disposition`(
            ContentDispositionTypes.attachment,
            Map("filename" -> (internalOntologyPrefixLabel + "-xml-schemas.zip"))
          )
        ) { requestContext =>
          val httpResponseFuture: Future[HttpResponse] = for {
            userProfile <- getUserADM(
              requestContext = requestContext,
              featureFactoryConfig = featureFactoryConfig
            )
            schemaZipFileBytes: Array[Byte] <- generateSchemaZipFile(
              internalOntologyIri = internalOntologyIri,
              userProfile = userProfile
            )
          } yield HttpResponse(
            status = StatusCodes.OK,
            entity = HttpEntity(bytes = schemaZipFileBytes)
          )

          requestContext.complete(httpResponseFuture)
        }
      }
    }
  }

  /**
   * Represents an XML import schema corresponding to an ontology.
   *
   * @param namespaceInfo information about the schema's namespace.
   * @param schemaXml     the XML text of the schema.
   */
  case class XmlImportSchemaV1(namespaceInfo: XmlImportNamespaceInfoV1, schemaXml: String)

  /**
   * Represents a bundle of XML import schemas corresponding to ontologies.
   *
   * @param mainNamespace the XML namespace corresponding to the main ontology to be used in the XML import.
   * @param schemas       a map of XML namespaces to schemas.
   */
  case class XmlImportSchemaBundleV1(mainNamespace: IRI, schemas: Map[IRI, XmlImportSchemaV1])

  /**
   * An implementation of [[LSResourceResolver]] that resolves resources from a [[XmlImportSchemaBundleV1]].
   * This is used to allow the XML schema validator to load additional schemas during XML import data validation.
   *
   * @param schemaBundle an [[XmlImportSchemaBundleV1]].
   */
  class SchemaBundleResolver(schemaBundle: XmlImportSchemaBundleV1) extends LSResourceResolver {
    private val contents: Map[IRI, Array[Byte]] = schemaBundle.schemas.map { case (namespace, schema) =>
      namespace -> schema.schemaXml.getBytes(StandardCharsets.UTF_8)
    }

    private class ByteArrayLSInput(content: Array[Byte]) extends LSInput {
      override def getSystemId: String = null

      override def setEncoding(encoding: String): Unit = ()

      override def getCertifiedText: Boolean = false

      override def setStringData(stringData: String): Unit = ()

      override def setPublicId(publicId: String): Unit = ()

      override def getByteStream: InputStream = new ByteArrayInputStream(content)

      override def getEncoding: String = null

      override def setCharacterStream(characterStream: Reader): Unit = ()

      override def setByteStream(byteStream: InputStream): Unit = ()

      override def getBaseURI: String = null

      override def setCertifiedText(certifiedText: Boolean): Unit = ()

      override def getStringData: String = null

      override def getCharacterStream: Reader = null

      override def getPublicId: String = null

      override def setBaseURI(baseURI: String): Unit = ()

      override def setSystemId(systemId: String): Unit = ()
    }

    override def resolveResource(
      `type`: String,
      namespaceURI: String,
      publicId: String,
      systemId: String,
      baseURI: String
    ): LSInput =
      new ByteArrayLSInput(contents(namespaceURI))
  }

}
