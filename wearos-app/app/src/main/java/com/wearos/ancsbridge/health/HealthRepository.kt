package com.wearos.ancsbridge.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Latest health snapshot shared across the app (collector -> GATT server -> UI).
 * Values are nullable until the corresponding sensor/data source has reported.
 */
data class HealthSnapshot(
    val heartRate: Int? = null,
    val spo2: Int? = null,
    val steps: Long? = null,
    val calories: Long? = null,
    val distanceMeters: Long? = null,
    val sleepMinutes: Int? = null,
    val sleepStartEpochSec: Long? = null,
    val sleepEndEpochSec: Long? = null,
    val exerciseActiveCalories: Int? = null,
    val exerciseDurationMin: Int? = null,
    val lastUpdateEpochMs: Long = 0L
)

object HealthRepository {
    private val _snapshot = MutableStateFlow(HealthSnapshot())
    val snapshot: StateFlow<HealthSnapshot> = _snapshot.asStateFlow()

    fun patch(block: (HealthSnapshot) -> HealthSnapshot) {
        _snapshot.value = block(_snapshot.value)
    }

    val current: HealthSnapshot get() = _snapshot.value
}
