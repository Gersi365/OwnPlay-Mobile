package app.ownplay.player.ui.tv

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * TV focus is communicated by color only.
 *
 * The indication never draws an outline or changes measured geometry, so moving focus does not make
 * navigation items or channel rows appear to change shape. Component-specific selected states can
 * layer their own stable tint on top of this indication.
 */
internal class TvRemoteIndication(
    private val focusColor: Color,
    private val pressedColor: Color,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        TvRemoteIndicationNode(
            interactionSource = interactionSource,
            focusColor = focusColor,
            pressedColor = pressedColor,
        )

    override fun equals(other: Any?): Boolean =
        other is TvRemoteIndication &&
            other.focusColor == focusColor &&
            other.pressedColor == pressedColor

    override fun hashCode(): Int = 31 * focusColor.hashCode() + pressedColor.hashCode()
}

private class TvRemoteIndicationNode(
    private val interactionSource: InteractionSource,
    private val focusColor: Color,
    private val pressedColor: Color,
) : Modifier.Node(), DrawModifierNode {
    private var focusCount = 0
    private var pressCount = 0

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focusCount += 1
                    is FocusInteraction.Unfocus -> focusCount = (focusCount - 1).coerceAtLeast(0)
                    is PressInteraction.Press -> pressCount += 1
                    is PressInteraction.Release -> pressCount = (pressCount - 1).coerceAtLeast(0)
                    is PressInteraction.Cancel -> pressCount = (pressCount - 1).coerceAtLeast(0)
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()

        if (pressCount > 0) {
            drawRoundRect(
                color = pressedColor,
                alpha = 0.12f,
                cornerRadius = CornerRadius(10.dp.toPx()),
            )
        }

        if (focusCount > 0) {
            drawRoundRect(
                color = focusColor,
                alpha = 0.20f,
                cornerRadius = CornerRadius(10.dp.toPx()),
            )
        }
    }
}
