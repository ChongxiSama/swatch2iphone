package com.wearos.ancsbridge.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.wearos.ancsbridge.ble.AncsConstants
import com.wearos.ancsbridge.ble.HealthGattServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Reads sleep sessions from Health Connect (which Health Services does not expose)
 * and pushes them over GATT. Sleep is a low-frequency, daily metric, so it is
 * polled on a generous interval rather than streamed.
 */
class HealthConnectSleepReader(
    private val context: Context,
    private val gattServer: HealthGattServer
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: HealthConnectClient? = null
    private var running = false

    private val sleepPermission = HealthPermission.getReadPermission(SleepSessionRecord::class)
    private val spo2Permission = HealthPermission.getReadPermission(OxygenSaturationRecord::class)

    fun available(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    /** Starts polling only if at least one of the needed permissions is granted. */
    suspend fun startIfGranted() {
        if (!available()) return
        val c = HealthConnectClient.getOrCreate(context)
        client = c
        val granted = c.permissionController.getGrantedPermissions()
        if (!granted.contains(sleepPermission) && !granted.contains(spo2Permission)) return
        startPolling()
    }

    /** Starts polling (after the permission has been granted via the UI flow). */
    fun start() {
        scope.launch { startIfGranted() }
    }

    private fun startPolling() {
        if (running) return
        running = true
        scope.launch {
            while (isActive && running) {
                readAndPush()
                delay(30 * 60 * 1000L)
            }
        }
    }

    fun stop() {
        running = false
        scope.cancel()
    }

    private suspend fun readAndPush() {
        val c = client ?: return
        try {
            val granted = c.permissionController.getGrantedPermissions()
            if (granted.contains(sleepPermission)) {
                readSleep(c)
            }
            if (granted.contains(spo2Permission)) {
                readSpo2(c)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read from Health Connect: ${e.message}")
        }
    }

    private suspend fun readSleep(c: HealthConnectClient) {
        val end = Instant.now()
        val start = end.minus(2, ChronoUnit.DAYS)
        val request = ReadRecordsRequest(
            recordType = SleepSessionRecord::class,
            timeRangeFilter = TimeRangeFilter.between(start, end),
            ascendingOrder = false
        )
        val session = c.readRecords(request).records.firstOrNull() ?: return
        val startSec = session.startTime.epochSecond
        val endSec = session.endTime.epochSecond
        HealthRepository.patch {
            it.copy(
                sleepStartEpochSec = startSec,
                sleepEndEpochSec = endSec,
                sleepMinutes = ((endSec - startSec) / 60).toInt(),
                lastUpdateEpochMs = System.currentTimeMillis()
            )
        }
        gattServer.notify(AncsConstants.SLEEP_UUID)
    }

    private suspend fun readSpo2(c: HealthConnectClient) {
        val end = Instant.now()
        val start = end.minus(1, ChronoUnit.DAYS)
        val request = ReadRecordsRequest(
            recordType = OxygenSaturationRecord::class,
            timeRangeFilter = TimeRangeFilter.between(start, end),
            ascendingOrder = false
        )
        val rec = c.readRecords(request).records.firstOrNull() ?: return
        val pct = (rec.percentage.value * 100).roundToInt()
        HealthRepository.patch {
            it.copy(
                spo2 = pct,
                lastUpdateEpochMs = System.currentTimeMillis()
            )
        }
        gattServer.notify(AncsConstants.SPO2_UUID)
    }

    companion object {
        private const val TAG = "HealthConnectSleepReader"
    }
}
