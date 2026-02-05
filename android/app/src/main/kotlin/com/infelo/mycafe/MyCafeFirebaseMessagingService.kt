package com.infelo.mycafe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.RemoteMessage
import io.flutter.plugins.firebase.messaging.FlutterFirebaseMessagingService

/**
 * Firebase Cloud Messaging Service for handling incoming order notifications.
 * 
 * IMPORTANT: This service extends FlutterFirebaseMessagingService (not FirebaseMessagingService)
 * to override the Flutter plugin's default handler. This ensures our native code handles
 * incoming_order messages even when the app is killed or in background.
 * 
 * Flow:
 * 1. FCM data message arrives
 * 2. If type=incoming_order or dismiss_incoming: Handle natively (notification, ringtone)
 * 3. For other message types: Delegate to Flutter via super.onMessageReceived()
 * 
 * The notification channel is configured with:
 * - IMPORTANCE_HIGH for heads-up display
 * - No sound (handled by RingtoneService with ALARM stream for louder audio)
 * - Vibration enabled
 * - CATEGORY_CALL to bypass Do Not Disturb
 */
class MyCafeFirebaseMessagingService : FlutterFirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val type = data["type"]
        
        Log.d(TAG, "FCM message received, type=$type, data keys=${data.keys}")
        
        // Handle incoming_order and dismiss_incoming natively
        // All other messages are delegated to Flutter
        when (type) {
            "incoming_order" -> {
                Log.d(TAG, "Handling incoming_order natively")
                handleIncomingOrder(data)
                // Don't call super - we handle this entirely in native
            }
            "dismiss_incoming" -> {
                Log.d(TAG, "Handling dismiss_incoming natively")
                handleDismissIncoming(data)
                // Don't call super - we handle this entirely in native
            }
            else -> {
                // Delegate to Flutter for other message types
                Log.d(TAG, "Delegating message to Flutter: type=$type")
                super.onMessageReceived(remoteMessage)
            }
        }
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM token refreshed: ${token.take(20)}...")
        // Flutter plugin will also receive this; no need to duplicate
    }

    /**
     * Handles incoming order FCM message.
     * Starts ringtone and shows a large notification (no accept/reject buttons).
     * Tapping the notification opens MainActivity with full order payload so Flutter
     * can show the React order-alert page in WebView. Sound continues until Flutter
     * calls stopOrderAlertSound after the vendor accepts/rejects in the app.
     */
    private fun handleIncomingOrder(payload: Map<String, String>) {
        val orderId = payload["order_id"] ?: ""
        val customerName = payload["name"] ?: "Customer"
        
        Log.d(TAG, "Incoming order received: #$orderId from $customerName")
        
        // Create notification channel (required for Android O+)
        createIncomingOrderChannel()
        
        // Start ringtone service for loud alarm sound (plays until Flutter stops it)
        RingtoneService.start(this, orderId, customerName)
        
        // Show notification only (no full-screen activity). Tap opens MainActivity with payload.
        showIncomingOrderNotification(payload)
    }

    /**
     * Handles dismiss_incoming FCM message.
     * Dismisses the notification and stops the ringtone.
     */
    private fun handleDismissIncoming(payload: Map<String, String>) {
        val orderId = payload["order_id"] ?: ""
        Log.d(TAG, "Dismissing incoming order notification for: $orderId")
        
        // Stop ringtone service
        RingtoneService.stop(this)
        
        // Cancel notification
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_INCOMING_ORDER)
        
        // Close IncomingCallActivity if open
        sendBroadcast(Intent(IncomingCallActivity.ACTION_CLOSE))
    }

    /**
     * Creates the notification channel for incoming orders.
     * 
     * Configuration:
     * - IMPORTANCE_HIGH: Shows as heads-up notification
     * - No sound: We use RingtoneService with ALARM stream instead
     * - Vibration: Enabled for attention
     * - Bypass DND: Uses CATEGORY_CALL to bypass Do Not Disturb
     */
    private fun createIncomingOrderChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Incoming Orders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority incoming order alerts"
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
                // No sound on channel - RingtoneService handles audio with ALARM stream
                setSound(null, null)
                // Allow showing on lock screen
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                // Bypass DND for calls
                setBypassDnd(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Shows the incoming order notification (no accept/reject buttons).
     * Tap opens MainActivity with full order payload so Flutter can show React order-alert page.
     * Full-screen intent opens MainActivity on lock screen for same behavior.
     */
    private fun showIncomingOrderNotification(payload: Map<String, String>) {
        val orderId = payload["order_id"] ?: ""
        val customerName = payload["name"] ?: "Customer"
        val tableNo = payload["table_no"] ?: ""
        val total = payload["total"] ?: "0"
        val itemsCount = payload["items_count"] ?: "0"
        val phone = payload["phone"] ?: ""
        val items = payload["items"] ?: ""

        // Content and full-screen intent: open MainActivity with full order payload
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("type", "incoming_order")
            putExtra("order_id", orderId)
            putExtra("name", customerName)
            putExtra("table_no", tableNo)
            putExtra("phone", phone)
            putExtra("total", total)
            putExtra("items_count", itemsCount)
            putExtra("items", items)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_CONTENT,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_FULL_SCREEN,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentTitle = "New order"
        val contentText = buildString {
            append("Order #$orderId · $customerName")
            if (tableNo.isNotEmpty()) append(" · Table $tableNo")
            append(" · ₹$total")
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSubText("$itemsCount items · Tap to open")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setVibrate(VIBRATION_PATTERN)
            .setSound(null)
            .setColorized(true)
            .setColor(0xFF3B82F6.toInt())
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_INCOMING_ORDER, notification)
        Log.d(TAG, "Notification shown for order #$orderId")
    }

    companion object {
        private const val TAG = "MyCafeFCM"
        
        // Notification channel ID
        private const val CHANNEL_ID = "incoming_order_v3"
        
        // Notification ID (single notification, replaced on new order)
        const val NOTIFICATION_ID_INCOMING_ORDER = 2001
        
        // PendingIntent request codes
        private const val REQUEST_CODE_FULL_SCREEN = 1001
        private const val REQUEST_CODE_CONTENT = 1002
        
        // Vibration pattern: wait, vibrate, pause, vibrate, pause, vibrate
        private val VIBRATION_PATTERN = longArrayOf(0, 500, 200, 500, 200, 500)
    }
}
