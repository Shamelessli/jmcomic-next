package com.par9uet.jm.ui.components

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.dp

fun adaptiveComicGridCells(columns: Int = 0): GridCells {
    return if (columns > 0) {
        GridCells.Fixed(columns)
    } else {
        GridCells.Adaptive(minSize = 118.dp)
    }
}
