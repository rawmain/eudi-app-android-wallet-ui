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

package eu.europa.ec.uilogic.component.utils

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

enum class TopSpacing {
    WithToolbar, WithoutToolbar
}

fun screenPaddings(
    append: PaddingValues? = null,
    topSpacing: TopSpacing = TopSpacing.WithToolbar
) = PaddingValues(
    start = SPACING_MEDIUM.dp,
    top = calculateTopSpacing(topSpacing).dp + (append?.calculateTopPadding() ?: 0.dp),
    end = SPACING_MEDIUM.dp,
    bottom = SPACING_MEDIUM.dp + (append?.calculateBottomPadding() ?: 0.dp)
)

private fun calculateTopSpacing(topSpacing: TopSpacing): Int = when (topSpacing) {
    TopSpacing.WithToolbar -> SPACING_SMALL
    TopSpacing.WithoutToolbar -> SPACING_EXTRA_LARGE
}