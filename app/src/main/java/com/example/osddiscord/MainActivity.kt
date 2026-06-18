package com.example.osddiscord

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private val osdUrl = "http://127.0.0.1:8080/data"
    
    private var isMonitoring = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastAlarmState = 0
    private var lastNotificationTime = 0L
    private var savedWebhookUrls = listOf<String>()

    private lateinit var etWebhookUrls: EditText
    private lateinit var btnSaveWebhooks: Button
    private lateinit var btnTestWebhooks: Button
    private lateinit var tvStatus: TextView
    private lateinit var btnToggleMonitoring: Button
    private lateinit var swBackground: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etWebhookUrls = findViewById(R.id.etWebhookUrls)
        btnSaveWebhooks = findViewById(R.id.btnSaveWebhooks)
        btnTestWebhooks = findViewById(R.id.btnTestWebhooks)
        tvStatus = findViewById(R.id.tvStatus)
        btnToggleMonitoring = findViewById(R.id.btnToggleMonitoring)
        swBackground = findViewById(R.id.swBackground)

        val sharedPreferences = getSharedPreferences("OSD_Prefs", Context.MODE_PRIVATE)
        val rawUrls = sharedPreferences.getString("webhook_urls", "") ?: ""
        savedWebhookUrls = rawUrls.split("\n").filter { it.isNotBlank() }
        etWebhookUrls.setText(rawUrls)
        
        // Initialize state to avoid missing first alarm if it's already active
        lastAlarmState = 0
        lastNotificationTime = 0L

        swBackground.isChecked = sharedPreferences.getBoolean("run_in_background", true)

        btnSaveWebhooks.setOnClickListener {
            val inputUrls = etWebhookUrls.text.toString().trim()
            val urlList = inputUrls.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            
            val valid = urlList.all { it.startsWith("https://discord.com/api/webhooks/") }
            
            if (urlList.isNotEmpty() && valid) {
                sharedPreferences.edit().putString("webhook_urls", inputUrls).apply()
                savedWebhookUrls = urlList
                Toast.makeText(this, "Webhooks Saved! (${urlList.size} found)", Toast.LENGTH_SHORT).show()
            } else if (urlList.isEmpty()) {
                Toast.makeText(this, "Please enter at least one URL", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "One or more URLs are invalid.", Toast.LENGTH_LONG).show()
            }
        }

        swBackground.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("run_in_background", isChecked).apply()
        }

        btnTestWebhooks.setOnClickListener {
            if (savedWebhookUrls.isEmpty()) {
                Toast.makeText(this, "Save at least one Webhook URL first.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            sendDiscordWebhook("🔔 **TEST MESSAGE**: This is a test notification from your OSD Discord Bridge.")
            Toast.makeText(this, "Sending test message...", Toast.LENGTH_SHORT).show()
        }

        // Check if service is already running to set UI state
        if (isServiceRunning(MonitoringService::class.java)) {
            isMonitoring = true
            updateUI(true)
        }

        btnToggleMonitoring.setOnClickListener {
            if (savedWebhookUrls.isEmpty()) {
                Toast.makeText(this, "Save at least one Webhook URL first.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                    return@setOnClickListener
                }
            }

            isMonitoring = !isMonitoring
            if (isMonitoring) {
                startMonitoring()
            } else {
                stopMonitoring()
            }
        }
    }

    private fun startMonitoring() {
        updateUI(true)
        if (swBackground.isChecked) {
            val intent = Intent(this, MonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        // Always run the local monitoring loop to update the UI while the app is open
        handler.post(monitorRunnable)
    }

    private fun stopMonitoring() {
        updateUI(false)
        stopService(Intent(this, MonitoringService::class.java))
        handler.removeCallbacksAndMessages(null)
        // Reset state so it's ready for a fresh start next time
        lastAlarmState = 0
        lastNotificationTime = 0L
    }

    private fun updateUI(active: Boolean) {
        if (active) {
            tvStatus.text = "Status: Connecting to OSD..."
            tvStatus.setTextColor(android.graphics.Color.parseColor("#FFA500")) // Orange while connecting
            btnToggleMonitoring.text = "Stop Monitoring"
            btnToggleMonitoring.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F44336")))
        } else {
            tvStatus.text = "Status: Stopped"
            tvStatus.setTextColor(android.graphics.Color.parseColor("#777777"))
            btnToggleMonitoring.text = "Start Monitoring"
            btnToggleMonitoring.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4CAF50")))
        }
    }

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (!isMonitoring) return
            checkOsdState()
            
            // Smart Polling: 1s if Warning/Alarm (1 or 2), 5s if OK (0)
            val nextDelay = if (lastAlarmState > 0) 1000L else 5000L
            handler.postDelayed(this, nextDelay)
        }
    }

    private fun checkOsdState() {
        val request = Request.Builder().url(osdUrl).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    if (isMonitoring) {
                        tvStatus.text = "Status: OSD Not Found (Is it running?)"
                        tvStatus.setTextColor(android.graphics.Color.RED)
                    }
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { responseBody ->
                    try {
                        val json = JSONObject(responseBody)
                        val currentState = json.optInt("alarmState", 0)
                        val currentTime = System.currentTimeMillis()

                        // Webhooks are only sent from MainActivity if the background service is disabled.
                        // When background service is enabled, it handles the webhooks independently.
                        if (!swBackground.isChecked) {
                            val isNewAlarm = currentState == 2 && lastAlarmState != 2
                            val isPersistentAlarm = currentState == 2 && (currentTime - lastNotificationTime > 30000)

                            if (isNewAlarm || isPersistentAlarm) {
                                val prefix = if (isPersistentAlarm && !isNewAlarm) "⚠️ **REMINDER**: " else ""
                                sendDiscordWebhook("${prefix}🚨 **URGENT: SEIZURE ALARM DETECTED!** 🚨\nOpenSeizureDetector has triggered an active emergency state.")
                                lastNotificationTime = currentTime
                            }
                        }

                        lastAlarmState = currentState
                        runOnUiThread {
                            if (isMonitoring) {
                                val stateString = when(currentState) {
                                    1 -> "Warning State"
                                    2 -> "ALARM TRIGGERED"
                                    else -> "Normal (OK)"
                                }
                                tvStatus.text = "Status: Connected (OSD: $stateString)"
                                tvStatus.setTextColor(if (currentState == 2) android.graphics.Color.RED else android.graphics.Color.parseColor("#4CAF50"))
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
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

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    override fun onDestroy() {
        if (!swBackground.isChecked) {
            handler.removeCallbacksAndMessages(null)
        }
        super.onDestroy()
    }
}
