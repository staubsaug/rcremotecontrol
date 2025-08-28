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
    // !!! CHANGE THIS to your server's public IP (VPS or VPN Laptop) !!!
    private val SERVER_IP = "88.77.66.55"
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
        val esp32Ip = intent?.getStringExtra("esp32_ip") ?: "0.0.0.0"

        createNotificationChannel()
        val notification = createNotification("Starting relay...")
        startForeground(1, notification)

        // Prevent the CPU from sleeping
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RcRemoteControl::RelayWakelockTag")
        wakeLock.acquire()

        sendStatus("Starting...")
        isRunning.set(true)
        networkingThread = Thread { runRelayLogic(esp32Ip) }
        networkingThread?.start()

        return START_NOT_STICKY
    }

    private fun runRelayLogic(esp32Ip: String) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = 3000 // 3 second timeout for receives

            val serverAddress = InetAddress.getByName(SERVER_IP)
            val esp32Address = InetAddress.getByName(esp32Ip)

            val buffer = ByteArray(1024)
            var lastKeepAlive = 0L

            sendStatus("Running")
            updateNotification("Relay is active.")

            while (isRunning.get()) {
                // Send keep-alive packet to server every 15 seconds
                if (System.currentTimeMillis() - lastKeepAlive > 15000) {
                    val keepAliveMsg = "PHONE_ALIVE".toByteArray()
                    val packet = DatagramPacket(keepAliveMsg, keepAliveMsg.size, serverAddress, SERVER_PORT)
                    socket.send(packet)
                    lastKeepAlive = System.currentTimeMillis()
                }

                // Listen for packets from the server and forward them to the ESP32
                try {
                    val packetFromServer = DatagramPacket(buffer, buffer.size)
                    socket.receive(packetFromServer)

                    if (packetFromServer.length > 0) {
                        val forwardPacket = DatagramPacket(
                            packetFromServer.data,
                            packetFromServer.length,
                            esp32Address,
                            ESP32_PORT
                        )
                        socket.send(forwardPacket)
                    }
                } catch (e: Exception) {
                    // Timeout is normal, just continue the loop
                }
            }
        } catch (e: Exception) {
            sendStatus("Error: ${e.message}")
            updateNotification("Relay stopped due to an error.")
        } finally {
            socket?.close()
            stopSelf() // Stop the service when the loop finishes
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
            .setSmallIcon(R.mipmap.ic_launcher) // Make sure you have this icon
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, notification)
    }
}