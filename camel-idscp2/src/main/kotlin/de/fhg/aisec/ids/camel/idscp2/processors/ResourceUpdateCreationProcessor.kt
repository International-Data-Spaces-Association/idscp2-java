/*-
 * ========================LICENSE_START=================================
 * camel-idscp2
 * %%
 * Copyright (C) 2021 Fraunhofer AISEC
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package de.fhg.aisec.ids.camel.idscp2.processors

import de.fhg.aisec.ids.camel.idscp2.Constants
import de.fhg.aisec.ids.camel.idscp2.Constants.IDSCP2_HEADER
import de.fhg.aisec.ids.camel.idscp2.ProviderDB
import de.fhg.aisec.ids.camel.idscp2.Utils
import de.fhg.aisec.ids.camel.idscp2.Utils.SERIALIZER
import de.fraunhofer.iais.eis.ResourceUpdateMessageBuilder
import org.apache.camel.Exchange
import org.apache.camel.Processor
import org.slf4j.LoggerFactory
import java.net.URI

class ResourceUpdateCreationProcessor : Processor {

    override fun process(exchange: Exchange) {
        if (LOG.isDebugEnabled) {
            LOG.debug("[IN] ${this::class.java.simpleName}")
        }

        val artifactUri = exchange.getProperty(Constants.ARTIFACT_URI_PROPERTY)?.let {
            if (it is URI) {
                it
            } else {
                URI.create(it.toString())
            }
        }

        val usedContract = ProviderDB.artifactUrisMapped2ContractAgreements[artifactUri]
            ?: throw RuntimeException("No UC contract found for resource/artifact $artifactUri")
        if (LOG.isDebugEnabled) {
            LOG.debug("Contract for requested Artifact found {}", usedContract)
        }

        ResourceUpdateMessageBuilder().run {
            Utils.initMessageBuilder(this)
            _affectedResource_(artifactUri)
            _transferContract_(usedContract)
            build().let {
                if (LOG.isDebugEnabled) {
                    LOG.debug("Serialisation header: {}", SERIALIZER.serialize(it))
                }
                exchange.message.setHeader(IDSCP2_HEADER, it)
            }
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ResourceUpdateCreationProcessor::class.java)
    }
}
