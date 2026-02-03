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
     * Launches IncomingCallActivity directly and shows notification as backup.
     * Works for both foreground and background scenarios.
     */
    private fun handleIncomingOrder(payload: Map<String, String>) {
        val orderId = payload["order_id"] ?: ""
        val customerName = payload["name"] ?: "Customer"
        
        Log.d(TAG, "Incoming order received: #$orderId from $customerName")
        
        // Create notification channel (required for Android O+)
        createIncomingOrderChannel()
        
        // Start ringtone service for loud alarm sound
        RingtoneService.start(this, orderId, customerName)
        
        // Launch IncomingCallActivity directly
        // This ensures the full-screen UI appears in both foreground and background
        launchIncomingCallActivity(payload)
        
        // Show notification as backup (visible in notification tray)
        showIncomingOrderNotification(payload)
    }
    
    /**
     * Launches IncomingCallActivity directly.
     * This is called for all incoming orders to ensure the full-screen UI appears
     * regardless of whether the app is in foreground, background, or killed.
     */
    private fun launchIncomingCallActivity(payload: Map<String, String>) {
        val orderId = payload["order_id"] ?: ""
        val customerName = payload["name"] ?: "Customer"
        val tableNo = payload["table_no"] ?: ""
        val total = payload["total"] ?: "0"
        val itemsCount = payload["items_count"] ?: "0"
        val phone = payload["phone"] ?: ""
        val items = payload["items"] ?: "" // JSON array of order items
        
        Log.d(TAG, "Launching IncomingCallActivity for order #$orderId")
        
        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            // Required for starting activity from service
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            
            // Pass order data
            putExtra(IncomingCallActivity.EXTRA_ORDER_ID, orderId)
            putExtra(IncomingCallActivity.EXTRA_CUSTOMER_NAME, customerName)
            putExtra(IncomingCallActivity.EXTRA_TABLE_NO, tableNo)
            putExtra(IncomingCallActivity.EXTRA_TOTAL, total)
            putExtra(IncomingCallActivity.EXTRA_ITEMS_COUNT, itemsCount)
            putExtra(IncomingCallActivity.EXTRA_PHONE, phone)
            putExtra(IncomingCallActivity.EXTRA_ITEMS, items) // Order items JSON
            putExtra(IncomingCallActivity.EXTRA_NOTIFICATION_ID, NOTIFICATION_ID_INCOMING_ORDER)
        }
        
        startActivity(intent)
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
     * Shows the incoming order notification with full-screen intent.
     * 
     * The notification includes:
     * - Full-screen intent: Launches IncomingCallActivity when device is locked/screen off
     * - Accept action: Uses OrderActionReceiver to open Flutter app
     * - Reject action: Uses OrderActionReceiver to call API and dismiss
     * - CATEGORY_CALL: Bypasses Do Not Disturb
     * - PRIORITY_MAX: Highest priority for immediate attention
     */
    private fun showIncomingOrderNotification(payload: Map<String, String>) {
        val orderId = payload["order_id"] ?: ""
        val customerName = payload["name"] ?: "Customer"
        val tableNo = payload["table_no"] ?: ""
        val total = payload["total"] ?: "0"
        val itemsCount = payload["items_count"] ?: "0"
        val phone = payload["phone"] ?: ""
        val items = payload["items"] ?: "" // JSON array of order items
        
        // Full-screen intent: Launch IncomingCallActivity (shows over lock screen)
        val fullScreenIntent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            putExtra(IncomingCallActivity.EXTRA_ORDER_ID, orderId)
            putExtra(IncomingCallActivity.EXTRA_CUSTOMER_NAME, customerName)
            putExtra(IncomingCallActivity.EXTRA_TABLE_NO, tableNo)
            putExtra(IncomingCallActivity.EXTRA_TOTAL, total)
            putExtra(IncomingCallActivity.EXTRA_ITEMS_COUNT, itemsCount)
            putExtra(IncomingCallActivity.EXTRA_PHONE, phone)
            putExtra(IncomingCallActivity.EXTRA_ITEMS, items) // Order items JSON for detail display
            putExtra(IncomingCallActivity.EXTRA_NOTIFICATION_ID, NOTIFICATION_ID_INCOMING_ORDER)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_FULL_SCREEN,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Content intent: Same as full-screen (tap notification opens call activity)
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_CONTENT,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Accept action: Uses OrderActionReceiver
        val acceptPendingIntent = OrderActionReceiver.createAcceptIntent(
            this,
            orderId,
            NOTIFICATION_ID_INCOMING_ORDER,
            REQUEST_CODE_ACCEPT
        )

        // Reject action: Uses OrderActionReceiver
        val rejectPendingIntent = OrderActionReceiver.createRejectIntent(
            this,
            orderId,
            NOTIFICATION_ID_INCOMING_ORDER,
            REQUEST_CODE_REJECT
        )

        // Build notification content
        val contentTitle = "Incoming Order #$orderId"
        val contentText = buildString {
            append(customerName)
            if (tableNo.isNotEmpty()) append(" · Table $tableNo")
            append(" · ₹$total")
        }

        // Build the notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSubText("$itemsCount items")
            // High priority and call category for maximum visibility
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Full-screen intent for lock screen
            .setFullScreenIntent(fullScreenPendingIntent, true)
            // Content intent for tapping notification
            .setContentIntent(contentPendingIntent)
            // Action buttons
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Reject",
                rejectPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_send,
                "Accept",
                acceptPendingIntent
            )
            // Keep showing until action is taken
            .setOngoing(true)
            .setAutoCancel(false)
            // Allow multiple alerts
            .setOnlyAlertOnce(false)
            // Vibration pattern (backup, channel also has it)
            .setVibrate(VIBRATION_PATTERN)
            // No sound - RingtoneService handles it
            .setSound(null)
            // Colorize for visual distinction
            .setColorized(true)
            .setColor(0xFF3B82F6.toInt()) // Blue accent
            .build()

        // Show the notification
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
        private const val REQUEST_CODE_ACCEPT = 1003
        private const val REQUEST_CODE_REJECT = 1004
        
        // Vibration pattern: wait, vibrate, pause, vibrate, pause, vibrate
        private val VIBRATION_PATTERN = longArrayOf(0, 500, 200, 500, 200, 500)
    }
}
