package com.example.rcremotecontrol // Make sure this matches your package name

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class RelayService : Service() {

    // --- CONFIGURATION ---
    // !!! CHANGE THIS to your laptop's IP address !!!
    private val SERVER_IP = "78.62.27.149"  // Your laptop's real IP
    private val SERVER_PORT = 4210
    private val ESP32_PORT = 4210
    // --- END CONFIGURATION ---

    private lateinit var wakeLock: PowerManager.WakeLock
    private val isRunning = AtomicBoolean(false)
    private var networkingThread: Thread? = null

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "RelayServiceChannel"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val esp32Ip = intent?.getStringExtra("esp32_ip") ?: "0.0.0.0"
            android.util.Log.d("RelayService", "Service starting with ESP32 IP: $esp32Ip")

            createNotificationChannel()
            val notification = createNotification("Starting relay...")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }

            // Prevent the CPU from sleeping
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RcRemoteControl::RelayWakelockTag")
            wakeLock.acquire()

            sendStatus("Starting...")
            isRunning.set(true)
            networkingThread = Thread { 
                try {
                    runRelayLogic(esp32Ip)
                } catch (e: Exception) {
                    android.util.Log.e("RelayService", "Error in relay logic: ${e.message}", e)
                    sendStatus("Error: ${e.message}")
                }
            }
            networkingThread?.start()

            android.util.Log.d("RelayService", "Service started successfully")
            return START_NOT_STICKY
            
        } catch (e: Exception) {
            android.util.Log.e("RelayService", "Error in onStartCommand: ${e.message}", e)
            sendStatus("Error: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun runRelayLogic(esp32Ip: String) {
        var serverSocket: DatagramSocket? = null
        var esp32Socket: DatagramSocket? = null
        
        try {
            android.util.Log.d("RelayService", "Creating UDP sockets...")
            
            serverSocket = DatagramSocket()
            esp32Socket = DatagramSocket()
            serverSocket.soTimeout = 1000 // 1 second timeout for faster response
            esp32Socket.soTimeout = 1000

            android.util.Log.d("RelayService", "Resolving addresses...")
            val serverAddress = InetAddress.getByName(SERVER_IP)
            val esp32Address = InetAddress.getByName(esp32Ip)
            
            android.util.Log.d("RelayService", "Server address: ${serverAddress.hostAddress}")
            android.util.Log.d("RelayService", "ESP32 address: ${esp32Address.hostAddress}")

            val buffer = ByteArray(1024)
            var lastKeepAlive = 0L

            sendStatus("Running")
            updateNotification("RC Relay is active.")

            // Start bidirectional relay threads
            val serverToEsp32Thread = Thread {
                while (isRunning.get()) {
                    try {
                        val packetFromServer = DatagramPacket(buffer, buffer.size)
                        serverSocket.receive(packetFromServer)

                        if (packetFromServer.length > 0) {
                            val message = String(packetFromServer.data, 0, packetFromServer.length)
                            
                            // Forward RC controls to ESP32
                            val forwardPacket = DatagramPacket(
                                packetFromServer.data,
                                packetFromServer.length,
                                esp32Address,
                                ESP32_PORT
                            )
                            esp32Socket.send(forwardPacket)
                            
                            android.util.Log.d("RelayService", "Server→ESP32: $message")
                        }
                    } catch (e: Exception) {
                        // Timeout is normal, just continue
                    }
                }
            }

            val esp32ToServerThread = Thread {
                while (isRunning.get()) {
                    try {
                        val packetFromEsp32 = DatagramPacket(buffer, buffer.size)
                        esp32Socket.receive(packetFromEsp32)

                        if (packetFromEsp32.length > 0) {
                            val message = String(packetFromEsp32.data, 0, packetFromEsp32.length)
                            
                            // Forward telemetry back to server
                            val forwardPacket = DatagramPacket(
                                packetFromEsp32.data,
                                packetFromEsp32.length,
                                serverAddress,
                                SERVER_PORT
                            )
                            serverSocket.send(forwardPacket)
                            
                            android.util.Log.d("RelayService", "ESP32→Server: $message")
                        }
                    } catch (e: Exception) {
                        // Timeout is normal, just continue
                    }
                }
            }

            // Send initial keep-alive immediately
            val initialKeepAliveMsg = "PHONE_ALIVE".toByteArray()
            val initialPacket = DatagramPacket(initialKeepAliveMsg, initialKeepAliveMsg.size, serverAddress, SERVER_PORT)
            serverSocket.send(initialPacket)
            lastKeepAlive = System.currentTimeMillis()
            android.util.Log.d("RelayService", "Sent initial PHONE_ALIVE")

            serverToEsp32Thread.start()
            esp32ToServerThread.start()

            // Keep-alive loop
            while (isRunning.get()) {
                if (System.currentTimeMillis() - lastKeepAlive > 15000) {
                    val keepAliveMsg = "PHONE_ALIVE".toByteArray()
                    val packet = DatagramPacket(keepAliveMsg, keepAliveMsg.size, serverAddress, SERVER_PORT)
                    serverSocket.send(packet)
                    lastKeepAlive = System.currentTimeMillis()
                    android.util.Log.d("RelayService", "Sent keep-alive")
                }
                Thread.sleep(1000)
            }
            
        } catch (e: Exception) {
            sendStatus("Error: ${e.message}")
            updateNotification("RC Relay stopped due to an error.")
        } finally {
            serverSocket?.close()
            esp32Socket?.close()
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning.set(false)
        networkingThread?.interrupt()
        if (this::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
        sendStatus("Stopped")
        super.onDestroy()
    }

    // This is required for a bound service, but we are using a started service.
    override fun onBind(intent: Intent?): IBinder? = null

    // --- Helper functions for notifications and status updates ---

    private fun sendStatus(message: String) {
        val intent = Intent("RelayStatusUpdate").apply {
            putExtra("status_message", message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "RC Relay Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("RC Relay Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Use system icon instead
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, notification)
    }
}