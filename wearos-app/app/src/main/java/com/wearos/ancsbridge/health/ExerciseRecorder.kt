package com.wearos.ancsbridge.health

import android.content.Context
import android.util.Log
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseType
import com.wearos.ancsbridge.ble.AncsConstants
import com.wearos.ancsbridge.ble.HealthGattServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Drives an explicit workout session via Health Services ExerciseClient and
 * streams live metrics (active calories + duration) to the GATT server so the
 * iPhone can record a workout.
 */
class ExerciseRecorder(
    private val context: Context,
    private val gattServer: HealthGattServer
) {
    private val TAG = "ExerciseRecorder"
    private val client = HealthServices.getClient(context)
    private var exerciseClient: ExerciseClient? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    var isActive = false
        private set

    fun start(type: ExerciseType = ExerciseType.WALKING) {
        scope.launch {
            try {
                exerciseClient = client.exerciseClient
                val config = ExerciseConfig.builder(type)
                    .setDataTypes(
                        setOf(
                            DataType.HEART_RATE_BPM,
                            DataType.CALORIES_TOTAL,
                            DataType.DISTANCE_TOTAL,
                            DataType.STEPS_TOTAL
                        )
                    )
                    .build()
                exerciseClient?.setUpdateCallback(exerciseCallback)
                exerciseClient?.startExerciseAsync(config)?.await()
                isActive = true
                Log.i(TAG, "Exercise started: $type")
            } catch (e: Exception) {
                Log.e(TAG, "startExercise failed: ${e.message}")
            }
        }
    }

    fun stop() {
        scope.launch {
            try {
                exerciseClient?.endExerciseAsync()?.await()
            } catch (_: Exception) {
                // ignore
            }
            isActive = false
        }
    }

    private val exerciseCallback = object : ExerciseUpdateCallback {
        override fun onRegistered() {
            isActive = true
        }

        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(TAG, "Exercise registration failed: ${throwable.message}")
            isActive = false
        }

        override fun onExerciseUpdateReceived(update: androidx.health.services.client.data.ExerciseUpdate) {
            val activeCal = update.latestMetrics.getData(DataType.CALORIES_TOTAL)?.total?.roundToInt()
            val durationMin = (update.activeDurationCheckpoint?.activeDuration?.toSeconds()?.div(60) ?: 0).toInt()
            HealthRepository.patch {
                it.copy(
                    exerciseActiveCalories = activeCal,
                    exerciseDurationMin = durationMin,
                    lastUpdateEpochMs = System.currentTimeMillis()
                )
            }
            gattServer.notify(AncsConstants.EXERCISE_UUID)
        }

        override fun onLapSummaryReceived(lapSummary: androidx.health.services.client.data.ExerciseLapSummary) {}

        override fun onAvailabilityChanged(
            dataType: androidx.health.services.client.data.DataType<*, *>,
            availability: androidx.health.services.client.data.Availability
        ) {}
    }
}
