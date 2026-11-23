package com.ryanmorrisroe.lyriamp.ui.glyph

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

@Composable
fun BackArrowIcon(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(
                color = color,
                start = Offset(x1 * unit, y1 * unit),
                end = Offset(x2 * unit, y2 * unit),
                strokeWidth = 2f * unit,
                cap = StrokeCap.Round
            )
        }

        line(19f, 12f, 5f, 12f)
        line(5f, 12f, 11f, 6f)
        line(5f, 12f, 11f, 18f)
    }
}
