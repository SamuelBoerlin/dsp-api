/*
 * Copyright © 2021 Data and Service Center for the Humanities and/or DaSCH Service Platform contributors.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.knora.webapi.routing.admin.permissions

import java.util.UUID

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatcher, Route}

import io.swagger.annotations._
import javax.ws.rs.Path
import org.knora.webapi.feature.FeatureFactoryConfig
import org.knora.webapi.messages.admin.responder.permissionsmessages._
import org.knora.webapi.routing.{Authenticator, KnoraRoute, KnoraRouteData, RouteUtilADM}

object DeletePermissionRouteADM {
  val PermissionsBasePath: PathMatcher[Unit] = PathMatcher("admin" / "permissions")
}

@Api(value = "permissions", produces = "application/json")
@Path("/admin/permissions")
class DeletePermissionRouteADM(routeData: KnoraRouteData)
    extends KnoraRoute(routeData)
    with Authenticator
    with PermissionsADMJsonProtocol {

  import DeletePermissionRouteADM._

  /**
   * Returns the route.
   */
  override def makeRoute(featureFactoryConfig: FeatureFactoryConfig): Route =
    deletePermission(featureFactoryConfig)

  /**
   * Delete a permission
   */
  private def deletePermission(featureFactoryConfig: FeatureFactoryConfig): Route =
    path(PermissionsBasePath / Segment) { iri =>
      delete { requestContext =>
        val requestMessage = for {
          requestingUser <- getUserADM(
            requestContext = requestContext,
            featureFactoryConfig = featureFactoryConfig
          )
        } yield PermissionDeleteRequestADM(
          permissionIri = iri,
          requestingUser = requestingUser,
          apiRequestID = UUID.randomUUID()
        )

        RouteUtilADM.runJsonRoute(
          requestMessageF = requestMessage,
          requestContext = requestContext,
          featureFactoryConfig = featureFactoryConfig,
          settings = settings,
          responderManager = responderManager,
          log = log
        )
      }
    }
}
