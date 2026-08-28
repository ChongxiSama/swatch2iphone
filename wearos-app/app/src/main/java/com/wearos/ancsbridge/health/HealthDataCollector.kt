package com.wearos.ancsbridge.health

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import com.wearos.ancsbridge.ble.AncsConstants
import com.wearos.ancsbridge.ble.HealthGattServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Collects health & fitness data from Wear OS Health Services and forwards
 * updates to the GATT server for the iPhone to read.
 *
 * Uses PassiveMonitoringClient for background metrics (heart rate, steps,
 * calories, distance). SpO2 is not exposed by Health Services passive
 * monitoring, so it is read from Health Connect instead (see HealthConnectSleepReader).
 */
class HealthDataCollector(
    private val context: Context,
    private val gattServer: HealthGattServer
) {
    private val TAG = "HealthDataCollector"
    private val client = HealthServices.getClient(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val supportedTypes = setOf(
        DataType.HEART_RATE_BPM,
        DataType.STEPS_TOTAL,
        DataType.CALORIES_TOTAL,
        DataType.DISTANCE_TOTAL
    )

    fun start() {
        scope.launch {
            try {
                val passive = client.passiveMonitoringClient
                val caps = passive.getCapabilitiesAsync().await()
                val wanted = supportedTypes.intersect(caps.supportedDataTypesPassiveMonitoring)
                val config = PassiveListenerConfig.builder()
                    .setDataTypes(wanted)
                    .build()
                passive.setPassiveListenerCallback(config, passiveCallback)
                Log.i(TAG, "Passive monitoring started for $wanted")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start passive monitoring: ${e.message}")
            }
        }
    }

    fun stop() {
        scope.launch {
            try {
                client.passiveMonitoringClient.clearPassiveListenerCallbackAsync().await()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private val passiveCallback = object : androidx.health.services.client.PassiveListenerCallback {
        override fun onNewDataPointsReceived(update: DataPointContainer) {
            update.getData(DataType.HEART_RATE_BPM).firstOrNull()?.let { dp ->
                val bpm = dp.value.roundToInt()
                HealthRepository.patch {
                    it.copy(heartRate = bpm, lastUpdateEpochMs = System.currentTimeMillis())
                }
                gattServer.notify(AncsConstants.HEART_RATE_UUID)
            }
            update.getData(DataType.STEPS_TOTAL)?.let { dp ->
                HealthRepository.patch {
                    it.copy(steps = dp.total, lastUpdateEpochMs = System.currentTimeMillis())
                }
                gattServer.notify(AncsConstants.STEPS_UUID)
            }
            update.getData(DataType.CALORIES_TOTAL)?.let { dp ->
                HealthRepository.patch {
                    it.copy(calories = dp.total.toLong(), lastUpdateEpochMs = System.currentTimeMillis())
                }
                gattServer.notify(AncsConstants.CALORIES_UUID)
            }
            update.getData(DataType.DISTANCE_TOTAL)?.let { dp ->
                HealthRepository.patch {
                    it.copy(distanceMeters = dp.total.toLong(), lastUpdateEpochMs = System.currentTimeMillis())
                }
                gattServer.notify(AncsConstants.DISTANCE_UUID)
            }
        }
    }
}
