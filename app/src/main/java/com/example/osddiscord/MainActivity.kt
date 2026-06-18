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
    private var savedWebhookUrls = listOf<String>()

    private lateinit var etWebhookUrls: EditText
    private lateinit var btnSaveWebhooks: Button
    private lateinit var tvStatus: TextView
    private lateinit var btnToggleMonitoring: Button
    private lateinit var swBackground: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etWebhookUrls = findViewById(R.id.etWebhookUrls)
        btnSaveWebhooks = findViewById(R.id.btnSaveWebhooks)
        tvStatus = findViewById(R.id.tvStatus)
        btnToggleMonitoring = findViewById(R.id.btnToggleMonitoring)
        swBackground = findViewById(R.id.swBackground)

        val sharedPreferences = getSharedPreferences("OSD_Prefs", Context.MODE_PRIVATE)
        val rawUrls = sharedPreferences.getString("webhook_urls", "") ?: ""
        savedWebhookUrls = rawUrls.split("\n").filter { it.isNotBlank() }
        etWebhookUrls.setText(rawUrls)
        
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
        } else {
            handler.post(monitorRunnable)
        }
    }

    private fun stopMonitoring() {
        updateUI(false)
        stopService(Intent(this, MonitoringService::class.java))
        handler.removeCallbacksAndMessages(null)
    }

    private fun updateUI(active: Boolean) {
        if (active) {
            tvStatus.text = "Status: Active & Monitoring..."
            tvStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
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
            handler.postDelayed(this, 4000)
        }
    }

    private fun checkOsdState() {
        val request = Request.Builder().url(osdUrl).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvStatus.text = "Status: Error connecting to local OSD"
                    tvStatus.setTextColor(android.graphics.Color.RED)
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { responseBody ->
                    try {
                        val json = JSONObject(responseBody)
                        val currentState = json.optInt("alarmState", 0)
                        if (currentState == 2 && lastAlarmState != 2) {
                            sendDiscordWebhook()
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

    private fun sendDiscordWebhook() {
        val jsonPayload = JSONObject().apply {
            put("content", "🚨 **URGENT: SEIZURE ALARM DETECTED!** 🚨\nOpenSeizureDetector has triggered an active emergency state.")
            put("username", "OSD Emergency Bridge")
        }.toString()
        val requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
        savedWebhookUrls.forEach { url ->
            val request = Request.Builder().url(url).post(requestBody).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) { response.close() }
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
