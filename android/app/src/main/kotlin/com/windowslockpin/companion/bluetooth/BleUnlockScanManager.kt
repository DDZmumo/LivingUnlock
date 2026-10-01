package com.windowslockpin.companion.bluetooth

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.windowslockpin.companion.core.model.SafeLogger
import com.windowslockpin.companion.service.BluetoothUnlockService
import java.util.Locale

/**
 * Manages a low-power background scan for LULK v1 manufacturer beacons.
 *
 * The PC advertises a fresh nonce when it is waiting for an unlock connection
 * (via BluetoothLEAdvertisementPublisher on the Windows side).
 *
 * Android delivers scan results via a [PendingIntent] even when the app is killed,
 * without periodic RFCOMM connections. Beacons are discovery hints only;
 * the paired-device challenge protocol still authenticates every unlock.
 *
 * Usage:
 *   BleUnlockScanManager.startScan(context)   — called from Application.onCreate
 *   BleUnlockScanManager.stopScan(context)    — called when pairing is removed
 */
object BleUnlockScanManager {
    private const val TAG = "BleUnlockScanManager"

    private const val MANUFACTURER_ID = 0xFFFF
    private val beaconPrefix = byteArrayOf(0x4c, 0x55, 0x4c, 0x4b, 1)
    private val seenBeacons = LinkedHashSet<String>()

    data class WakeBeacon(val address: String, val key: String)

    @Synchronized
    fun claimBeacon(result: ScanResult): WakeBeacon? {
        val data = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID) ?: return null
        if (data.size != 19 || !data.copyOfRange(0, 5).contentEquals(beaconPrefix)) return null
        val address = data.copyOfRange(5, 11).joinToString(":") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
        val key = data.joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
        if (!seenBeacons.add(key)) return null
        if (seenBeacons.size > 64) seenBeacons.remove(seenBeacons.first())
        return WakeBeacon(address, key)
    }

    @Synchronized
    fun releaseBeacon(beacon: WakeBeacon) { seenBeacons.remove(beacon.key) }

    private const val SCAN_REQUEST_CODE = 9001
    private const val ACTION_BLE_SCAN_RESULT =
        "com.windowslockpin.companion.BLE_SCAN_RESULT"
    @Volatile private var scanning = false
    const val KEY_COMPATIBILITY_SCAN = "ble_compatibility_scan_enabled"

    fun isCompatibilityScanEnabled(context: Context): Boolean =
        context.getSharedPreferences("wslp_companion_prefs", Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPATIBILITY_SCAN, false)

    fun isListeningEnabled(context: Context): Boolean =
        context.getSharedPreferences("wslp_companion_prefs", Context.MODE_PRIVATE)
            .getBoolean("bluetooth_listening_enabled", false)

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startScan(context: Context) {
        if (!isListeningEnabled(context) || !isCompatibilityScanEnabled(context)) {
            stopScan(context)
            return
        }
        if (!hasBluetoothPermissions(context)) {
            scanning = false
            SafeLogger.w(TAG, "BLE scan deferred until Bluetooth permissions are granted")
            return
        }
        try {
            val scanner = getScanner(context) ?: run { scanning = false; return }
            if (scanning) return
            val pi = buildPendingIntent(context)

            val filter = ScanFilter.Builder()
                .setManufacturerData(MANUFACTURER_ID, beaconPrefix)
                .build()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)   // battery-friendly
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES) // deduplicate by request nonce
                .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                .build()

            scanner.stopScan(pi) // replace registrations left by an earlier process/app version
            val result = scanner.startScan(listOf(filter), settings, pi)
            if (result == 0) {
                scanning = true
                SafeLogger.i(TAG, "Background BLE scan started (manufacturer beacon filter active)")
            } else {
                SafeLogger.w(TAG, "Background BLE scan start failed: error code $result")
            }
        } catch (e: SecurityException) {
            SafeLogger.w(TAG, "BLE scan deferred because Bluetooth permission was revoked")
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stopScan(context: Context) {
        scanning = false
        if (!hasBluetoothPermissions(context)) return
        try {
            getScanner(context)?.stopScan(buildPendingIntent(context))
            scanning = false
            SafeLogger.i(TAG, "Background BLE scan stopped")
        } catch (e: SecurityException) {
            SafeLogger.w(TAG, "BLE scan stop skipped because Bluetooth permission was revoked")
        }
    }

    private fun hasBluetoothPermissions(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
             ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

    private fun getScanner(context: Context): BluetoothLeScanner? {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter = bm?.adapter ?: return null
        if (!adapter.isEnabled) {
            SafeLogger.w(TAG, "Bluetooth is off; BLE scan not started")
            return null
        }
        return adapter.bluetoothLeScanner
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BleScanReceiver::class.java).apply {
            action = ACTION_BLE_SCAN_RESULT
        }
        return PendingIntent.getBroadcast(
            context,
            SCAN_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
}

/**
 * Receives BLE scan results in the background (even when the app is killed).
 * When a PC lock-screen beacon is detected, starts [BluetoothUnlockService]
 * which immediately establishes the RFCOMM connection and shows the unlock notification.
 */
class BleScanReceiver : BroadcastReceiver() {
    private val tag = "BleScanReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (!BleUnlockScanManager.isListeningEnabled(context) ||
            !BleUnlockScanManager.isCompatibilityScanEnabled(context)) return
        val results: List<ScanResult> =
            intent.getParcelableArrayListExtra(android.bluetooth.le.BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
                ?: return

        val beacons = results.mapNotNull { BleUnlockScanManager.claimBeacon(it) }
        if (beacons.isEmpty()) return

        SafeLogger.i(tag, "BLE beacon detected from PC (${results.size} result(s)) — starting unlock service")

        // Start (or wake) the unlock service so it connects via RFCOMM immediately
        val serviceIntent = Intent(context, BluetoothUnlockService::class.java).apply {
            action = BluetoothUnlockService.ACTION_BLE_WAKE
            putStringArrayListExtra(BluetoothUnlockService.EXTRA_BLE_PC_ADDRESSES,
                ArrayList(beacons.map { it.address }.distinct()))
        }
        try {
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            beacons.forEach { BleUnlockScanManager.releaseBeacon(it) }
            SafeLogger.w(tag, "BLE wake could not start the listener: ${e.javaClass.simpleName}")
        }
    }
}
