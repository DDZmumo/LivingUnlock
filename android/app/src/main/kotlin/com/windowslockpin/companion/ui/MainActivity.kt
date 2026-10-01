package com.windowslockpin.companion.ui

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import com.windowslockpin.companion.core.egg.EggCodec
import com.windowslockpin.companion.storage.DeviceDetailsStore
import com.windowslockpin.companion.ui.screens.DeviceDetailsScreen
import com.windowslockpin.companion.ui.screens.EggScreen
import kotlinx.coroutines.withTimeout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.windowslockpin.companion.R
import com.windowslockpin.companion.biometric.BiometricAuthManager
import com.windowslockpin.companion.bluetooth.BleUnlockScanManager
import com.windowslockpin.companion.bluetooth.BluetoothRfcommClient
import com.windowslockpin.companion.core.crypto.SecretStore
import com.windowslockpin.companion.core.model.*
import com.windowslockpin.companion.core.pairing.PairingCoordinator
import com.windowslockpin.companion.core.pairing.PairingResult
import com.windowslockpin.companion.core.statemachine.CompanionStateMachine
import com.windowslockpin.companion.core.statemachine.PairedPcRecord
import com.windowslockpin.companion.core.storage.EncryptedFilePairedDeviceStore
import com.windowslockpin.companion.core.storage.PairedDeviceStore
import com.windowslockpin.companion.core.unlock.UnlockCoordinator
import com.windowslockpin.companion.security.AndroidKeystoreRecordCipher
import com.windowslockpin.companion.security.AndroidKeystoreSecretStore
import com.windowslockpin.companion.service.ActiveUnlockSession
import com.windowslockpin.companion.service.BluetoothUnlockService
import com.windowslockpin.companion.service.UnlockSessionManager
import com.windowslockpin.companion.service.UnlockUiEvent
import com.windowslockpin.companion.storage.SharedPreferencesDeviceIdProvider
import com.windowslockpin.companion.ui.screens.MainDeviceScreen
import com.windowslockpin.companion.ui.screens.UnlockRequestScreen
import com.windowslockpin.companion.ui.screens.SettingsScreen
import com.windowslockpin.companion.ui.theme.LivingUnlockTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.security.Signature

class MainActivity : AppCompatActivity() {

    private lateinit var stateMachine: CompanionStateMachine
    private lateinit var secretStore: SecretStore
    private lateinit var biometricAuthManager: BiometricAuthManager
    private lateinit var pairedDeviceStore: PairedDeviceStore
    private lateinit var deviceIdProvider: SharedPreferencesDeviceIdProvider
    private lateinit var pairingCoordinator: PairingCoordinator
    private lateinit var unlockCoordinator: UnlockCoordinator
    private lateinit var prefs: SharedPreferences

    private val keyAlias = "wslp_companion_auth_key"

