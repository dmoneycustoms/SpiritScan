package com.nscb.spiritscan.orchestrator

import androidx.compose.runtime.Composable
import com.nscb.spiritscan.ScanViewModel
import com.nscb.spiritscan.ui.SpiritTheme

/**
 * Application root composable.
 * Applies the dark Spirit theme and hands control to NSCBOrchestrator.
 */
@Composable
fun SpiritScanApp(viewModel: ScanViewModel) {
    SpiritTheme {
        NSCBOrchestrator(viewModel)
    }
}
