package com.nscb.spiritscan.orchestrator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nscb.spiritscan.ScanViewModel
import com.nscb.spiritscan.camera.CameraPipeline
import com.nscb.spiritscan.camera.FrameCollector
import com.nscb.spiritscan.ui.LiveHud

/**
 * Top-level orchestrator composable.
 * Hosts the live camera pipeline (with optional frame analysis)
 * and the full LiveHud UI driven by ScanViewModel.
 */
@Composable
fun NSCBOrchestrator(viewModel: ScanViewModel) {
    val context = LocalContext.current
    val frameCollector = remember { FrameCollector(viewModel) }

    Box(Modifier.fillMaxSize()) {
        // Live camera + frame analysis runs underneath the HUD
        CameraPipeline(
            frameCollector = frameCollector,
            modifier = Modifier.fillMaxSize()
        )

        // Full instrument UI on top
        LiveHud(viewModel)
    }
}
