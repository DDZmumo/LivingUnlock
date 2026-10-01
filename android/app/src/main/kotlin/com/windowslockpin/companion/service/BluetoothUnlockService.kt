package com.windowslockpin.companion.service

import android.app.Notification
import com.windowslockpin.companion.storage.DeviceDetailsStore
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.annotation.SuppressLint
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.windowslockpin.companion.R
import com.windowslockpin.companion.bluetooth.BluetoothRfcommClient
import com.windowslockpin.companion.bluetooth.BluetoothRfcommConnection
import com.windowslockpin.companion.core.transport.TransportConnection
import com.windowslockpin.companion.bluetooth.BleUnlockScanManager
import com.windowslockpin.companion.core.model.*
import com.windowslockpin.companion.core.statemachine.CompanionStateMachine
import com.windowslockpin.companion.core.statemachine.PairedPcRecord
import com.windowslockpin.companion.core.storage.EncryptedFilePairedDeviceStore
import com.windowslockpin.companion.core.storage.PairedDeviceStore
import com.windowslockpin.companion.core.unlock.UnlockCoordinator
import com.windowslockpin.companion.security.AndroidKeystoreRecordCipher
import com.windowslockpin.companion.storage.SharedPreferencesDeviceIdProvider
import com.windowslockpin.companion.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.io.File
import java.io.IOException

