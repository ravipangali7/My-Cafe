package com.infelo.mycafe

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver that handles Accept and Reject actions from the incoming order notification.
 *
 * Stops ringtone, dismisses notification, opens Flutter app with order_id and action (accept/reject).
 * Flutter calls React's handleIncomingOrderAction; React calls backend to update order status.
 *
 * Triggered when user taps the action buttons on the notification or IncomingCallActivity.
 */
class OrderActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OrderActionReceiver"
        
        // Action constants
        const val ACTION_ACCEPT = "com.infelo.mycafe.ACTION_ACCEPT_ORDER"
        const val ACTION_REJECT = "com.infelo.mycafe.ACTION_REJECT_ORDER"
        
        // Intent extras
        const val EXTRA_ORDER_ID = "order_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_ACTION_TOKEN = "action_token"
        
        /**
         * Creates a PendingIntent for the Accept action.
         */
        fun createAcceptIntent(
            context: Context,
            orderId: String,
            notificationId: Int,
            requestCode: Int,
            actionToken: String?
        ): android.app.PendingIntent {
            val intent = Intent(context, OrderActionReceiver::class.java).apply {
                action = ACTION_ACCEPT
                putExtra(EXTRA_ORDER_ID, orderId)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                actionToken?.let { putExtra(EXTRA_ACTION_TOKEN, it) }
            }
            return android.app.PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }
        
        /**
         * Creates a PendingIntent for the Reject action.
         */
        fun createRejectIntent(
            context: Context,
            orderId: String,
            notificationId: Int,
            requestCode: Int,
            actionToken: String?
        ): android.app.PendingIntent {
            val intent = Intent(context, OrderActionReceiver::class.java).apply {
                action = ACTION_REJECT
                putExtra(EXTRA_ORDER_ID, orderId)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                actionToken?.let { putExtra(EXTRA_ACTION_TOKEN, it) }
            }
            return android.app.PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val orderId = intent.getStringExtra(EXTRA_ORDER_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val actionToken = intent.getStringExtra(EXTRA_ACTION_TOKEN)
        
        Log.d(TAG, "Received action: $action for order: $orderId")
        
        // Stop ringtone service
        RingtoneService.stop(context)
        
        // Dismiss the notification
        dismissNotification(context, notificationId)
        
        // Close the IncomingCallActivity if it's open
        closeIncomingCallActivity(context)
        
        when (action) {
            ACTION_ACCEPT -> handleAccept(context, orderId, actionToken)
            ACTION_REJECT -> handleReject(context, orderId, actionToken)
        }
    }

    /**
     * Handles the Accept action:
     * - Opens Flutter app with action=accept; Flutter tells React, React calls backend to update status.
     */
    private fun handleAccept(context: Context, orderId: String, actionToken: String?) {
        Log.d(TAG, "Accepting order: $orderId")
        navigateToOrderDetail(context, orderId, "incoming_order", "accept")
    }

    /**
     * Handles the Reject action:
     * - Opens Flutter app with action=reject; Flutter tells React, React calls backend to update status.
     */
    private fun handleReject(context: Context, orderId: String, actionToken: String?) {
        Log.d(TAG, "Rejecting order: $orderId")
        navigateToOrderDetail(context, orderId, "order_rejected", "reject")
    }

    /**
     * Opens the Flutter app with order_id and action (accept/reject).
     * Flutter calls React's handleIncomingOrderAction; React calls backend to update order status.
     */
    private fun navigateToOrderDetail(context: Context, orderId: String, type: String, action: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("type", type)
            putExtra("order_id", orderId)
            putExtra("action", action)
            putExtra("navigate_to", "order_detail")
        }
        context.startActivity(intent)
    }

    /**
     * Dismisses the notification with the given ID.
     */
    private fun dismissNotification(context: Context, notificationId: Int) {
        if (notificationId != 0) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(notificationId)
            Log.d(TAG, "Dismissed notification: $notificationId")
        }
    }

    /**
     * Sends a broadcast to close the IncomingCallActivity if it's open.
     */
    private fun closeIncomingCallActivity(context: Context) {
        val closeIntent = Intent(IncomingCallActivity.ACTION_CLOSE)
        context.sendBroadcast(closeIntent)
    }
}
