package com.wearos.ancsbridge.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.lifecycle.lifecycleScope
import com.wearos.ancsbridge.ancs.AncsService
import com.wearos.ancsbridge.ui.theme.AncsBridgeTheme
import com.wearos.ancsbridge.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.startService()
            checkHealthConnectSleepPermission()
        }
    }

    private val sleepPermission = HealthPermission.getReadPermission(SleepSessionRecord::class)
    private val spo2Permission = HealthPermission.getReadPermission(OxygenSaturationRecord::class)

    private val hcPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.contains(sleepPermission) || granted.contains(spo2Permission)) {
            val intent = Intent(AncsService.ACTION_HC_SLEEP_GRANTED).apply { setPackage(packageName) }
            sendBroadcast(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissionsIfNeeded()

        setContent {
            AncsBridgeTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.BODY_SENSORS_BACKGROUND,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            viewModel.startService()
            checkHealthConnectSleepPermission()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    /**
     * Health Connect is needed for sleep + SpO2 (low-frequency daily metrics that
     * Health Services does not expose). If available and not yet granted, request
     * it; otherwise signal the service to start the reader if already granted.
     */
    private fun checkHealthConnectSleepPermission() {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) return
        val client = HealthConnectClient.getOrCreate(this)
        lifecycleScope.launch {
            val granted = client.permissionController.getGrantedPermissions()
            val wanted = setOf(sleepPermission, spo2Permission)
            if (wanted.any { it in granted }) {
                sendBroadcast(
                    Intent(AncsService.ACTION_HC_SLEEP_GRANTED).apply { setPackage(packageName) }
                )
            } else {
                hcPermissionLauncher.launch(wanted)
            }
        }
    }
}