class BluetoothUnlockService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    @Volatile private var isListening: Boolean = false
    private lateinit var pairedDeviceStore: PairedDeviceStore
    private lateinit var deviceIdProvider: SharedPreferencesDeviceIdProvider
    private lateinit var stateMachine: CompanionStateMachine
    private lateinit var coordinator: UnlockCoordinator
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var connectionJob: Job? = null
    private val connectionLock = Any()
    private val pendingAddresses = LinkedHashSet<String>()
    private val sessionTransportMutex = Mutex()
    private var passiveJob: Job? = null
    @Volatile private var passiveServer: BluetoothServerSocket? = null
    @Volatile private var passiveSocket: BluetoothSocket? = null
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED || !isListening) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> {
                    BleUnlockScanManager.stopScan(context)
                    synchronized(connectionLock) { pendingAddresses.clear() }
                    connectionJob?.cancel()
                    stopPassiveListener()
                }
                BluetoothAdapter.STATE_ON -> {
                    BleUnlockScanManager.startScan(context)
                    startPassiveListener()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        SafeLogger.i(TAG, "BluetoothUnlockService onCreate")
        createNotificationChannels()

        deviceIdProvider = SharedPreferencesDeviceIdProvider(this)
        pairedDeviceStore = try {
            EncryptedFilePairedDeviceStore(
                storageDir = File(filesDir, "paired_devices_v1"),
                cipher = AndroidKeystoreRecordCipher()
            )
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Failed to initialize paired store in service", e)
            stopSelf()
            return
        }

        val initialRecords = pairedDeviceStore.getPairedPcs().associateBy { it.pcId.value }
        val candidateStateMachine = CompanionStateMachine(initialPairedPcs = initialRecords)
        val candidateCoordinator = UnlockCoordinator(deviceIdProvider, candidateStateMachine)
        val shared = UnlockSessionManager.initializeIfNeeded(candidateCoordinator, candidateStateMachine)
        this.coordinator = shared.coordinator
        this.stateMachine = shared.stateMachine

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bm?.adapter
        ContextCompat.registerReceiver(this, bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        SafeLogger.i(TAG, "onStartCommand action=$action")

        when (action) {
            ACTION_STOP_LISTENING -> {
                serviceScope.launch {
                    try {
                        stopListeningAndCleanup()
                    } catch (e: Exception) {
                        SafeLogger.w(TAG, "Error in stopListeningAndCleanup: ${e.message}")
                    } finally {
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_CANCEL_REQUEST -> {
                serviceScope.launch {
                    SafeLogger.i(TAG, "User clicked cancel action from notification")
                    dismissHeadsUpNotification()
                    UnlockSessionManager.cancelActiveSession(
                        reasonCode = CancelMessage.REASON_USER_CANCELLED,
                        reasonText = "User cancelled via notification"
                    )
                }
                return START_STICKY
            }
            else -> {
                if (!BleUnlockScanManager.isListeningEnabled(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundServiceInternal()
                isListening = true
                startPassiveListener()
                BleUnlockScanManager.startScan(this)
                if (action == ACTION_BLE_WAKE) {
                    connectForBeacon(intent.getStringArrayListExtra(EXTRA_BLE_PC_ADDRESSES).orEmpty())
                }
                return START_STICKY
            }
        }
    }

    private fun startForegroundServiceInternal() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID_SERVICE, notification)
        }
    }

    private fun connectForBeacon(addresses: List<String>): Unit = synchronized(connectionLock) {
        if (!BleUnlockScanManager.isCompatibilityScanEnabled(this)) {
            pendingAddresses.clear()
            return@synchronized
        }
        if (!isListening || bluetoothAdapter?.isEnabled != true) return@synchronized
        pendingAddresses.addAll(addresses)
        if (connectionJob != null || pendingAddresses.isEmpty()) return@synchronized
        val worker = serviceScope.launch(start = CoroutineStart.LAZY) {
            // Drain actual beacon events only; there is no periodic reconnect loop.
            while (isActive && isListening && BleUnlockScanManager.isCompatibilityScanEnabled(this@BluetoothUnlockService)) {
                val batch = synchronized(connectionLock) {
                    pendingAddresses.toSet().also { pendingAddresses.clear() }
                }
                if (batch.isEmpty()) break
                val records = pairedDeviceStore.getPairedPcs().filter { record ->
                    batch.any { it.equals(record.bluetoothMac.value, ignoreCase = true) }
                }
                for (record in records) {
                    if (!isActive || !isListening ||
                        !BleUnlockScanManager.isCompatibilityScanEnabled(this@BluetoothUnlockService)) break
                    SafeLogger.i(TAG, "BLE beacon triggered a bounded unlock connection")
                    withTimeoutOrNull(CONNECTION_WINDOW_MS) {
                        sessionTransportMutex.withLock { listenForPcChallenge(record) }
                    }
                }
            }
            SafeLogger.i(TAG, "Unlock connection finished; waiting for the next BLE beacon")
        }
        connectionJob = worker
        worker.invokeOnCompletion {
            synchronized(connectionLock) {
                if (connectionJob === worker) connectionJob = null
                if (pendingAddresses.isNotEmpty() && isListening) connectForBeacon(emptyList())
            }
        }
        worker.start()
        Unit
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun startPassiveListener() {
        if (!isListening || bluetoothAdapter?.isEnabled != true || passiveJob?.isActive == true) return
        passiveJob = serviceScope.launch {
            var server: BluetoothServerSocket? = null
            try {
                server = bluetoothAdapter!!.listenUsingRfcommWithServiceRecord(
                    "LivingUnlock Phone", PHONE_LISTENER_UUID)
                passiveServer = server
                ensureActive()
                SafeLogger.i(TAG, "Passive phone listener ready")
                while (isActive && isListening) {
                    val socket = server.accept()
                    passiveSocket = socket
                    var accepted: BluetoothRfcommConnection? = null
                    var wakeLock: PowerManager.WakeLock? = null
                    try {
                        ensureActive()
                        val record = pairedDeviceStore.getPairedPcs().firstOrNull {
                            it.bluetoothMac.value.equals(socket.remoteDevice.address, ignoreCase = true)
                        }
                        if (record == null) {
                            SafeLogger.w(TAG, "Incoming connection has no paired PC; closing")
                            continue
                        }
                        wakeLock = getSystemService(PowerManager::class.java)
                            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LivingUnlock:incoming-request")
                            .apply { acquire(CONNECTION_WINDOW_MS + 1000L) }
                        accepted = BluetoothRfcommConnection(socket)
                        SafeLogger.i(TAG, "Paired PC connected to passive listener")
                        withTimeoutOrNull(CONNECTION_WINDOW_MS) {
                            sessionTransportMutex.withLock { listenForPcChallenge(record, accepted) }
                        }
                    } finally {
                        accepted?.close()
                        runCatching { socket.close() }
                        if (passiveSocket === socket) passiveSocket = null
                        if (wakeLock?.isHeld == true) wakeLock.release()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SafeLogger.w(TAG, "Passive listener stopped: ${e.javaClass.simpleName}")
            } finally {
                runCatching { server?.close() }
                if (passiveServer === server) passiveServer = null
            }
        }
    }

    private fun stopPassiveListener() {
        passiveJob?.cancel()
        runCatching { passiveSocket?.close() }
        runCatching { passiveServer?.close() }
    }

    private suspend fun listenForPcChallenge(record: PairedPcRecord, incoming: TransportConnection? = null) {
        val client = BluetoothRfcommClient(bluetoothAdapter)
        var connection: com.windowslockpin.companion.core.transport.TransportConnection? = null
        var ownedSession: ActiveUnlockSession? = null
        var expiryJob: Job? = null
        try {
            SafeLogger.d(TAG, "Attempting connection to '${record.pcName}' (${record.bluetoothMac})...")
            connection = incoming ?: withTimeout(CONNECT_TIMEOUT_MS) {
                client.connect(record.bluetoothMac)
            }
            SafeLogger.i(TAG, "Connected to '${record.pcName}'; awaiting one unlock request")

            // Read optional metadata, then handle one challenge within the connection window.
            while (currentCoroutineContext().isActive && isListening && connection.isConnected) {

                // Blocking read — returns only when a frame arrives or the socket closes.
                val frame = withTimeout(FIRST_FRAME_TIMEOUT_MS) { connection.receiveFrame() }

                when (frame.header.messageType) {
                    ProtocolConstants.MSG_PONG -> {
                        try { DeviceDetailsStore(this).accept(record,frame.payload) }
                        catch(_:Exception) { SafeLogger.w(TAG,"Ignoring invalid optional device metadata") }
                    }
                    ProtocolConstants.MSG_UNLOCK_CHALLENGE -> {
                        val challenge = ProtocolMessage.decode(frame) as UnlockChallenge
                        SafeLogger.i(TAG, "Received challenge from '${record.pcName}', validating...")

                        val transcriptResult = coordinator.processIncomingChallenge(
                            requestId = frame.header.requestId,
                            challenge = challenge,
                            expectedPc = record
                        )

                        if (transcriptResult is ProtocolResult.Failure) {
                            SafeLogger.w(TAG, "Challenge rejected: ${transcriptResult.message}")
                            coordinator.cancel(connection, frame.header.requestId,
                                CancelMessage.REASON_SYSTEM_CANCELLED, transcriptResult.message)
                            return
                        }

                        val transcript = (transcriptResult as ProtocolResult.Success).value

                        val session = ActiveUnlockSession(
                            pcRecord = record,
                            requestId = frame.header.requestId,
                            challenge = challenge,
                            connection = connection,
                            transcript = transcript
                        )

                        val registered = UnlockSessionManager.registerSession(session)
                        if (!registered) {
                            SafeLogger.w(TAG, "Active session already in progress; rejecting")
                            coordinator.cancel(connection, frame.header.requestId,
                                CancelMessage.REASON_SYSTEM_CANCELLED, "Concurrent request rejected")
                            return
                        }
                        ownedSession = session

                        // Only show heads-up floating notification banner when the user is outside the app.
                        // If the user is already inside the app, the in-app card & prompt handles it directly.
                        if (!UnlockSessionManager.isAppInForeground) {
                            showHeadsUpNotification(record, challenge, frame.header.requestId)
                        } else {
                            SafeLogger.i(TAG, "User is inside the app; suppressing heads-up floating banner")
                        }

                        // TTL timeout watcher
                        val timeoutJob = serviceScope.launch {
                            val ttlWaitMs = session.remainingTimeMs()
                            delay(ttlWaitMs)
                            if (UnlockSessionManager.activeSession.value == session) {
                                SafeLogger.w(TAG, "Challenge TTL expired; canceling")
                                dismissHeadsUpNotification()
                                UnlockSessionManager.cancelActiveSession(
                                    reasonCode = CancelMessage.REASON_TIMEOUT,
                                    reasonText = "Challenge TTL expired",
                                    expectedRequestId = session.requestId
                                )
                            }
                        }
                        expiryJob = timeoutJob

                        // Wait for user action or cancellation
                        while (currentCoroutineContext().isActive &&
                            UnlockSessionManager.activeSession.value == session) {
                            if (!connection.isConnected && !session.isSigned) {
                                // PC superseded the challenge: do not wait for its TTL.
                                UnlockSessionManager.clearSession(session)
                                break
                            }
                            delay(250L)
                        }

                        timeoutJob.cancel()
                        dismissHeadsUpNotification()
                        return
                    }

                    ProtocolConstants.MSG_CANCEL -> {
                        // PC cancelled this request. Return to BLE waiting without reconnecting.
                        SafeLogger.i(TAG, "Received Cancel from PC; closing connection")
                        dismissHeadsUpNotification()
                        UnlockSessionManager.cancelActiveSession(
                            reasonCode = CancelMessage.REASON_SYSTEM_CANCELLED,
                            reasonText = "PC sent Cancel"
                        )
                        connection.close()
                        return
                    }

                    else -> {
                        SafeLogger.w(TAG, "Unexpected message type: 0x%02X".format(frame.header.messageType))
                        return
                    }
                }
            }

        } catch (e: TimeoutCancellationException) {
            SafeLogger.d(TAG, "Connection attempt to '${record.pcName}' timed out (not at lock screen)")
            try { connection?.close() } catch (_: Exception) {}
        } catch (e: IOException) {
            SafeLogger.d(TAG, "RFCOMM connection closed or failed: ${e.message}")
            try { connection?.close() } catch (_: Exception) {}
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLogger.w(TAG, "Error in listenForPcChallenge: ${e.message}")
            try { connection?.close() } catch (_: Exception) {}
        } finally {
            expiryJob?.cancel()
            withContext(NonCancellable) {
                ownedSession?.let { session ->
                    if (UnlockSessionManager.activeSession.value === session) {
                        UnlockSessionManager.clearSession(session)
                        dismissHeadsUpNotification()
                    }
                }
            }
            try { connection?.close() } catch (_: Exception) {}
        }
    }

    private fun showHeadsUpNotification(record: PairedPcRecord, challenge: UnlockChallenge, requestId: RequestId) {
        val openRequestIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_UNLOCK_REQUEST
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_REQUEST_ID, requestId.value)
        }
        val unlockIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_CONFIRM_UNLOCK
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_REQUEST_ID, requestId.value)
        }
        val unlockPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_UNLOCK,
            unlockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, BluetoothUnlockService::class.java).apply {
            action = ACTION_CANCEL_REQUEST
            putExtra(EXTRA_REQUEST_ID, requestId.value)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_CANCEL,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_CONTENT,
            openRequestIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val headsUpNotification = NotificationCompat.Builder(this, CHANNEL_UNLOCK_HEADS_UP)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setColor(getColor(R.color.primary))
            .setContentTitle(getString(R.string.notification_request_title))
            .setContentText(getString(R.string.notification_request_text, DeviceDetailsStore(this).displayName(record)))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.unlock_request_card_user, challenge.userDisplayName)))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 250, 200, 250))
            .setOngoing(false) // CRITICAL: Ongoing notifications are suppressed from floating by Android & MIUI!
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setFullScreenIntent(contentIntent, true)
            .addAction(
                0,
                getString(R.string.notification_action_unlock),
                unlockPendingIntent
            )
            .addAction(
                0,
                getString(R.string.notification_action_cancel),
                cancelPendingIntent
            )
            .build()

        // MIUI / HyperOS heads-up floating banner support via reflection & extras
        try {
            val extraNotification = headsUpNotification.javaClass.getField("extraNotification").get(headsUpNotification)
            val setFloatTime = extraNotification.javaClass.getMethod("setFloatTime", Int::class.javaPrimitiveType)
            setFloatTime.invoke(extraNotification, 10000)
        } catch (_: Exception) {}
        try {
            headsUpNotification.extras.putBoolean("miui.float", true)
            headsUpNotification.extras.putBoolean("miui.show_floating", true)
            headsUpNotification.extras.putBoolean("miui.enable_float", true)
        } catch (_: Exception) {}

        val notificationManager = NotificationManagerCompat.from(this)
        try {
            notificationManager.notify(NOTIFICATION_ID_CHALLENGE, headsUpNotification)
        } catch (e: SecurityException) {
            SafeLogger.w(TAG, "Notification permission missing when showing heads-up notification")
        }
    }

    private fun dismissHeadsUpNotification() {
        val notificationManager = NotificationManagerCompat.from(this)
        try {
            notificationManager.cancel(NOTIFICATION_ID_CHALLENGE)
        } catch (_: Exception) {}
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_APP
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_LISTENER_SERVICE)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setColor(getColor(R.color.primary))
            .setContentTitle(getString(R.string.notification_service_title))
            .setContentText(getString(R.string.notification_service_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Clean up legacy channel IDs
            try {
                notificationManager.deleteNotificationChannel("wslp_unlock_heads_up_channel")
                notificationManager.deleteNotificationChannel("wslp_unlock_heads_up_channel_v2")
            } catch (_: Exception) {}

            val serviceChannel = NotificationChannel(
                CHANNEL_LISTENER_SERVICE,
                getString(R.string.notification_channel_service_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_service_desc)
                setShowBadge(false)
            }

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST)
                .build()

            val unlockChannel = NotificationChannel(
                CHANNEL_UNLOCK_HEADS_UP,
                getString(R.string.notification_channel_request_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_request_desc)
                setSound(soundUri, audioAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 200, 250)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableLights(true)
                lightColor = Color.BLUE
                setBypassDnd(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(unlockChannel)
        }
    }

    suspend fun stopListeningAndCleanup() {
        isListening = false
        stopPassiveListener()
        passiveJob?.join()
        synchronized(connectionLock) { pendingAddresses.clear() }
        connectionJob?.cancelAndJoin()
        connectionJob = null
        BleUnlockScanManager.stopScan(this)
        dismissHeadsUpNotification()
        UnlockSessionManager.cancelActiveSession(
            reasonCode = CancelMessage.REASON_SYSTEM_CANCELLED,
            reasonText = "Service stopped"
        )
    }

    override fun onDestroy() {
        SafeLogger.i(TAG, "BluetoothUnlockService onDestroy")
        isListening = false
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        dismissHeadsUpNotification()
        runBlocking {
            try {
                withTimeout(1500L) {
                    stopListeningAndCleanup()
                }
            } catch (e: Exception) {
                SafeLogger.w(TAG, "Cleanup in onDestroy timed out or failed: ${e.message}")
            }
        }
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "BluetoothUnlockService"
        private val PHONE_LISTENER_UUID = UUID.fromString("9b3f4a10-7c22-4e89-80b1-5d9c71a3d0f3")
        const val CHANNEL_LISTENER_SERVICE = "wslp_listener_service_channel"
        const val CHANNEL_UNLOCK_HEADS_UP = "wslp_unlock_heads_up_v4"

        const val NOTIFICATION_ID_SERVICE = 1001
        const val NOTIFICATION_ID_CHALLENGE = 2001

        const val ACTION_START_LISTENING = "com.windowslockpin.companion.ACTION_START_LISTENING"
        const val ACTION_BLE_WAKE = "com.windowslockpin.companion.ACTION_BLE_WAKE"
        const val ACTION_STOP_LISTENING = "com.windowslockpin.companion.ACTION_STOP_LISTENING"
        const val ACTION_CANCEL_REQUEST = "com.windowslockpin.companion.ACTION_CANCEL_REQUEST"
        const val ACTION_CONFIRM_UNLOCK = "com.windowslockpin.companion.ACTION_CONFIRM_UNLOCK"
        const val ACTION_OPEN_UNLOCK_REQUEST = "com.windowslockpin.companion.ACTION_OPEN_UNLOCK_REQUEST"
        const val ACTION_OPEN_APP = "com.windowslockpin.companion.ACTION_OPEN_APP"

        const val EXTRA_REQUEST_ID = "extra_request_id"
        const val EXTRA_BLE_PC_ADDRESSES = "extra_ble_pc_addresses"

        private const val REQUEST_CODE_UNLOCK = 101
        private const val REQUEST_CODE_CANCEL = 102
        private const val REQUEST_CODE_CONTENT = 103

        private const val CONNECT_TIMEOUT_MS = 8_000L         // connect attempt timeout
        private const val FIRST_FRAME_TIMEOUT_MS = 5_000L
        private const val CONNECTION_WINDOW_MS = 45_000L // connect + challenge TTL + result confirmation
    }
}
