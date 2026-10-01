package com.windowslockpin.companion.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.windowslockpin.companion.core.model.*
import com.windowslockpin.companion.core.transport.TransportConnection
import com.windowslockpin.companion.core.transport.TransportClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class BluetoothRfcommConnection(
    private val socket: BluetoothSocket
) : TransportConnection {

    private val inputStream: InputStream = socket.inputStream
    private val outputStream: OutputStream = socket.outputStream
    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frames = Channel<Frame>(8)
    @Volatile private var disconnected = false

    init {
        // Keep observing EOF while the UI waits for biometric confirmation.
        // Consumers still receive each frame exactly once, including UNLOCK_RESULT.
        readerScope.launch {
            try {
                while (!disconnected) frames.send(Frame.readFromStream(inputStream))
            } catch (e: Exception) {
                frames.close(e)
            } finally {
                disconnected = true
                frames.close()
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    override val isConnected: Boolean
        get() = !disconnected && socket.isConnected

    override suspend fun sendFrame(frame: Frame): Unit = withContext(Dispatchers.IO) {
        if (!socket.isConnected) {
            throw IOException("RFCOMM socket is not connected")
        }
        val bytes = frame.toByteArray()
        outputStream.write(bytes)
        outputStream.flush()
    }

    override suspend fun receiveFrame(): Frame = frames.receive()

    override fun close() {
        disconnected = true
        frames.close()
        readerScope.cancel()
        try {
            socket.close()
        } catch (e: Exception) {
            SafeLogger.w(TAG, "Error closing RFCOMM socket: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BluetoothRfcommConn"
    }
}

class BluetoothRfcommClient(
    private val bluetoothAdapter: BluetoothAdapter?
) : TransportClient {

    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun connect(macAddress: BluetoothMacAddress): TransportConnection = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter
            ?: throw IOException("Bluetooth adapter is unavailable on this device")

        if (!adapter.isEnabled) {
            throw IOException("Bluetooth is disabled")
        }

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(macAddress.value)
        } catch (e: Exception) {
            throw IOException("Invalid remote Bluetooth MAC address: ${macAddress.value}", e)
        }

        SafeLogger.i(TAG, "Initiating RFCOMM connection to target device with service UUID: ${ProtocolConstants.RFCOMM_SERVICE_UUID}")

        val socket = device.createRfcommSocketToServiceRecord(ProtocolConstants.RFCOMM_SERVICE_UUID)
        val connectContext = currentCoroutineContext()
        suspendCancellableCoroutine { continuation ->
            // Socket.connect() blocks; closing the socket makes the caller's timeout effective.
            continuation.invokeOnCancellation { try { socket.close() } catch (_: Exception) {} }
            CoroutineScope(connectContext).launch {
                try {
                    adapter.cancelDiscovery()
                    socket.connect()
                    SafeLogger.i(TAG, "RFCOMM connection established successfully")
                    val connection = BluetoothRfcommConnection(socket)
                    continuation.resume(connection) { try { connection.close() } catch (_: Exception) {} }
                } catch (e: Exception) {
                    try { socket.close() } catch (_: Exception) {}
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(IOException("Failed to connect RFCOMM socket to device: ${e.message}", e)))
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "BluetoothRfcommClient"
    }
}
