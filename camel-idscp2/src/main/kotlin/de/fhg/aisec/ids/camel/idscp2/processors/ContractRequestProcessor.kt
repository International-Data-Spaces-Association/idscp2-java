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

import de.fhg.aisec.ids.camel.idscp2.Constants.CONTAINER_URI_PROPERTY
import de.fhg.aisec.ids.camel.idscp2.Constants.IDSCP2_HEADER
import de.fhg.aisec.ids.camel.idscp2.Utils
import de.fhg.aisec.ids.camel.idscp2.Utils.SERIALIZER
import de.fraunhofer.iais.eis.BinaryOperator
import de.fraunhofer.iais.eis.Constraint
import de.fraunhofer.iais.eis.ConstraintBuilder
import de.fraunhofer.iais.eis.ContractOfferBuilder
import de.fraunhofer.iais.eis.ContractRequest
import de.fraunhofer.iais.eis.ContractRequestMessage
import de.fraunhofer.iais.eis.ContractResponseMessageBuilder
import de.fraunhofer.iais.eis.LeftOperand
import de.fraunhofer.iais.eis.Permission
import de.fraunhofer.iais.eis.PermissionBuilder
import org.apache.camel.Exchange
import org.apache.camel.Processor
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * This Processor handles a ContractRequestMessage and creates a ContractResponseMessage.
 */
class ContractRequestProcessor : Processor {

    override fun process(exchange: Exchange) {
        if (LOG.isDebugEnabled) {
            LOG.debug("[IN] ${this::class.java.simpleName}")
        }

        val contractRequest = SERIALIZER.deserialize(
            exchange.message.getBody(String::class.java),
            ContractRequest::class.java
        )
        val requestedArtifact = contractRequest.permission[0].target

        val contractRequestMessage = exchange.message.getHeader(
            IDSCP2_HEADER, ContractRequestMessage::class.java
        )

        val contractResponseMessageBuilder = ContractResponseMessageBuilder()
        Utils.initMessageBuilder(contractResponseMessageBuilder)
        contractResponseMessageBuilder._correlationMessage_(contractRequestMessage.id)
        contractResponseMessageBuilder.build().let {
            if (LOG.isDebugEnabled) {
                LOG.debug("Serialization header: {}", SERIALIZER.serialize(it))
            }
            exchange.message.setHeader(IDSCP2_HEADER, it)
        }

        // create ContractOffer, allowing use of received data in the given container only
        val containerUri = exchange.getProperty(CONTAINER_URI_PROPERTY).let {
            if (it is URI) {
                it
            } else {
                URI.create(it.toString())
            }
        }
        val contractOffer = ContractOfferBuilder()
            ._permission_(
                ArrayList<Permission>().also { pl ->
                    pl += PermissionBuilder()
                        ._target_(requestedArtifact)
                        ._constraint_(
                            ArrayList<Constraint>().also { cl ->
                                cl += ConstraintBuilder()
                                    ._leftOperand_(LeftOperand.SYSTEM)
                                    ._operator_(BinaryOperator.SAME_AS)
                                    ._rightOperandReference_(containerUri)
                                    .build()
                            }
                        )
                        .build()
                }
            )
            .build()

        SERIALIZER.serialize(contractOffer).let {
            if (LOG.isDebugEnabled) {
                LOG.debug("ContractOffer ID: {}", contractOffer.id)
                LOG.debug("Serialisation body: {}", it)
            }
            exchange.message.body = it
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ContractRequestProcessor::class.java)
    }
}
