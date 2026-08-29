package com.mojibo.airplaytv.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.mojibo.airplaytv.OnScreenLog

/**
 * Announces this device on the local network so an iPhone's Control Center shows it
 * as an AirPlay target. Two services must be advertised:
 *
 *  - _airplay._tcp   -> screen mirroring / video
 *  - _raop._tcp      -> "Remote Audio Output Protocol", audio-only streaming
 *
 * The TXT records below are the minimum set that real-world AirPlay receivers
 * (UxPlay, RPiPlay) publish. deviceid MUST look like a MAC address (colon separated hex).
 */
class NsdAdvertiser(
    private val context: Context,
    private val deviceName: String = "Mojibo TV",
    private val rtspPort: Int = 7000,
    private val raopPort: Int = 5000,
) {
    private val nsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val listeners = mutableListOf<NsdManager.RegistrationListener>()

    fun start(deviceId: String) {
        registerAirplay(deviceId)
        registerRaop(deviceId)
    }

    private fun registerAirplay(deviceId: String) {
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = "_airplay._tcp"
            port = rtspPort
            setAttribute("deviceid", deviceId)
            setAttribute("features", "0x5A7FFFF7,0x1E") // capability bitmask incl. video+audio, seen on UxPlay
            setAttribute("model", "AppleTV5,3")
            setAttribute("srcvers", "220.68")
            setAttribute("pi", deviceId)
            setAttribute("vv", "2")
            setAttribute("acl", "0")
            setAttribute("flags", "0x4")
            setAttribute("osvers", "26.0")
            setAttribute("protovers", "1.0")
            setAttribute("model", "AppleTV5,3")
        }
        register(info)
    }

    private fun registerRaop(deviceId: String) {
        // RAOP service name convention is "<deviceid>@<displayname>"
        val info = NsdServiceInfo().apply {
            serviceName = "$deviceId@$deviceName"
            serviceType = "_raop._tcp"
            port = raopPort
            setAttribute("txtvers", "1")
            setAttribute("ch", "2")
            setAttribute("cn", "0,1,2,3")
            setAttribute("et", "0,3,5")
            setAttribute("sv", "false")
            setAttribute("tp", "UDP")
            setAttribute("sm", "false")
            setAttribute("ss", "16")
            setAttribute("sr", "44100")
            setAttribute("pw", "false")
            setAttribute("vn", "3")
            setAttribute("md", "0,1,2")
            setAttribute("vs", "220.68")
            setAttribute("am", "AppleTV3,2")
            setAttribute("fv", "76400.10")
        }
        register(info)
    }

    private fun register(info: NsdServiceInfo) {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(reg: NsdServiceInfo) {
                Log.i(TAG, "Registered ${info.serviceType} as ${reg.serviceName}")
                OnScreenLog.log("NSD OK: ${info.serviceType} registered as ${reg.serviceName}")
            }
            override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Failed to register ${si.serviceType}: $errorCode")
                OnScreenLog.log("NSD FAILED: ${si.serviceType} error=$errorCode")
            }
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
        }
        listeners.add(listener)
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        listeners.forEach {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
        }
        listeners.clear()
    }

    companion object {
        private const val TAG = "NsdAdvertiser"
    }
}
