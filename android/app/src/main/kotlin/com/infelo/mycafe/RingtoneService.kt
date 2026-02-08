package com.infelo.mycafe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that plays a loud alarm ringtone when an incoming order arrives.
 * 
 * Uses ALARM audio stream to ensure maximum volume and bypass Do Not Disturb.
 * The ringtone loops continuously until the service is stopped (accept/reject/timeout).
 * 
 * This service is started by MyCafeFirebaseMessagingService when a new order arrives
 * and is stopped by OrderActionReceiver when the user accepts or rejects.
 */
class RingtoneService : Service() {

    companion object {
        private const val TAG = "RingtoneService"
        private const val CHANNEL_ID = "ringtone_service_channel"
        private const val NOTIFICATION_ID = 3001
        
        // Intent extras
        const val EXTRA_ORDER_ID = "order_id"
        const val EXTRA_CUSTOMER_NAME = "customer_name"
        
        /** True while ringtone is playing; used so we don't restart sound when more orders arrive. */
        @Volatile
        var isPlaying: Boolean = false
            private set
        
        internal fun markPlaying() { isPlaying = true }
        internal fun markStopped() { isPlaying = false }
        
        // Vibration pattern: wait, vibrate, pause, vibrate, pause, vibrate (repeating)
        private val VIBRATION_PATTERN = longArrayOf(0, 1000, 500, 1000, 500, 1000)
        
        /**
         * Helper to start the ringtone service.
         * If the service is already running and playing, a new intent just updates the notification.
         */
        fun start(context: Context, orderId: String, customerName: String) {
            val intent = Intent(context, RingtoneService::class.java).apply {
                putExtra(EXTRA_ORDER_ID, orderId)
                putExtra(EXTRA_CUSTOMER_NAME, customerName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Helper to stop the ringtone service
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, RingtoneService::class.java))
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var originalVolume: Int = 0
    private var audioManager: AudioManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "RingtoneService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val orderId = intent?.getStringExtra(EXTRA_ORDER_ID) ?: ""
        val customerName = intent?.getStringExtra(EXTRA_CUSTOMER_NAME) ?: "Customer"
        
        // If already playing (e.g. another order arrived), only update notification; do not restart sound
        if (isPlaying && mediaPlayer != null && mediaPlayer?.isPlaying == true) {
            Log.d(TAG, "RingtoneService already playing, updating notification for order #$orderId")
            val notification = createForegroundNotification(orderId, customerName)
            startForeground(NOTIFICATION_ID, notification)
            return START_NOT_STICKY
        }
        
        Log.d(TAG, "RingtoneService onStartCommand")
        
        // Start as foreground service with notification
        val notification = createForegroundNotification(orderId, customerName)
        startForeground(NOTIFICATION_ID, notification)
        
        // Start ringtone and vibration
        startRingtone()
        startVibration()
        
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "RingtoneService destroyed")
        stopRingtone()
        stopVibration()
        super.onDestroy()
    }

    /**
     * Creates the notification channel for the foreground service.
     * This is required for Android O and above.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Incoming Order Ringtone",
                NotificationManager.IMPORTANCE_LOW // Low importance - just for foreground service requirement
            ).apply {
                description = "Plays ringtone for incoming orders"
                setShowBadge(false)
                setSound(null, null) // No sound - we handle audio ourselves
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Creates a minimal notification for the foreground service.
     * This notification is required by Android for foreground services.
     */
    private fun createForegroundNotification(orderId: String, customerName: String): Notification {
        // Intent to open the app when notification is tapped
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("type", "incoming_order")
            putExtra("order_id", orderId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Incoming Order")
            .setContentText("New order from $customerName")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * Starts playing the custom order alert sound using ALARM audio stream for maximum volume.
     * Uses app raw resource order_alert.mp3. Sets volume to maximum and loops until stopped.
     */
    private fun startRingtone() {
        try {
            // Get audio manager and save original volume
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            originalVolume = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0
            
            // Set alarm volume to maximum
            val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 7
            audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
            
            // Custom order alert sound from app raw resources
            val alertUri = Uri.parse("android.resource://$packageName/${R.raw.order_alert}")
            Log.d(TAG, "Starting custom order alert with URI: $alertUri")
            
            // Create and configure MediaPlayer with ALARM audio stream
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM) // ALARM stream bypasses DND
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@RingtoneService, alertUri)
                isLooping = true // Loop until stopped
                prepare()
                start()
            }
            RingtoneService.markPlaying()
            Log.d(TAG, "Order alert started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting order alert: ${e.message}", e)
        }
    }

    /**
     * Stops the ringtone and restores original volume.
     */
    private fun stopRingtone() {
        try {
            RingtoneService.markStopped()
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
            mediaPlayer = null
            
            // Restore original volume
            audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
            
            Log.d(TAG, "Ringtone stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ringtone: ${e.message}", e)
        }
    }

    /**
     * Starts vibration with a repeating pattern.
     */
    private fun startVibration() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Repeat from index 0 (start of pattern)
                    vibrator?.vibrate(
                        VibrationEffect.createWaveform(VIBRATION_PATTERN, 0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(VIBRATION_PATTERN, 0)
                }
                Log.d(TAG, "Vibration started")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting vibration: ${e.message}", e)
        }
    }

    /**
     * Stops vibration.
     */
    private fun stopVibration() {
        try {
            vibrator?.cancel()
            vibrator = null
            Log.d(TAG, "Vibration stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping vibration: ${e.message}", e)
        }
    }
}
