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

package eu.europa.ec.uilogic.config

import eu.europa.ec.uilogic.navigation.Screen

data class ConfigNavigation(
    val navigationType: NavigationType,
    val screenToNavigate: Screen,
    val arguments: Map<String, String> = emptyMap(),
    val flags: Int = 0,
    val indicateFlowCompletion: FlowCompletion = FlowCompletion.NONE
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ConfigNavigation

        if (navigationType != other.navigationType) return false
        if (screenToNavigate.screenRoute != other.screenToNavigate.screenRoute) return false
        if (arguments != other.arguments) return false
        if (flags != other.flags) return false
        return indicateFlowCompletion == other.indicateFlowCompletion
    }

    override fun hashCode(): Int {
        var result = navigationType.hashCode()
        result = 31 * result + screenToNavigate.screenRoute.hashCode()
        result = 31 * result + arguments.hashCode()
        result = 31 * result + flags.hashCode()
        result = 31 * result + indicateFlowCompletion.hashCode()
        return result
    }
}

enum class NavigationType {
    POP,
    PUSH,
    DEEPLINK
}

enum class FlowCompletion {
    CANCEL,
    SUCCESS,
    NONE
}