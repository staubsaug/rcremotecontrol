package com.example.rcremotecontrol // Make sure this matches your package name

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

    // UI Elements
    private lateinit var esp32IpEditText: EditText
    private lateinit var toggleButton: Button
    private lateinit var statusTextView: TextView

    private var isServiceRunning = false

    // This receiver listens for messages (like status updates) from our RelayService
    private val messageReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val message = intent.getStringExtra("status_message")
            statusTextView.text = "Status: $message"

            // Update button state based on service status
            if (message == "Running") {
                isServiceRunning = true
                toggleButton.text = "Stop Relay"
            } else if (message == "Stopped" || message?.startsWith("Error") == true) {
                isServiceRunning = false
                toggleButton.text = "Start Relay"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("MainActivity", "onCreate started")
        
        try {
            setContentView(R.layout.activity_main)
            android.util.Log.d("MainActivity", "setContentView completed")
            
            esp32IpEditText = findViewById(R.id.esp32IpEditText)
            toggleButton = findViewById(R.id.toggleButton)
            statusTextView = findViewById(R.id.statusTextView)
            
            // Set default ESP32 IP
            esp32IpEditText.setText("10.214.29.39")
            
            android.util.Log.d("MainActivity", "findViewById completed")

            toggleButton.setOnClickListener {
                android.util.Log.d("MainActivity", "Button clicked, isServiceRunning: $isServiceRunning")
                if (isServiceRunning) {
                    android.util.Log.d("MainActivity", "Stopping relay service")
                    stopRelayService()
                } else {
                    android.util.Log.d("MainActivity", "Starting relay service")
                    startRelayService()
                }
            }
            
            android.util.Log.d("MainActivity", "onCreate completed successfully")
        } catch (e: Exception) {
            // Log the error and show a simple message
            android.util.Log.e("MainActivity", "Error in onCreate: ${e.message}", e)
            try {
                statusTextView?.text = "Error: ${e.message}"
            } catch (e2: Exception) {
                android.util.Log.e("MainActivity", "Error setting error text: ${e2.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Register to receive messages.
        LocalBroadcastManager.getInstance(this).registerReceiver(
            messageReceiver,
            IntentFilter("RelayStatusUpdate")
        )
    }

    override fun onPause() {
        // Unregister since the activity is not visible.
        LocalBroadcastManager.getInstance(this).unregisterReceiver(messageReceiver)
        super.onPause()
    }

    private fun startRelayService() {
        try {
            val esp32Ip = esp32IpEditText.text.toString().trim()
            if (esp32Ip.isBlank()) {
                statusTextView.text = "Status: Please enter ESP32 IP"
                return
            }

            // Basic IP validation
            if (!esp32Ip.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) {
                statusTextView.text = "Status: Invalid IP format"
                return
            }

            android.util.Log.d("MainActivity", "Starting relay service with ESP32 IP: $esp32Ip")
            
            val serviceIntent = Intent(this, HttpRelayService::class.java).apply {
                putExtra("esp32_ip", esp32Ip)
            }
            
            // This starts the service in the foreground, which is required for long-running tasks.
            startForegroundService(serviceIntent)
            
            android.util.Log.d("MainActivity", "Service start command sent")
            
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error starting service: ${e.message}", e)
            statusTextView.text = "Status: Error starting service"
        }
    }

    private fun stopRelayService() {
        val serviceIntent = Intent(this, HttpRelayService::class.java)
        stopService(serviceIntent)
    }
}