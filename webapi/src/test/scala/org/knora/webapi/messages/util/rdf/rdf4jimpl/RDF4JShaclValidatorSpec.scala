/*
 * Copyright © 2022 Swiss National Data and Service Center for the Humanities and/or DaSCH Service Platform contributors.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.knora.webapi.util.rdf.rdf4jimpl

import org.knora.webapi.feature.{FeatureToggle, ToggleStateOff}
import org.knora.webapi.util.rdf.ShaclValidatorSpec

/**
 * Tests [[org.knora.webapi.messages.util.rdf.ShaclValidator]] using the RDF4J API.
 */
class RDF4JShaclValidatorSpec extends ShaclValidatorSpec(FeatureToggle("jena-rdf-library", ToggleStateOff))
