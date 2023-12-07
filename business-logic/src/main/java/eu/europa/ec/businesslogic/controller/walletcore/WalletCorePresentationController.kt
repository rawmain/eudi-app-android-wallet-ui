/*
 * Copyright (c) 2023 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

package eu.europa.ec.businesslogic.controller.walletcore

import android.content.Context
import androidx.activity.ComponentActivity
import eu.europa.ec.businesslogic.di.WalletPresentationScope
import eu.europa.ec.businesslogic.extension.safeAsync
import eu.europa.ec.businesslogic.util.EudiWalletListenerWrapper
import eu.europa.ec.eudi.iso18013.transfer.DisclosedDocuments
import eu.europa.ec.eudi.iso18013.transfer.RequestDocument
import eu.europa.ec.eudi.iso18013.transfer.ResponseResult
import eu.europa.ec.eudi.iso18013.transfer.engagement.NfcEngagementService
import eu.europa.ec.eudi.wallet.EudiWallet
import eu.europa.ec.eudi.wallet.EudiWalletConfig
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.zip
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped

sealed class TransferEventPartialState {
    data object Connected : TransferEventPartialState()
    data object Connecting : TransferEventPartialState()
    data object Disconnected : TransferEventPartialState()
    data class Error(val error: String) : TransferEventPartialState()
    data class QrEngagementReady(val qrCode: String) : TransferEventPartialState()
    data class RequestReceived(
        val requestData: List<RequestDocument>,
        val verifierName: String?
    ) :
        TransferEventPartialState()

    data object ResponseSent : TransferEventPartialState()
}

sealed class SendRequestedDocumentsPartialState {
    data class Failure(val error: String) : SendRequestedDocumentsPartialState()
    data object UserAuthenticationRequired : SendRequestedDocumentsPartialState()
    data object RequestSent : SendRequestedDocumentsPartialState()
}

sealed class ResponseReceivedPartialState {
    data object Success : ResponseReceivedPartialState()
    data class Failure(val error: String) : ResponseReceivedPartialState()
}

sealed class WalletCoreProximityPartialState {
    data object UserAuthenticationRequired : WalletCoreProximityPartialState()
    data class Failure(val error: String) : WalletCoreProximityPartialState()
    data object Success : WalletCoreProximityPartialState()
}

sealed class LoadSampleDataPartialState {
    data object Success : LoadSampleDataPartialState()
    data class Failure(val error: String) : LoadSampleDataPartialState()
}

/**
 * Common scoped interactor that has all the complexity and required interaction with the EudiWallet Core.
 * */
interface WalletCorePresentationController {
    /**
     * On initialization it adds the core listener and remove it when scope is canceled.
     * When the scope is canceled so does the presentation
     *
     * @return Hot Flow that emits the Core's status callback.
     * */
    val events: Flow<TransferEventPartialState>

    /**
     * User selection data for request step
     * */
    val disclosedDocuments: DisclosedDocuments?

    /**
     * Verifier name so it can be retrieve across screens
     * */
    val verifierName: String?

    /**
     * Terminates the presentation and kills the coroutine scope that [events] live in
     * */
    fun stopPresentation()

    /**
     * Starts QR engagement. This will trigger [events] emission.
     *
     * [TransferEventPartialState.QrEngagementReady] -> QR String to show QR
     *
     * [TransferEventPartialState.Connecting] -> Connecting
     *
     * [TransferEventPartialState.Connected] -> Connected. We can proceed to the next screen
     * */
    fun startQrEngagement()

    /**
     * Enable/Disable NFC service
     * */
    fun toggleNfcEngagement(componentActivity: ComponentActivity, toggle: Boolean)

    /**
     * Transform UI models to Domain and create -> sent the request.
     *
     * @return Flow that emits the creation state. On Success send the request.
     * The response of that request is emitted through [events]
     *  */
    fun sendRequestedDocuments(): Flow<SendRequestedDocumentsPartialState>

    /**
     * Updates the UI model
     * @param items User updated data through UI Events
     * */
    fun updateRequestedDocuments(disclosedDocuments: DisclosedDocuments?)

    /**
     * @return flow that maps the state from [events] emission to what we consider as success state
     * */
    fun mappedCallbackStateFlow(): Flow<ResponseReceivedPartialState>

    /**
     * The main observation point for collecting state for the Request flow.
     * Exposes a single flow for two operations([sendRequestedDocuments] - [mappedCallbackStateFlow])
     * and a single state
     * @return flow that emits the create, sent, receive states
     * */
    fun observeSentDocumentsRequest(): Flow<WalletCoreProximityPartialState>

    companion object {
        /**
         * Initialize wallet core
         * @param applicationContext Application Context
         * @param config Config for Eudi Wallet Core
         * */
        fun initializeWalletCore(
            applicationContext: Context,
            config: EudiWalletConfig = EudiWalletConfig.Builder(applicationContext).build()
        ) {
            EudiWallet.init(applicationContext, config)
        }
    }
}

