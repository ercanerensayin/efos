package com.ercan.otomakro

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlin.math.sqrt

/** Ön planda çalışan servis: sistem olaylarını dinler ve motora iletir. */
class MacroService : Service() {

    companion object {
        fun refresh(ctx: Context) {
            val i = Intent(ctx, MacroService::class.java)
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                Store.log(ctx, "Servis başlatılamadı: ${e.message}")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var receiverOn = false
    private var wifiCb: ConnectivityManager.NetworkCallback? = null
    private var wifiConnected = false
    private var lastSsid = ""
    private var shakeOn = false
    private var lastShake = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (lvl < 0 || sc <= 0) return
                    val pct = lvl * 100 / sc
                    val prev = Engine.lastBattery
                    if (pct != prev) {
                        Engine.lastBattery = pct
                        if (prev >= 0) Engine.fire(c, "battery_level", mapOf("level" to "$pct", "prev" to "$prev"))
                    }
                }
                Intent.ACTION_POWER_CONNECTED -> Engine.fire(c, "power", mapOf("state" to "connected"))
                Intent.ACTION_POWER_DISCONNECTED -> Engine.fire(c, "power", mapOf("state" to "disconnected"))
                Intent.ACTION_SCREEN_ON -> Engine.fire(c, "screen", mapOf("state" to "on"))
                Intent.ACTION_SCREEN_OFF -> Engine.fire(c, "screen", mapOf("state" to "off"))
                Intent.ACTION_USER_PRESENT -> Engine.fire(c, "screen", mapOf("state" to "unlocked"))
                Intent.ACTION_HEADSET_PLUG -> {
                    if (isInitialStickyBroadcast) return
                    val st = if (i.getIntExtra("state", 0) == 1) "plugged" else "unplugged"
                    Engine.fire(c, "headset", mapOf("state" to st))
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    val on = i.getBooleanExtra("state", false)
                    Engine.fire(c, "airplane", mapOf("state" to if (on) "on" else "off"))
                }
            }
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values[0]
            val y = e.values[1]
            val z = e.values[2]
            val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
            if (g >= 2.0f) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastShake > 1500) {
                    lastShake = now
                    Engine.fire(this@MacroService, "shake", mapOf("g" to g.toString()))
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        Notif.ensure(this)
        goForeground()
        registerSystem()
        registerWifi()
        Store.log(this, "Servis başladı")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        updateShake()
        Scheduler.rescheduleAll(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (receiverOn) {
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
        }
        wifiCb?.let {
            try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        if (shakeOn) getSystemService(SensorManager::class.java)?.unregisterListener(sensorListener)
        super.onDestroy()
    }

    private fun goForeground() {
        val count = Store.all(this).count { it.enabled }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, Notif.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("OtoMakro etkin")
            .setContentText("$count makro dinleniyor")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun registerSystem() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, f)
        }
        receiverOn = true
    }

    private fun registerWifi() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        wifiConnected = Sys.wifi(this)
        if (wifiConnected) lastSsid = Sys.ssid(this)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.postDelayed({
                    if (!wifiConnected) {
                        wifiConnected = true
                        lastSsid = Sys.ssid(this@MacroService)
                        Engine.fire(this@MacroService, "wifi", mapOf("state" to "connected", "ssid" to lastSsid))
                    }
                }, 1500)
            }

            override fun onLost(network: Network) {
                handler.postDelayed({
                    if (wifiConnected && !Sys.wifi(this@MacroService)) {
                        wifiConnected = false
                        Engine.fire(this@MacroService, "wifi", mapOf("state" to "disconnected", "ssid" to lastSsid))
                    }
                }, 1000)
            }
        }
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb
            )
            wifiCb = cb
        } catch (e: Exception) {
            Store.log(this, "Wi-Fi dinlenemiyor: ${e.message}")
        }
    }

    private fun updateShake() {
        val need = Store.all(this).any { m -> m.enabled && m.triggers.any { it.type == "shake" } }
        val sm = getSystemService(SensorManager::class.java) ?: return
        if (need && !shakeOn) {
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            sm.registerListener(sensorListener, acc, SensorManager.SENSOR_DELAY_UI)
            shakeOn = true
        } else if (!need && shakeOn) {
            sm.unregisterListener(sensorListener)
            shakeOn = false
        }
    }
}
