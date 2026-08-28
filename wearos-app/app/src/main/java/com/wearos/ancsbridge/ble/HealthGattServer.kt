package com.wearos.ancsbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.wearos.ancsbridge.health.HealthRepository
import java.util.UUID

/**
 * Exposes collected health data over GATT. The watch acts as a peripheral
 * (GATT server) advertising a custom health service; the iPhone app connects
 * as a central and subscribes to notifications.
 *
 * No Apple peripheral entitlement is required on the iOS side — it only needs
 * CBCentralManager, which is available to free Apple Developer accounts.
 */
@SuppressLint("MissingPermission")
class HealthGattServer(private val context: Context) {

    private val TAG = "HealthGattServer"
    private var gattServer: BluetoothGattServer? = null
    private val connectedCentrals = mutableSetOf<BluetoothDevice>()
    private val charMap = mutableMapOf<UUID, BluetoothGattCharacteristic>()

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            device ?: return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedCentrals.add(device)
                    Log.i(TAG, "Central connected: ${device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedCentrals.remove(device)
                    Log.i(TAG, "Central disconnected: ${device.address}")
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            device ?: return
            val uuid = characteristic?.uuid ?: return
            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                currentValueFor(uuid)
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            device ?: return
            descriptor ?: return
            val result = if (descriptor.uuid == AncsConstants.CCCD_UUID) {
                BluetoothGatt.GATT_SUCCESS
            } else {
                BluetoothGatt.GATT_FAILURE
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, result, 0, null)
            }
        }
    }

    fun start() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null) {
            Log.e(TAG, "No Bluetooth adapter available")
            return
        }
        gattServer = manager.openGattServer(context, serverCallback)
        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT server")
            return
        }
        addHealthService()
        startAdvertising(adapter)
    }

    private fun addHealthService() {
        val service = BluetoothGattService(
            AncsConstants.HEALTH_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        service.addCharacteristic(makeNotifyChar(AncsConstants.HEART_RATE_UUID))
        service.addCharacteristic(makeNotifyChar(AncsConstants.SPO2_UUID))
        service.addCharacteristic(makeNotifyChar(AncsConstants.STEPS_UUID))
        service.addCharacteristic(makeNotifyChar(AncsConstants.CALORIES_UUID))
        service.addCharacteristic(makeNotifyChar(AncsConstants.DISTANCE_UUID))
        service.addCharacteristic(makeNotifyChar(AncsConstants.SLEEP_UUID))
        service.addCharacteristic(makeNotifyChar(AncsConstants.EXERCISE_UUID))
        gattServer?.addService(service)
        Log.i(TAG, "Health GATT service added")
    }

    private fun makeNotifyChar(uuid: UUID): BluetoothGattCharacteristic {
        val characteristic = BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            AncsConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        characteristic.addDescriptor(cccd)
        charMap[uuid] = characteristic
        return characteristic
    }

    private fun startAdvertising(adapter: BluetoothAdapter) {
        val advertiser = adapter.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "LE advertiser unavailable (requires BT LE)")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(AncsConstants.HEALTH_SERVICE_UUID))
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Health advertising started (service ${AncsConstants.HEALTH_SERVICE_UUID})")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Health advertising failed (code=$errorCode)")
        }
    }

    private fun currentValueFor(uuid: UUID): ByteArray {
        val s = HealthRepository.current
        return when (uuid) {
            AncsConstants.HEART_RATE_UUID -> byteArrayOf((s.heartRate ?: 0).coerceIn(0, 255).toByte())
            AncsConstants.SPO2_UUID -> byteArrayOf((s.spo2 ?: 0).coerceIn(0, 255).toByte())
            AncsConstants.STEPS_UUID -> (s.steps ?: 0L).toLittleEndian4()
            AncsConstants.CALORIES_UUID -> (s.calories ?: 0L).toLittleEndian4()
            AncsConstants.DISTANCE_UUID -> (s.distanceMeters ?: 0L).toLittleEndian4()
            AncsConstants.SLEEP_UUID -> {
                if (s.sleepStartEpochSec != null && s.sleepEndEpochSec != null) {
                    s.sleepStartEpochSec!!.toLittleEndian4() + s.sleepEndEpochSec!!.toLittleEndian4()
                } else {
                    byteArrayOf()
                }
            }
            AncsConstants.EXERCISE_UUID -> byteArrayOf(
                (s.exerciseActiveCalories ?: 0).coerceIn(0, 255).toByte(),
                (s.exerciseDurationMin ?: 0).coerceIn(0, 255).toByte()
            )
            else -> byteArrayOf()
        }
    }

    /** Push the latest value of a characteristic to all subscribed centrals. */
    fun notify(uuid: UUID) {
        val characteristic = charMap[uuid] ?: return
        val value = currentValueFor(uuid)
        characteristic.value = value
        for (central in connectedCentrals) {
            gattServer?.notifyCharacteristicChanged(central, characteristic, false, value)
        }
    }

    fun stop() {
        try {
            bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
            // ignore
        }
        gattServer?.close()
        gattServer = null
        connectedCentrals.clear()
        charMap.clear()
    }

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}

private fun Long.toLittleEndian4(): ByteArray = byteArrayOf(
    (this and 0xFF).toByte(),
    ((this ushr 8) and 0xFF).toByte(),
    ((this ushr 16) and 0xFF).toByte(),
    ((this ushr 24) and 0xFF).toByte()
)