@Scope(WalletPresentationScope::class)
@Scoped
class WalletCorePresentationControllerImpl(
    private val eudiWallet: EudiWallet,
    private val resourceProvider: ResourceProvider,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WalletCorePresentationController {

    private val genericErrorMessage = resourceProvider.genericErrorMessage()

    private val coroutineScope = CoroutineScope(dispatcher + SupervisorJob())

    override var disclosedDocuments: DisclosedDocuments? = null
        private set
    override var verifierName: String? = null
        private set

    override val events = callbackFlow {
        val eventListenerWrapper = EudiWalletListenerWrapper(
            onQrEngagementReady = { qrCode ->
                trySendBlocking(
                    TransferEventPartialState.QrEngagementReady(qrCode = qrCode)
                )
            },
            onConnected = {
                trySendBlocking(
                    TransferEventPartialState.Connected
                )
            },
            onConnecting = {
                trySendBlocking(
                    TransferEventPartialState.Connecting
                )
            },
            onDisconnected = {
                trySendBlocking(
                    TransferEventPartialState.Disconnected
                )
            },
            onError = { errorMessage ->
                trySendBlocking(
                    TransferEventPartialState.Error(error = errorMessage)
                )
            },
            onRequestReceived = { requestDocuments ->
                verifierName =
                    requestDocuments.firstOrNull()?.docRequest?.readerAuth?.readerCommonName
                trySendBlocking(
                    TransferEventPartialState.RequestReceived(
                        requestData = requestDocuments,
                        verifierName = verifierName
                    )
                )
            },
            onResponseSent = {
                trySendBlocking(
                    TransferEventPartialState.ResponseSent
                )
            }
        )

        eudiWallet.addTransferEventListener(eventListenerWrapper)
        awaitClose {
            eudiWallet.removeTransferEventListener(eventListenerWrapper)
            eudiWallet.stopPresentation()
        }
    }.shareIn(coroutineScope, SharingStarted.Lazily, 1)
        .safeAsync {
            TransferEventPartialState.Error(
                error = it.localizedMessage ?: resourceProvider.genericErrorMessage()
            )
        }

    override fun startQrEngagement() {
        eudiWallet.startQrEngagement()
    }

    override fun toggleNfcEngagement(componentActivity: ComponentActivity, toggle: Boolean) {
        if (toggle) {
            NfcEngagementService.enable(componentActivity)
        } else {
            NfcEngagementService.disable(componentActivity)
        }
    }

    override fun sendRequestedDocuments() =
        flow {
            disclosedDocuments?.let { documents ->
                when (val response = eudiWallet.createResponse(documents)) {
                    is ResponseResult.Failure -> {
                        emit(SendRequestedDocumentsPartialState.Failure(resourceProvider.genericErrorMessage()))
                    }

                    is ResponseResult.Response -> {
                        val responseBytes = response.bytes
                        eudiWallet.sendResponse(responseBytes)
                        emit(SendRequestedDocumentsPartialState.RequestSent)
                    }

                    is ResponseResult.UserAuthRequired -> {
                        emit(SendRequestedDocumentsPartialState.UserAuthenticationRequired)
                    }
                }
            }
        }

    override fun mappedCallbackStateFlow(): Flow<ResponseReceivedPartialState> {
        return events.mapNotNull { response ->
            when (response) {

                // This state should be fixed by Scytales. Right now verifier sends this error for success
                is TransferEventPartialState.Error -> {
                    if (response.error == "Peer disconnected without proper session termination") {
                        ResponseReceivedPartialState.Success
                    } else {
                        ResponseReceivedPartialState.Failure(error = response.error)
                    }
                }

                else -> null
            }
        }.safeAsync {
            ResponseReceivedPartialState.Failure(
                error = it.localizedMessage ?: genericErrorMessage
            )
        }
    }

    override fun observeSentDocumentsRequest(): Flow<WalletCoreProximityPartialState> =
        sendRequestedDocuments().zip(mappedCallbackStateFlow()) { createResponseState, sentResponseState ->
            when {
                createResponseState is SendRequestedDocumentsPartialState.Failure -> {
                    WalletCoreProximityPartialState.Failure(createResponseState.error)
                }

                createResponseState is SendRequestedDocumentsPartialState.UserAuthenticationRequired -> {
                    WalletCoreProximityPartialState.UserAuthenticationRequired
                }

                sentResponseState is ResponseReceivedPartialState.Failure -> {
                    WalletCoreProximityPartialState.Failure(sentResponseState.error)
                }

                createResponseState is SendRequestedDocumentsPartialState.RequestSent &&
                        sentResponseState !is ResponseReceivedPartialState.Success -> {
                    null
                }

                else -> {
                    WalletCoreProximityPartialState.Success
                }
            }
        }.filterNotNull()
            .safeAsync {
                WalletCoreProximityPartialState.Failure(it.localizedMessage ?: genericErrorMessage)
            }

    override fun updateRequestedDocuments(disclosedDocuments: DisclosedDocuments?) {
        this.disclosedDocuments = disclosedDocuments
    }

    override fun stopPresentation() {
        eudiWallet.stopPresentation()
        coroutineScope.cancel()
    }


}