package com.wata.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.wata.ui.theme.WataColors
import com.wata.ui.theme.WataSpacing

/**
 * A surface that responds to D-pad focus and selection.
 *
 * Provides visual feedback when focused (orange border + highlighted background)
 * and handles Enter/Center key presses as clicks.
 *
 * @param onClick Called when the surface is clicked or Enter/Center is pressed
 * @param modifier Modifier to apply to the surface
 * @param backgroundColor Background color when not focused
 * @param focusedBackgroundColor Background color when focused
 * @param focusRequester Optional FocusRequester for programmatic focus control
 * @param content Content to display inside the surface
 */
@Composable
fun FocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = WataColors.bgSecondary,
    focusedBackgroundColor: Color = WataColors.bgHighlight,
    focusRequester: FocusRequester? = null,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val actualFocusRequester = focusRequester ?: remember { FocusRequester() }

    Box(
        modifier = modifier
            .then(
                if (isFocused) {
                    Modifier
                        .border(2.dp, WataColors.focus)
                        .background(focusedBackgroundColor)
                } else {
                    Modifier
                        .border(2.dp, Color.Transparent)
                        .background(backgroundColor)
                }
            )
            .focusRequester(actualFocusRequester)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyUp) {
                    when (keyEvent.key) {
                        Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                            onClick()
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null // We handle visual feedback via focus state
            ) {
                onClick()
            }
            .padding(WataSpacing.md)
    ) {
        content()
    }
}
