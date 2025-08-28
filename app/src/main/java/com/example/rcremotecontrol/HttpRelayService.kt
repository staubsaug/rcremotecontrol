package com.example.rcremotecontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class HttpRelayService : Service() {

    // --- CONFIGURATION ---
    // Cloud server base URL (Render). Example: "https://rc-remote-control-server.onrender.com"
    private val SERVER_URL = "https://rc-remote-control-server.onrender.com"
    private val ESP32_PORT = 4210
    // --- END CONFIGURATION ---

    private lateinit var wakeLock: PowerManager.WakeLock
    private val isRunning = AtomicBoolean(false)
    private var networkingThread: Thread? = null
    private var httpServerThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    @Volatile private var esp32IpForRelay: String = "0.0.0.0"

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "HttpRelayServiceChannel"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val esp32Ip = intent?.getStringExtra("esp32_ip") ?: "0.0.0.0"
            android.util.Log.d("HttpRelayService", "Service starting with ESP32 IP: $esp32Ip")
            esp32IpForRelay = esp32Ip

            createNotificationChannel()
            val notification = createNotification("Starting HTTP relay...")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }

            // Prevent the CPU from sleeping
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RcRemoteControl::HttpRelayWakelockTag")
            wakeLock.acquire()

            sendStatus("Starting...")
            isRunning.set(true)
            
            // Optional: legacy local HTTP server (not needed with cloud pull model)
            // Commented out to reduce surface; uncomment if you still need local HTTP push.
            // httpServerThread = Thread { 
            //     try {
            //         startHttpServer()
            //     } catch (e: Exception) {
            //         android.util.Log.e("HttpRelayService", "Error in HTTP server: ${e.message}", e)
            //         sendStatus("Error: ${e.message}")
            //     }
            // }
            // httpServerThread?.start()

            // Start networking thread for ESP32 communication (poll cloud, send UDP to ESP32)
            networkingThread = Thread { 
                try {
                    runRelayLogic(esp32Ip)
                } catch (e: Exception) {
                    android.util.Log.e("HttpRelayService", "Error in relay logic: ${e.message}", e)
                    sendStatus("Error: ${e.message}")
                }
            }
            networkingThread?.start()

            android.util.Log.d("HttpRelayService", "Service started successfully")
            return START_NOT_STICKY
            
        } catch (e: Exception) {
            android.util.Log.e("HttpRelayService", "Error in onStartCommand: ${e.message}", e)
            sendStatus("Error: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun startHttpServer() {
        try {
            serverSocket = ServerSocket(8080)
            android.util.Log.d("HttpRelayService", "HTTP server listening on port 8080")
            
            while (isRunning.get()) {
                val clientSocket = serverSocket?.accept()
                if (clientSocket != null) {
                    Thread {
                        handleHttpRequest(clientSocket)
                    }.start()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HttpRelayService", "HTTP server error: ${e.message}", e)
        }
    }

    private fun handleHttpRequest(clientSocket: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val output = clientSocket.getOutputStream()
            
            // Read HTTP request
            val requestLine = input.readLine()
            if (requestLine != null) {
                val parts = requestLine.split(" ")
                val method = parts[0]
                val path = parts[1]
                
                android.util.Log.d("HttpRelayService", "HTTP $method $path")
                
                // Read headers
                var contentLength = 0
                var line: String?
                while (input.readLine().also { line = it } != null && line != "") {
                    if (line?.startsWith("Content-Length: ") == true) {
                        contentLength = line?.substring(16)?.toIntOrNull() ?: 0
                    }
                }
                
                // Read body if present
                val body = if (contentLength > 0) {
                    val bodyChars = CharArray(contentLength)
                    input.read(bodyChars, 0, contentLength)
                    String(bodyChars)
                } else ""
                
                // Handle different endpoints
                val response = when {
                    path.startsWith("/rc_controls") -> handleRcControls(body)
                    path.startsWith("/command") -> handleCommand(body)
                    else -> "HTTP/1.1 404 Not Found\r\n\r\nNot found"
                }
                
                output.write(response.toByteArray())
            }
            
            clientSocket.close()
            
        } catch (e: Exception) {
            android.util.Log.e("HttpRelayService", "Error handling HTTP request: ${e.message}", e)
        }
    }

    private fun handleRcControls(body: String): String {
        try {
            // Parse rc_controls parameter
            val params = body.split("&")
            for (param in params) {
                if (param.startsWith("rc_controls=")) {
                    val rcControlsJson = param.substring(12) // Remove "rc_controls="
                    android.util.Log.d("HttpRelayService", "Received RC controls: $rcControlsJson")
                    
                    // Forward to ESP32
                    forwardToEsp32(rcControlsJson)
                    break
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HttpRelayService", "Error handling RC controls: ${e.message}", e)
        }
        
        return "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"status\":\"ok\"}"
    }

    private fun handleCommand(body: String): String {
        try {
            // Parse command parameter
            val params = body.split("&")
            for (param in params) {
                if (param.startsWith("command=")) {
                    val command = param.substring(8) // Remove "command="
                    android.util.Log.d("HttpRelayService", "Received command: $command")
                    
                    // Forward to ESP32
                    forwardToEsp32(command)
                    break
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HttpRelayService", "Error handling command: ${e.message}", e)
        }
        
        return "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"status\":\"ok\"}"
    }

    private fun forwardToEsp32(message: String) {
        try {
            val socket = java.net.Socket("10.214.29.39", ESP32_PORT)
            val output = socket.getOutputStream()
            output.write(message.toByteArray())
            output.flush()
            socket.close()
            android.util.Log.d("HttpRelayService", "Forwarded to ESP32: $message")
        } catch (e: Exception) {
            android.util.Log.e("HttpRelayService", "Error forwarding to ESP32: ${e.message}", e)
        }
    }

    private fun runRelayLogic(esp32Ip: String) {
        try {
            android.util.Log.d("HttpRelayService", "Starting HTTP relay logic...")
            
            // Send initial keep-alive to server
            sendKeepAlive()
            
            sendStatus("Running")
            updateNotification("HTTP RC Relay is active.")

            // UDP socket for ESP32 forwarding
            val udpSocket = java.net.DatagramSocket()
            val espAddr = java.net.InetAddress.getByName(esp32Ip)

            var lastKeepAlive = 0L
            val keepAliveIntervalMs = 15000L
            val pollIntervalMs = 33L // ~30 Hz

            while (isRunning.get()) {
                val now = System.currentTimeMillis()
                if (now - lastKeepAlive > keepAliveIntervalMs) {
                    sendKeepAlive()
                    lastKeepAlive = now
                }

                try {
                    val json = fetchControlsJson()
                    if (json.isNotEmpty()) {
                        val bytes = json.toByteArray()
                        val packet = java.net.DatagramPacket(bytes, bytes.size, espAddr, ESP32_PORT)
                        udpSocket.send(packet)
                        android.util.Log.d("HttpRelayService", "Sent controls to ESP32 ${espAddr.hostAddress}:$ESP32_PORT -> $json")
                    }
                } catch (e: Exception) {
                    // network hiccup; continue loop
                }

                Thread.sleep(pollIntervalMs)
            }
            
        } catch (e: Exception) {
            android.util.Log.e("HttpRelayService", "Error in relay logic: ${e.message}", e)
            sendStatus("Error: ${e.message}")
        }
    }

    private fun sendKeepAlive() {
        try {
            val url = URL("$SERVER_URL/phone/keepalive")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                android.util.Log.d("HttpRelayService", "Keep-alive sent successfully")
            } else {
                android.util.Log.w("HttpRelayService", "Keep-alive failed with code: $responseCode")
            }
            
            connection.disconnect()
            
        } catch (e: Exception) {
            android.util.Log.e("HttpRelayService", "Error sending keep-alive: ${e.message}", e)
        }
    }

    private fun fetchControlsJson(): String {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("$SERVER_URL/api/get_controls")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000

            val code = connection.responseCode
            if (code == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()
                sb.toString()
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "HTTP RC Relay Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when the HTTP RC relay service is running"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("HTTP RC Relay Service")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(1, createNotification(message))
    }

    private fun sendStatus(status: String) {
        val intent = Intent("RELAY_STATUS")
        intent.putExtra("status", status)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            android.util.Log.e("HttpRelayService", "Error closing server socket: ${e.message}", e)
        }
        
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
        
        android.util.Log.d("HttpRelayService", "Service destroyed")
    }
}



