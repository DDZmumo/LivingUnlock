package com.windowslockpin.companion.time

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.windowslockpin.companion.core.model.SafeLogger
import com.windowslockpin.companion.core.time.ApplicationTime
import com.windowslockpin.companion.core.time.NetworkTimeClient
import com.windowslockpin.companion.core.time.DatagramRoute
import java.net.InetAddress
import java.net.Inet4Address
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TimePreparation(val requestId: Long, val pcName: String, val message: String)

object AppTime {
    var clock=ApplicationTime(System.currentTimeMillis(),SystemClock::elapsedRealtime)
        private set
    @Volatile private var applicationContext: Context?=null
    val network=NetworkTimeClient(SystemClock::elapsedRealtime) { host,preferDirect ->
        val manager=applicationContext?.getSystemService(ConnectivityManager::class.java)
        val physical=if(preferDirect)manager?.allNetworks?.firstOrNull { network ->
            val caps=manager.getNetworkCapabilities(network)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } else null
        val resolved=try { physical?.getAllByName(host)?.firstOrNull { it is Inet4Address } } catch (_: Exception) { null }
        if(physical!=null && resolved!=null)DatagramRoute(resolved) { socket ->
            // Some always-on VPNs disallow bypass. A bind refusal falls back to
            // the system route; valid NTP data, not network flags, determines success.
            try { physical.bindSocket(socket) } catch (_: Exception) {}
        } else DatagramRoute(InetAddress.getByName(host))
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var started=false
    private val _preparation=MutableStateFlow<TimePreparation?>(null)
    val preparation=_preparation.asStateFlow()
    private var cancelPreparationAction: (suspend () -> Unit)?=null

    @Synchronized fun start(context: Context) {
        if(started)return
        started=true
        val app=context.applicationContext
        applicationContext=app
        scope.launch {
            repeat(3) { attempt ->
                if(attempt>0)delay(if(attempt==1)30_000L else 60_000L)
                try {
                    if(network.refresh(clock,online(app))) {
                        SafeLogger.i("AppTime","Application clock calibrated; source=${clock.snapshot().source}")
                        return@launch
                    }
                } catch (_: Exception) { /* Retain the system-seeded monotonic baseline. */ }
            }
            SafeLogger.i("AppTime","Three startup attempts ended; retaining the existing internal clock")
        }
    }
    fun online(context: Context): Boolean = try {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val capabilities=manager.getNetworkCapabilities(manager.activeNetwork)
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
    } catch (_: Exception) { false }
    fun show(requestId: Long,pcName: String,message: String) {
        _preparation.value=TimePreparation(requestId,pcName,message)
    }
    fun clear(requestId: Long) {
        if(_preparation.value?.requestId==requestId){_preparation.value=null;cancelPreparationAction=null}
    }
    fun setCancellation(action: suspend () -> Unit){cancelPreparationAction=action}
    suspend fun cancelPreparation(requestId: Long) {
        if(_preparation.value?.requestId!=requestId)return
        val action=cancelPreparationAction
        clear(requestId)
        try { action?.invoke() } catch (_: Exception) {}
    }
    internal fun resetForTests(initialUtcMs: Long) {
        clock=ApplicationTime(initialUtcMs,SystemClock::elapsedRealtime)
        _preparation.value=null
        cancelPreparationAction=null
    }
    suspend fun calibrateManually(context: Context): Boolean=withContext(Dispatchers.IO) {
        repeat(2) { attempt ->
            if(network.refresh(clock,online(context),oneRetry=attempt==1,forceNewSample=true))return@withContext true
        }
        false
    }
}
