package dev.securenotes.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Apply outside the scrolling modifier so the indicator stays in the viewport, above its content. */
@Composable
internal fun Modifier.verticalScrollbar(state: ScrollableState): Modifier = scrollbar(state, vertical = true)

@Composable
internal fun Modifier.horizontalScrollbar(state: ScrollableState): Modifier = scrollbar(state, vertical = false)

/** A visual indicator only: scrolling, text selection, and note-reordering gestures keep their own input. */
@Composable
private fun Modifier.scrollbar(state: ScrollableState, vertical: Boolean): Modifier {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)
    return drawWithContent {
        drawContent()
        if (!state.canScrollBackward && !state.canScrollForward) return@drawWithContent
        val indicator = state.scrollIndicatorState ?: return@drawWithContent
        val content = indicator.contentSize
        val viewport = indicator.viewportSize
        val offset = indicator.scrollOffset
        if (content == Int.MAX_VALUE || viewport == Int.MAX_VALUE || offset == Int.MAX_VALUE ||
            viewport <= 0 || content <= viewport) return@drawWithContent

        val inset = 4.dp.toPx()
        val thickness = 3.dp.toPx()
        val track = (if (vertical) size.height else size.width) - 2 * inset
        if (track <= 0f) return@drawWithContent
        val thumb = (track * viewport.toFloat() / content).coerceIn(minOf(24.dp.toPx(), track), track)
        val fraction = when {
            !state.canScrollBackward -> 0f
            !state.canScrollForward -> 1f
            else -> (offset.toFloat() / (content.toFloat() - viewport)).coerceIn(0f, 1f)
        }
        val rtl = layoutDirection == LayoutDirection.Rtl
        val position = inset + (track - thumb) * if (!vertical && rtl) 1f - fraction else fraction
        val origin = if (vertical) Offset(if (rtl) inset else size.width - inset - thickness, position)
            else Offset(position, size.height - inset - thickness)
        drawRoundRect(color, origin, if (vertical) Size(thickness, thumb) else Size(thumb, thickness),
            CornerRadius(thickness / 2))
    }
}
