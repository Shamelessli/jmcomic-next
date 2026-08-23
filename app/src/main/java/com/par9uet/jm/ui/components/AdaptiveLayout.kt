package com.par9uet.jm.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keeps scrollable dialog content inside the usable height on landscape tablets. */
@Composable
fun adaptiveDialogMaxHeight(
    max: Dp = 420.dp,
    reserved: Dp = 220.dp,
): Dp {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    return minOf(max, (screenHeight - reserved).coerceAtLeast(180.dp))
}