    private val pairedDevicesState = mutableStateListOf<PairedPcRecord>()
    private var eggPayload by mutableStateOf<String?>(null)
    private var selectedDevice by mutableStateOf<PairedPcRecord?>(null)
    private var deviceUiRevision by mutableStateOf(0)
    private var showSettings by mutableStateOf(false)
    private val detailsStore by lazy { DeviceDetailsStore(this) }

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleScannedPayload(result.contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eggPayload = savedInstanceState?.getString("scan_result")?.take(2401)
        showSettings = savedInstanceState?.getBoolean("settings_open") ?: false

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        secretStore = try {
            AndroidKeystoreSecretStore(requireBiometricAuthPerUse = true)
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Android Keystore initialization failed", e)
            Toast.makeText(this, "Android Keystore 不可用，生物认证无法启用", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        biometricAuthManager = BiometricAuthManager(this)
        deviceIdProvider = SharedPreferencesDeviceIdProvider(this)
        pairedDeviceStore = try {
            EncryptedFilePairedDeviceStore(
                storageDir = File(filesDir, "paired_devices_v1"),
                cipher = AndroidKeystoreRecordCipher()
            )
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Encrypted pairing store initialization failed", e)
            Toast.makeText(this, "安全配对存储不可用", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val initialRecords = pairedDeviceStore.getPairedPcs().associateBy { it.pcId.value }
        val candidateStateMachine = CompanionStateMachine(initialPairedPcs = initialRecords)
        val candidateUnlockCoordinator = UnlockCoordinator(deviceIdProvider, candidateStateMachine)
        val shared = UnlockSessionManager.initializeIfNeeded(candidateUnlockCoordinator, candidateStateMachine)
        this.unlockCoordinator = shared.coordinator
        this.stateMachine = shared.stateMachine

        val bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        pairingCoordinator = PairingCoordinator(
            deviceIdProvider = deviceIdProvider,
            deviceNameProvider = { "${Build.MANUFACTURER} ${Build.MODEL}".take(64) },
            secretStore = secretStore,
            transportClient = BluetoothRfcommClient(bluetoothAdapter),
            pairedDeviceStore = pairedDeviceStore,
            stateMachine = this.stateMachine,
            onPairedConnection = { record, connection ->
                try {
                    val frame=withTimeout(1500){connection.receiveFrame()}
                    if(frame.header.messageType==ProtocolConstants.MSG_PONG) detailsStore.accept(record,frame.payload)
                } catch(_:Exception) { /* Metadata is optional; pairing success remains valid. */ }
            }
        )

        refreshPairedDevicesList()
        checkPermissions()
        observeUiEvents()

        val isListeningEnabled = prefs.getBoolean(KEY_LISTENING_ENABLED, false)
        if (isListeningEnabled && hasBluetoothPermissions()) {
            startUnlockService()
        }

        // Set Compose Content
        setContent {
            LivingUnlockTheme {
                val activeSession by UnlockSessionManager.activeSession.collectAsStateWithLifecycle()
                DisposableEffect(activeSession?.requestId) {
                    onDispose { biometricAuthManager.cancelAuthentication() }
                }
                var isListening by remember { mutableStateOf(isListeningEnabled) }
                var compatibilityScan by remember { mutableStateOf(BleUnlockScanManager.isCompatibilityScanEnabled(this)) }
                DisposableEffect(prefs) {
                    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                        isListening = prefs.getBoolean(KEY_LISTENING_ENABLED, false)
                        compatibilityScan = BleUnlockScanManager.isCompatibilityScanEnabled(this@MainActivity)
                    }
                    prefs.registerOnSharedPreferenceChangeListener(listener)
                    onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
                }
                val revision=deviceUiRevision
                BackHandler(enabled=eggPayload!=null || selectedDevice!=null || showSettings) {
                    if (selectedDevice != null) selectedDevice = null
                    else if (eggPayload != null) eggPayload = null
                    else showSettings = false
                }

                AnimatedContent(
                    targetState = activeSession,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(280)) togetherWith fadeOut(animationSpec = tween(280))
                    },
                    label = "screen_transition"
                ) { session ->
                    if (session != null && !session.isExpired) {
                        UnlockRequestScreen(
                            session = session,
                            onConfirmUnlock = {
                                lifecycleScope.launch {
                                    triggerBiometricUnlock()
                                }
                            },
                            onCancelUnlock = {
                                lifecycleScope.launch {
                                    cancelCurrentUnlockRequest()
                                }
                            }
                        )
                    } else if(eggPayload!=null) {
                        EggScreen(eggPayload!!){eggPayload=null}
                    } else if (showSettings) {
                        SettingsScreen(
                            isListening = isListening,
                            compatibilityScan = compatibilityScan,
                            onBack = { showSettings = false },
                            onToggleListening = { onListeningSwitchToggled(it) },
                            onToggleCompatibility = { enabled ->
                                prefs.edit().putBoolean(BleUnlockScanManager.KEY_COMPATIBILITY_SCAN, enabled).apply()
                                compatibilityScan = enabled
                                BleUnlockScanManager.startScan(this@MainActivity)
                            }
                        )
                    } else {
                        MainDeviceScreen(
                            pairedDevices = pairedDevicesState,
                            onOpenSettings = { showSettings = true },
                            isListeningEnabled = isListening,
                            onToggleListening = { enabled ->
                                if (onListeningSwitchToggled(enabled)) isListening = enabled
                            },
                            onScanQrCode = { launchQrScanner() },
                            onDeviceClick = { selectedDevice=it },
                            displayName = { if(revision>=0) detailsStore.displayName(it) else it.pcName },
                            onUnpairDevice = { record ->
                                unpairDevice(record)
                            }
                        )
                        selectedDevice?.let { record ->
                            DeviceDetailsScreen(record,detailsStore,{selectedDevice=null},{deviceUiRevision++})
                        }
                    }
                }
            }
        }

        handleIncomingIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Save only the encrypted scan result, never the password or decrypted content.
        outState.putString("scan_result", eggPayload)
        outState.putBoolean("settings_open", showSettings)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        UnlockSessionManager.isAppInForeground = true
        checkIntentForPairing(intent)
    }

    override fun onPause() {
        super.onPause()
        UnlockSessionManager.isAppInForeground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkIntentForPairing(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(incomingIntent: Intent?) {
        if (incomingIntent == null) return
        val isUnlockAction = incomingIntent.action == BluetoothUnlockService.ACTION_CONFIRM_UNLOCK

        // ONLY trigger biometric prompt if the explicit "解锁" action button was clicked!
        if (isUnlockAction) {
            incomingIntent.action = null
            val intentRequestId = incomingIntent.getLongExtra(BluetoothUnlockService.EXTRA_REQUEST_ID, -1L)
            SafeLogger.i(TAG, "Activity opened via explicit notification Unlock action (reqId=$intentRequestId)")
            lifecycleScope.launch {
                delay(250L) // Small delay to let Compose views settle
                val session = UnlockSessionManager.activeSession.value
                if (!UnlockSessionManager.shouldTriggerBiometricForNotification(session, intentRequestId)) {
                    SafeLogger.w(TAG, "Notification unlock action ignored: expired or mismatched requestId ($intentRequestId)")
                    return@launch
                }
                triggerBiometricUnlock()
            }
        }
    }

    private fun observeUiEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UnlockSessionManager.uiEvents.collect { event ->
                    when (event) {
                        is UnlockUiEvent.Accepted -> {
                            Toast.makeText(this@MainActivity, getString(R.string.status_unlock_accepted), Toast.LENGTH_SHORT).show()
                        }
                        is UnlockUiEvent.Rejected -> {
                            Toast.makeText(this@MainActivity, "解锁被拒绝: ${event.message}", Toast.LENGTH_SHORT).show()
                        }
                        is UnlockUiEvent.Error -> {
                            Toast.makeText(this@MainActivity, "解锁出错: ${event.message}", Toast.LENGTH_SHORT).show()
                        }
                        is UnlockUiEvent.Cancelled -> {
                            Toast.makeText(this@MainActivity, "已取消解锁", Toast.LENGTH_SHORT).show()
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun refreshPairedDevicesList() {
        pairedDevicesState.clear()
        pairedDevicesState.addAll(pairedDeviceStore.getPairedPcs())
    }

    private fun unpairDevice(record: PairedPcRecord) {
        detailsStore.remove(record)
        pairedDeviceStore.removePairedPc(record.pcId)
        stateMachine.removePairedPc(record.pcId)
        refreshPairedDevicesList()

        if (pairedDevicesState.isEmpty()) {
            stopUnlockService()
            prefs.edit().putBoolean(KEY_LISTENING_ENABLED, false).apply()
            BleUnlockScanManager.stopScan(this)
        }
        Toast.makeText(this, "已解除与【${record.pcName}】的配对", Toast.LENGTH_SHORT).show()
    }

    private fun onListeningSwitchToggled(isChecked: Boolean): Boolean {
        if (isChecked) {
            if (!hasBluetoothPermissions()) {
                checkPermissions()
                Toast.makeText(this, "请先允许附近设备权限", Toast.LENGTH_SHORT).show()
                return false
            }
            val paired = pairedDeviceStore.getPairedPcs()
            if (paired.isEmpty()) {
                Toast.makeText(this, "请先扫描电脑端二维码完成配对", Toast.LENGTH_SHORT).show()
                return false
            }

            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            if (bm.adapter == null || !bm.adapter.isEnabled) {
                Toast.makeText(this, "蓝牙未开启，请先在手机设置中开启蓝牙", Toast.LENGTH_SHORT).show()
                return false
            }

            prefs.edit().putBoolean(KEY_LISTENING_ENABLED, true).apply()
            startUnlockService()
            Toast.makeText(this, "已启用后台自动解锁监听", Toast.LENGTH_SHORT).show()
        } else {
            prefs.edit().putBoolean(KEY_LISTENING_ENABLED, false).apply()
            stopUnlockService()
            Toast.makeText(this, "已停用后台自动解锁监听", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun hasBluetoothPermissions(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
             ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && hasBluetoothPermissions()) {
            BleUnlockScanManager.startScan(this)
            if (prefs.getBoolean(KEY_LISTENING_ENABLED, false)) startUnlockService()
        }
    }

    private fun startUnlockService() {
        val serviceIntent = Intent(this, BluetoothUnlockService::class.java).apply {
            action = BluetoothUnlockService.ACTION_START_LISTENING
        }
        try {
            startForegroundService(serviceIntent)
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Failed to start BluetoothUnlockService", e)
        }
    }

    private fun stopUnlockService() {
        val serviceIntent = Intent(this, BluetoothUnlockService::class.java).apply {
            action = BluetoothUnlockService.ACTION_STOP_LISTENING
        }
        startService(serviceIntent)
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setPrompt(getString(R.string.scanner_prompt))
            setBeepEnabled(false)
            setOrientationLocked(true)
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setCaptureActivity(LivingUnlockCaptureActivity::class.java)
        }
        barcodeLauncher.launch(options)
    }

    private fun handleScannedPayload(payload: String) {
        if(EggCodec.isEgg(payload)) {
            eggPayload=payload.take(2401)
            selectedDevice=null
            return
        }
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "正在与电脑建立安全配对通道...", Toast.LENGTH_SHORT).show()
            try {
                val uri = PairingUri.parse(payload)
                val result = pairingCoordinator.pair(uri)
                when (result) {
                    is PairingResult.Success -> {
                        Toast.makeText(this@MainActivity, "与【${result.record.pcName}】配对成功！", Toast.LENGTH_LONG).show()
                        refreshPairedDevicesList()
                        prefs.edit().putBoolean(KEY_LISTENING_ENABLED, true).apply()
                        startUnlockService()
                    }
                    is PairingResult.Failure -> {
                        Toast.makeText(this@MainActivity, "配对失败: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                SafeLogger.e(TAG, "Pairing exception", e)
                Toast.makeText(this@MainActivity, "配对异常: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkIntentForPairing(intent: Intent?) {
        val dataString = intent?.dataString ?: return
        if (dataString.startsWith("wslp://pair")) {
            handleScannedPayload(dataString)
            intent.data = null
        }
    }

    private suspend fun triggerBiometricUnlock() {
        val session = UnlockSessionManager.activeSession.value
        if (session == null || session.isExpired) {
            SafeLogger.d(TAG, "No active session to unlock")
            return
        }

        val canSign = UnlockSessionManager.tryStartSigning(session.requestId)
        if (!canSign) {
            SafeLogger.w(TAG, "Cannot start signing; session already signed or signing in progress")
            return
        }

        secretStore.getOrCreateCompanionKeyPair(keyAlias)
        val signature: Signature = try {
            secretStore.initSignature(keyAlias).also {
                it.update(session.transcript)
            }
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Failed to initialize signature with Keystore", e)
            UnlockSessionManager.cancelSigning(session.requestId)
            UnlockSessionManager.cancelActiveSession(
                reasonCode = CancelMessage.REASON_SYSTEM_CANCELLED,
                reasonText = "Signature init failed",
                expectedRequestId = session.requestId
            )
            Toast.makeText(this, "安全硬件签名初始化失败", Toast.LENGTH_SHORT).show()
            return
        }

        biometricAuthManager.authenticateWithCrypto(
            signature = signature,
            title = getString(R.string.biometric_title),
            subtitle = getString(R.string.biometric_subtitle),
            description = getString(R.string.unlock_request_card_title, session.pcRecord.pcName),
            negativeButtonText = getString(R.string.biometric_negative),
            callback = object : BiometricAuthManager.Callback {
                override fun onAuthenticated(authenticatedSignature: Signature) {
                    var derSignature: ByteArray? = null
                    try {
                        derSignature = authenticatedSignature.sign()
                        val submitted = UnlockSessionManager.submitBiometricSignature(session.requestId, derSignature)
                        if (!submitted) {
                            lifecycleScope.launch {
                                UnlockSessionManager.cancelActiveSession(
                                    reasonCode = CancelMessage.REASON_SYSTEM_CANCELLED,
                                    reasonText = "Failed to submit signature",
                                    expectedRequestId = session.requestId
                                )
                            }
                        }
                    } catch (e: Exception) {
                        SafeLogger.e(TAG, "Biometric signature generation error", e)
                        lifecycleScope.launch {
                            UnlockSessionManager.cancelActiveSession(
                                reasonCode = CancelMessage.REASON_SYSTEM_CANCELLED,
                                reasonText = "Signature exception: ${e.javaClass.simpleName}",
                                expectedRequestId = session.requestId
                            )
                        }
                    } finally {
                        derSignature?.fill(0)
                    }
                }

                override fun onAuthenticationFailed() {
                    // Fingerprint not recognized; keep dialog alive
                }

                override fun onError(errorCode: Int, errString: CharSequence) {
                    lifecycleScope.launch {
                        UnlockSessionManager.cancelSigning(session.requestId)
                        UnlockSessionManager.cancelActiveSession(
                            reasonCode = CancelMessage.REASON_USER_CANCELLED,
                            reasonText = "User cancelled biometric prompt: $errorCode",
                            expectedRequestId = session.requestId
                        )
                    }
                }
            }
        )
    }

    private suspend fun cancelCurrentUnlockRequest() {
        UnlockSessionManager.cancelActiveSession(
            reasonCode = CancelMessage.REASON_USER_CANCELLED,
            reasonText = "Cancelled by user"
        )
    }

    private fun checkPermissions() {
        val neededPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(android.Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val PREFS_NAME = "wslp_companion_prefs"
        private const val KEY_LISTENING_ENABLED = "bluetooth_listening_enabled"
    }
}
