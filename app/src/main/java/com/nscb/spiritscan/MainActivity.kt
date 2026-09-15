package com.nscb.spiritscan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nscb.spiritscan.ui.LiveHud
import com.nscb.spiritscan.ui.SpiritTheme

class MainActivity : ComponentActivity() {
    private val ask = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) ask.launch(need.toTypedArray())
        setContent {
            val vm: ScanViewModel = viewModel()
            val out by vm.output.collectAsState()
            SpiritTheme {
                LiveHud(
                    output = out,
                    boxOn = vm.boxOn.collectAsState().value,
                    walking = vm.walking.collectAsState().value,
                    sweep = vm.sweep.collectAsState().value,
                    onArm = { vm.arm(this) },
                    onCal = { vm.calibrate() },
                    onBox = { vm.toggleBox() },
                    onWalk = { vm.toggleWalk() },
                    onSweep = { vm.setSweep(it) },
                    onReset = { vm.resetSurvey() },
                )
            }
        }
    }
}
