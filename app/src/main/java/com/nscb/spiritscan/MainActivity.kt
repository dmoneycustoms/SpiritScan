package com.nscb.spiritscan

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nscb.spiritscan.ui.LiveHud
import com.nscb.spiritscan.ui.SpiritTheme

class MainActivity : ComponentActivity() {
    private val ask = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val needed = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).filter {
            ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ask.launch(needed.toTypedArray())
        }

        setContent {
            val vm: ScanViewModel = viewModel()
            SpiritTheme {
                LiveHud(vm)
            }
        }
    }
}
