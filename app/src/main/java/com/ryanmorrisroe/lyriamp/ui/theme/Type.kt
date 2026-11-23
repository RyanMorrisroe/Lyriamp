package com.ryanmorrisroe.lyriamp.ui.theme

import androidx.compose.material3.Typography as MaterialTypography
import androidx.compose.ui.text.TextStyle

private val baseTypography = MaterialTypography()

private fun TextStyle.tightened(): TextStyle = copy(
    fontSize = fontSize * 0.94f,
    lineHeight = lineHeight * 0.94f,
    letterSpacing = letterSpacing * 0.8f
)

val Typography = baseTypography.copy(
    displayLarge = baseTypography.displayLarge.tightened(),
    displayMedium = baseTypography.displayMedium.tightened(),
    displaySmall = baseTypography.displaySmall.tightened(),
    headlineLarge = baseTypography.headlineLarge.tightened(),
    headlineMedium = baseTypography.headlineMedium.tightened(),
    headlineSmall = baseTypography.headlineSmall.tightened(),
    titleLarge = baseTypography.titleLarge.tightened(),
    titleMedium = baseTypography.titleMedium.tightened(),
    titleSmall = baseTypography.titleSmall.tightened(),
    bodyLarge = baseTypography.bodyLarge.tightened(),
    bodyMedium = baseTypography.bodyMedium.tightened(),
    bodySmall = baseTypography.bodySmall.tightened(),
    labelLarge = baseTypography.labelLarge.tightened(),
    labelMedium = baseTypography.labelMedium.tightened(),
    labelSmall = baseTypography.labelSmall.tightened()
)
