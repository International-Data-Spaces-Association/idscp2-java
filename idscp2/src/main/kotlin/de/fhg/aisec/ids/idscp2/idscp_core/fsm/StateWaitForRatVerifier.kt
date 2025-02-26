/*-
 * ========================LICENSE_START=================================
 * idscp2
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
package de.fhg.aisec.ids.idscp2.idscp_core.fsm

import de.fhg.aisec.ids.idscp2.idscp_core.fsm.FSM.FsmState
import de.fhg.aisec.ids.idscp2.idscp_core.messages.Idscp2MessageHelper
import de.fhg.aisec.ids.idscp2.messages.IDSCP2.IdscpClose.CloseCause
import de.fhg.aisec.ids.idscp2.messages.IDSCP2.IdscpMessage
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * The Wait_For_Rat_Verifier State of the FSM of the IDSCP2 protocol.
 * Waits only for the RatVerifier Result to decide whether the connection will be established
 *
 * @author Leon Beckmann (leon.beckmann@aisec.fraunhofer.de)
 */
class StateWaitForRatVerifier(
    fsm: FSM,
    ratTimer: StaticTimer,
    handshakeTimer: StaticTimer,
    verifierHandshakeTimer: StaticTimer,
    ackTimer: StaticTimer
) : State() {
    override fun runEntryCode(fsm: FSM) {
        if (LOG.isTraceEnabled) {
            LOG.trace("Switched to state STATE_WAIT_FOR_RAT_VERIFIER")
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(StateWaitForRatVerifier::class.java)
    }

    init {

        /*---------------------------------------------------
         * STATE_WAIT_FOR_RAT_VERIFIER - Transition Description
         * ---------------------------------------------------
         * onICM: error ---> {stop RAT_VERIFIER} ---> STATE_CLOSED
         * onICM: close ---> {send IDSCP_CLOSE, stop RAT_VERIFIER} ---> STATE_CLOSED
         * onICM: dat_timeout ---> {send IDSCP_DAT_EXPIRED, cancel ratV} ---> STATE_WAIT_FOR_DAT_AND_RAT_VERIFIER
         * onICM: timeout ---> {send IDSCP_CLOSE, stop RAT_VERIFIER} ---> STATE_CLOSED
         * onICM: rat_verifier_ok ---> {set rat timeout} ---> STATE_ESTABLISHED / STATE_WAIT_FOR_ACK
         * onICM: rat_verifier_failed ---> {send IDSCP_CLOSE} ---> STATE_CLOSED
         * onICM: rat_verifier_msg ---> {send IDSCP_RAT_VERIFIER} ---> STATE_WAIT_FOR_RAT_VERIFIER
         * onMessage: IDSCP_ACK ---> {cancel Ack flag} ---> STATE_WAIT_FOR_RAT
         * onMessage: IDSCP_DAT_EXPIRED ---> {send IDSCP_DAT, start RAT_PROVER} ---> STATE_WAIT_FOR_RAT
         * onMessage: IDSCP_CLOSE ---> {stop RAT_VERIFIER} ---> STATE_CLOSED
         * onMessage: IDSCP_RAT_PROVER ---> {delegat to RAT_VERIFIER} ---> STATE_WAIT_FOR_RAT_VERIFIER
         * onMessage: IDSCP_RE_RAT ---> {start RAT_PROVER} ---> STATE_WAIT_FOR_RAT
         * ALL_OTHER_MESSAGES ---> {} ---> STATE_WAIT_FOR_RAT_VERIFIER
         * --------------------------------------------------- */
        addTransition(
            InternalControlMessage.IDSCP_STOP.value,
            Transition {
                if (LOG.isTraceEnabled) {
                    LOG.trace("Send IDSC_CLOSE")
                }
                fsm.sendFromFSM(
                    Idscp2MessageHelper.createIdscpCloseMessage(
                        "User close",
                        CloseCause.USER_SHUTDOWN
                    )
                )
                FSM.FsmResult(FSM.FsmResultCode.OK, fsm.getState(FsmState.STATE_CLOSED))
            }
        )

        addTransition(
            InternalControlMessage.ERROR.value,
            Transition {
                LOG.warn("An internal control error occurred")
                FSM.FsmResult(FSM.FsmResultCode.OK, fsm.getState(FsmState.STATE_CLOSED))
            }
        )

        addTransition(
            InternalControlMessage.REPEAT_RAT.value,
            Transition {
                // already re-attestating
                FSM.FsmResult(FSM.FsmResultCode.OK, this)
            }
        )

        addTransition(
            InternalControlMessage.SEND_DATA.value,
            Transition {
                FSM.FsmResult(FSM.FsmResultCode.NOT_CONNECTED, this)
            }
        )

        addTransition(
            InternalControlMessage.TIMEOUT.value,
            Transition {
                LOG.warn("Handshake timeout occurred. Send IDSCP_CLOSE")
                fsm.sendFromFSM(Idscp2MessageHelper.createIdscpCloseMessage("Handshake timeout", CloseCause.TIMEOUT))
                FSM.FsmResult(FSM.FsmResultCode.OK, fsm.getState(FsmState.STATE_CLOSED))
            }
        )

        addTransition(
            InternalControlMessage.DAT_TIMER_EXPIRED.value,
            Transition {
                if (LOG.isDebugEnabled) {
                    LOG.debug("DAT expired, request new DAT from peer and trigger a re-attestation")
                }
                if (LOG.isTraceEnabled) {
                    LOG.trace("Send IDSCP_DAT_EXPIRED")
                }
                fsm.stopRatVerifierDriver()
                if (!fsm.sendFromFSM(Idscp2MessageHelper.createIdscpDatExpiredMessage())) {
                    LOG.warn("Cannot send DatExpired message")
                    return@Transition FSM.FsmResult(FSM.FsmResultCode.IO_ERROR, fsm.getState(FsmState.STATE_CLOSED))
                }
                if (LOG.isTraceEnabled) {
                    LOG.trace("Start Handshake Timer")
                }
                handshakeTimer.resetTimeout()
                FSM.FsmResult(FSM.FsmResultCode.OK, fsm.getState(FsmState.STATE_WAIT_FOR_DAT_AND_RAT_VERIFIER))
            }
        )

        addTransition(
            InternalControlMessage.RAT_VERIFIER_OK.value,
            Transition {
                if (LOG.isTraceEnabled) {
                    LOG.trace("Received RAT_VERIFIER OK. Start RAT Timer")
                }
                verifierHandshakeTimer.cancelTimeout()
                ratTimer.resetTimeout()
                if (fsm.ackFlag) {
                    ackTimer.start()
                    return@Transition FSM.FsmResult(FSM.FsmResultCode.OK, fsm.getState(FsmState.STATE_WAIT_FOR_ACK))
                } else {
                    return@Transition FSM.FsmResult(FSM.FsmResultCode.OK, fsm.getState(FsmState.STATE_ESTABLISHED))
                }
            }
        )

        addTransition(
            InternalControlMessage.RAT_VERIFIER_FAILED.value,
            Transition {
                LOG.warn("RAT_VERIFIER failed. Send IDSCP_CLOSE")
                fsm.sendFromFSM(
                    Idscp2MessageHelper.createIdscpCloseMessage(
                        "RAT_VERIFIER failed",
                        CloseCause.RAT_VERIFIER_FAILED
                    )
                )
                FSM.FsmResult(FSM.FsmResultCode.OK, fsm.getState(FsmState.STATE_CLOSED))
            }
        )

        addTransition(
            InternalControlMessage.RAT_VERIFIER_MSG.value,
            Transition { event: Event ->
                if (LOG.isTraceEnabled) {
                    LOG.trace("Send IDSCP_RAT_VERIFIER")
                }
                if (!fsm.sendFromFSM(event.idscpMessage)) {
                    LOG.warn("Cannot send rat verifier message")
                    return@Transition FSM.FsmResult(FSM.FsmResultCode.IO_ERROR, fsm.getState(FsmState.STATE_CLOSED))
                }
                FSM.FsmResult(FSM.FsmResultCode.OK, this)
            }
        )

        addTransition(
            IdscpMessage.IDSCPCLOSE_FIELD_NUMBER,
            Transition {
                if (LOG.isTraceEnabled) {
                    LOG.trace("Received IDSCP_CLOSE")
                }
                FSM.FsmResult(FSM.FsmResultCode.OK, fsm.getState(FsmState.STATE_CLOSED))
            }
        )

        addTransition(
            IdscpMessage.IDSCPDATEXPIRED_FIELD_NUMBER,
            Transition {
                if (LOG.isDebugEnabled) {
                    LOG.debug("Peer is requesting a new DAT, followed by a re-attestation")
                }
                if (!fsm.sendFromFSM(Idscp2MessageHelper.createIdscpDatMessage(fsm.getDynamicAttributeToken))) {
                    LOG.warn("Cannot send DAT message")
                    return@Transition FSM.FsmResult(FSM.FsmResultCode.IO_ERROR, fsm.getState(FsmState.STATE_CLOSED))
                }
                if (!fsm.restartRatProverDriver()) {
                    LOG.warn("Cannot run Rat prover, close idscp connection")
                    return@Transition FSM.FsmResult(FSM.FsmResultCode.RAT_ERROR, fsm.getState(FsmState.STATE_CLOSED))
                }
                FSM.FsmResult(FSM.FsmResultCode.OK, fsm.getState(FsmState.STATE_WAIT_FOR_RAT))
            }
        )

        addTransition(
            IdscpMessage.IDSCPRATPROVER_FIELD_NUMBER,
            Transition { event: Event ->
                if (LOG.isTraceEnabled) {
                    LOG.trace("Delegate received IDSCP_RAT_PROVER to RAT_VERIFIER")
                }

                if (!event.idscpMessage.hasIdscpRatProver()) {
                    // this should never happen
                    LOG.warn("IDSCP_RAT_PROVER Message not available")
                    return@Transition FSM.FsmResult(FSM.FsmResultCode.RAT_ERROR, fsm.getState(FsmState.STATE_CLOSED))
                }

                fsm.ratVerifierDriver?.let {
                    // Run in async fire-and-forget coroutine to avoid cycles caused by protocol misuse
                    GlobalScope.launch {
                        it.delegate(event.idscpMessage.idscpRatProver.data.toByteArray())
                    }
                } ?: run {
                    LOG.warn("RatProverDriver not available")
                    return@Transition FSM.FsmResult(FSM.FsmResultCode.RAT_ERROR, fsm.getState(FsmState.STATE_CLOSED))
                }

                FSM.FsmResult(FSM.FsmResultCode.OK, this)
            }
        )

        addTransition(
            IdscpMessage.IDSCPRERAT_FIELD_NUMBER,
            Transition {
                if (LOG.isDebugEnabled) {
                    LOG.debug("Peer is requesting a re-attestation")
                }
                if (!fsm.restartRatProverDriver()) {
                    LOG.warn("Cannot run Rat prover, close idscp connection")
                    return@Transition FSM.FsmResult(FSM.FsmResultCode.RAT_ERROR, fsm.getState(FsmState.STATE_CLOSED))
                }
                FSM.FsmResult(FSM.FsmResultCode.OK, fsm.getState(FsmState.STATE_WAIT_FOR_RAT))
            }
        )

        addTransition(
            IdscpMessage.IDSCPACK_FIELD_NUMBER,
            Transition {
                fsm.recvAck(it.idscpMessage.idscpAck)
                FSM.FsmResult(FSM.FsmResultCode.OK, this)
            }
        )

        setNoTransitionHandler {
            if (LOG.isTraceEnabled) {
                LOG.trace("No transition available for given event $it")
                LOG.trace("Stay in state STATE_WAIT_FOR_RAT_VERIFIER")
            }
            FSM.FsmResult(FSM.FsmResultCode.UNKNOWN_TRANSITION, this)
        }
    }
}
