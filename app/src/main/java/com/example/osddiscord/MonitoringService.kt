package com.example.osddiscord

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class MonitoringService : Service() {

    private val client = OkHttpClient()
    private val osdUrl = "http://127.0.0.1:8080/data"
    private val handler = Handler(Looper.getMainLooper())
    private var lastAlarmState = 0
    private var lastNotificationTime = 0L
    private var isRunning = false
    private var savedWebhookUrls = listOf<String>()

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            checkOsdState()

            // Smart Polling: 1s if Warning/Alarm (1 or 2), 5s if OK (0)
            val nextDelay = if (lastAlarmState > 0) 1000L else 5000L
            handler.postDelayed(this, nextDelay)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rawUrls = getSharedPreferences("OSD_Prefs", Context.MODE_PRIVATE)
            .getString("webhook_urls", "") ?: ""
        savedWebhookUrls = rawUrls.split("\n").filter { it.isNotBlank() }

        // Reset state on service start to ensure first alarm is caught
        lastAlarmState = 0
        lastNotificationTime = 0L

        if (!isRunning) {
            isRunning = true
            startForeground(1, createNotification("Connecting to OSD..."))
            handler.post(monitorRunnable)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkOsdState() {
        val request = Request.Builder().url(osdUrl).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                updateNotification("Status: OSD Not Found (Is it running?)")
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { responseBody ->
                    try {
                        val json = JSONObject(responseBody)
                        val currentState = json.optInt("alarmState", 0)

                        val currentTime = System.currentTimeMillis()
                        val isNewAlarm = currentState == 2 && lastAlarmState != 2
                        val isPersistentAlarm = currentState == 2 && (currentTime - lastNotificationTime > 30000)

                        if (isNewAlarm || isPersistentAlarm) {
                            val prefix = if (isPersistentAlarm && !isNewAlarm) "⚠️ **REMINDER**: " else ""
                            sendDiscordWebhook("${prefix}🚨 **URGENT: SEIZURE ALARM DETECTED!** 🚨\nOpenSeizureDetector has triggered an active emergency state.")
                            lastNotificationTime = currentTime
                        }

                        lastAlarmState = currentState

                        val stateString = when(currentState) {
                            1 -> "Warning State"
                            2 -> "ALARM TRIGGERED"
                            else -> "Normal (OK)"
                        }
                        updateNotification("Status: Connected (OSD: $stateString)")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        })
    }

    private fun sendDiscordWebhook(message: String) {
        val jsonPayload = JSONObject().apply {
            put("content", message)
            put("username", "OSD Emergency Bridge")
        }.toString()

        val requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
        
        savedWebhookUrls.forEach { url ->
            val request = Request.Builder().url(url).post(requestBody).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("OSD_Bridge", "Failed to send to $url", e)
                }
                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        Log.e("OSD_Bridge", "Error code ${response.code} from $url")
                    }
                    response.close()
                }
            })
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "OSD_MONITOR_CHANNEL",
                "OSD Monitor Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "OSD_MONITOR_CHANNEL")
            .setContentTitle("OSD Discord Bridge")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, notification)
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
