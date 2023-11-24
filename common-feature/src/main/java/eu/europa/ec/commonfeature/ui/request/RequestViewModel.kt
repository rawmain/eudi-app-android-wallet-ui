/*
 *
 *  * Copyright (c) 2023 European Commission
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package eu.europa.ec.commonfeature.ui.request

import eu.europa.ec.commonfeature.di.getPresentationScope
import eu.europa.ec.commonfeature.ui.request.model.RequestDataUi
import eu.europa.ec.uilogic.component.content.ContentErrorConfig
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.Job

data class State(
    val isLoading: Boolean = true,
    val isShowingFullUserInfo: Boolean = false,
    val error: ContentErrorConfig? = null,
    val isBottomSheetOpen: Boolean = false,
    val sheetContent: RequestBottomSheetContent = RequestBottomSheetContent.SUBTITLE,

    val verifierName: String? = null,
    val screenTitle: String,
    val screenSubtitle: String,
    val screenClickableSubtitle: String?,
    val warningText: String,

    val items: List<RequestDataUi<Event>> = emptyList(),
) : ViewState

sealed class Event : ViewEvent {
    data object DoWork : Event()
    data object DismissError : Event()
    data object GoBack : Event()
    data object ChangeContentVisibility : Event()
    data class ExpandOrCollapseRequiredDataList(val id: Int) : Event()
    data class UserIdentificationClicked(val itemId: String) : Event()

    data object SubtitleClicked : Event()
    data object PrimaryButtonPressed : Event()
    data object SecondaryButtonPressed : Event()

    sealed class BottomSheet : Event() {
        data class UpdateBottomSheetState(val isOpen: Boolean) : BottomSheet()

        sealed class Cancel : BottomSheet() {
            data object PrimaryButtonPressed : Cancel()
            data object SecondaryButtonPressed : Cancel()
        }

        sealed class Subtitle : BottomSheet() {
            data object PrimaryButtonPressed : Subtitle()
        }
    }
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data class SwitchScreen(
            val screenRoute: String
        ) : Navigation()

        data object Pop : Navigation()
    }

    data object ShowBottomSheet : Effect()
    data object CloseBottomSheet : Effect()
}

enum class RequestBottomSheetContent {
    SUBTITLE, CANCEL
}

abstract class RequestViewModel : MviViewModel<Event, State, Effect>() {
    protected var viewModelJob: Job? = null

    abstract fun getScreenSubtitle(): String
    abstract fun getScreenClickableSubtitle(): String?
    abstract fun getWarningText(): String
    abstract fun getNextScreen(): String
    abstract fun doWork()

    /**
     * Called during [NavigationType.POP].
     *
     * Kill presentation scope.
     *
     * Therefore kill [EudiWalletInteractor]
     * */
    open fun cleanUp() {
        getPresentationScope().close()
    }

    open fun updateData(updatedItems: List<RequestDataUi<Event>>) {
        setState {
            copy(items = updatedItems)
        }
    }

    override fun setInitialState(): State {
        return State(
            screenTitle = "",
            screenSubtitle = getScreenSubtitle(),
            screenClickableSubtitle = getScreenClickableSubtitle(),
            warningText = getWarningText(),
            error = null,
            verifierName = null
        )
    }

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.DoWork -> doWork()

            is Event.DismissError -> {
                setState {
                    copy(error = null)
                }
            }

            is Event.GoBack -> {
                setState {
                    copy(error = null)
                }
                doNavigation(NavigationType.POP)
            }

            is Event.ChangeContentVisibility -> {
                setState {
                    copy(isShowingFullUserInfo = !isShowingFullUserInfo)
                }
            }

            is Event.ExpandOrCollapseRequiredDataList -> {
                expandOrCollapseRequiredDataList(id = event.id)
            }

            is Event.UserIdentificationClicked -> {
                updateUserIdentificationItem(id = event.itemId)
            }

            is Event.SubtitleClicked -> {
                showBottomSheet(sheetContent = RequestBottomSheetContent.SUBTITLE)
            }

            is Event.PrimaryButtonPressed -> {
                doNavigation(NavigationType.PUSH)
            }

            is Event.SecondaryButtonPressed -> {
                showBottomSheet(sheetContent = RequestBottomSheetContent.CANCEL)
            }

            is Event.BottomSheet.UpdateBottomSheetState -> {
                setState {
                    copy(isBottomSheetOpen = event.isOpen)
                }
            }

            is Event.BottomSheet.Cancel.PrimaryButtonPressed -> {
                hideBottomSheet()
            }

            is Event.BottomSheet.Cancel.SecondaryButtonPressed -> {
                hideBottomSheet()
                doNavigation(NavigationType.POP)
            }

            is Event.BottomSheet.Subtitle.PrimaryButtonPressed -> {
                hideBottomSheet()
            }
        }
    }

    private fun doNavigation(navigationType: NavigationType) {
        when (navigationType) {
            NavigationType.PUSH -> {
                unsubscribe()
                setEffect { Effect.Navigation.SwitchScreen(getNextScreen()) }
            }

            NavigationType.POP -> {
                unsubscribe()
                cleanUp()
                setEffect { Effect.Navigation.Pop }
            }

            NavigationType.DEEPLINK -> {}
        }
    }

    private fun expandOrCollapseRequiredDataList(id: Int) {
        val items = viewState.value.items
        val updatedItems = items.map { item ->
            if (item is RequestDataUi.RequiredFields
                && id == item.requiredFieldsItemUi.id
            ) {
                item.copy(
                    requiredFieldsItemUi = item.requiredFieldsItemUi
                        .copy(expanded = !item.requiredFieldsItemUi.expanded)
                )
            } else {
                item
            }
        }
        updateData(updatedItems)
    }

    private fun updateUserIdentificationItem(id: String) {
        val items: List<RequestDataUi<Event>> = viewState.value.items
        val updatedList = items.map { item ->
            if (item is RequestDataUi.OptionalField
                && id == item.optionalFieldItemUi.requestDocumentItemUi.id
            ) {
                val itemCurrentCheckedState = item.optionalFieldItemUi.requestDocumentItemUi.checked
                val updatedUiItem = item.optionalFieldItemUi.requestDocumentItemUi.copy(
                    checked = !itemCurrentCheckedState
                )
                item.copy(
                    optionalFieldItemUi = item.optionalFieldItemUi
                        .copy(requestDocumentItemUi = updatedUiItem)
                )
            } else {
                item
            }
        }
        updateData(updatedList)
    }

    private fun showBottomSheet(sheetContent: RequestBottomSheetContent) {
        setState {
            copy(sheetContent = sheetContent)
        }
        setEffect {
            Effect.ShowBottomSheet
        }
    }

    private fun hideBottomSheet() {
        setEffect {
            Effect.CloseBottomSheet
        }
    }

    private fun unsubscribe() {
        viewModelJob?.cancel()
    }
}