package com.infelo.mycafe

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * BroadcastReceiver that handles Accept and Reject actions from the incoming order notification.
 * 
 * - Accept: Stops ringtone, dismisses notification, opens Flutter app with order_id
 * - Reject: Stops ringtone, dismisses notification, calls backend API to reject order
 * 
 * This receiver is triggered when user taps the action buttons on the notification
 * or the IncomingCallActivity buttons.
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
        
        // API configuration
        private const val BASE_URL = "https://mycafe.sewabyapar.com"
        
        // OkHttp client with reasonable timeouts
        private val httpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }
        
        /**
         * Creates a PendingIntent for the Accept action.
         */
        fun createAcceptIntent(
            context: Context,
            orderId: String,
            notificationId: Int,
            requestCode: Int
        ): android.app.PendingIntent {
            val intent = Intent(context, OrderActionReceiver::class.java).apply {
                action = ACTION_ACCEPT
                putExtra(EXTRA_ORDER_ID, orderId)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
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
            requestCode: Int
        ): android.app.PendingIntent {
            val intent = Intent(context, OrderActionReceiver::class.java).apply {
                action = ACTION_REJECT
                putExtra(EXTRA_ORDER_ID, orderId)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
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
        
        Log.d(TAG, "Received action: $action for order: $orderId")
        
        // Stop ringtone service
        RingtoneService.stop(context)
        
        // Dismiss the notification
        dismissNotification(context, notificationId)
        
        // Close the IncomingCallActivity if it's open
        closeIncomingCallActivity(context)
        
        when (action) {
            ACTION_ACCEPT -> handleAccept(context, orderId)
            ACTION_REJECT -> handleReject(context, orderId)
        }
    }

    /**
     * Handles the Accept action:
     * - Opens the Flutter app with the order_id so the user can process the order
     * - Navigates to order detail page
     */
    private fun handleAccept(context: Context, orderId: String) {
        Log.d(TAG, "Accepting order: $orderId")
        
        // Launch Flutter app with order payload and navigate to order detail
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or 
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("type", "incoming_order")
            putExtra("order_id", orderId)
            putExtra("action", "accept")
            putExtra("navigate_to", "order_detail") // Navigate to order detail after accept
        }
        context.startActivity(intent)
    }

    /**
     * Handles the Reject action:
     * - Calls the backend API to reject the order
     * - Opens Flutter app and navigates to order detail page after API call
     */
    private fun handleReject(context: Context, orderId: String) {
        Log.d(TAG, "Rejecting order: $orderId")
        
        // Call backend API to reject the order, then navigate to order detail
        rejectOrderApi(context, orderId)
    }

    /**
     * Calls the backend API to reject the order.
     * POST /api/orders/{order_id}/edit/ with status=rejected
     * After API completes (success or failure), navigates to order detail in Flutter.
     */
    private fun rejectOrderApi(context: Context, orderId: String) {
        val url = "$BASE_URL/api/orders/$orderId/edit/"
        
        val requestBody = FormBody.Builder()
            .add("status", "rejected")
            .add("reject_reason", "Rejected from notification")
            .build()
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()
        
        Log.d(TAG, "Calling reject API: $url")
        
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to reject order $orderId: ${e.message}", e)
                // Even if API fails, navigate to order detail so user can see the order
                navigateToOrderDetail(context, orderId)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Order $orderId rejected successfully")
                    } else {
                        Log.e(TAG, "Failed to reject order $orderId: ${response.code} ${response.message}")
                    }
                    // Navigate to order detail regardless of API result
                    navigateToOrderDetail(context, orderId)
                }
            }
        })
    }
    
    /**
     * Opens the Flutter app and navigates to order detail page.
     * Called after reject API completes.
     */
    private fun navigateToOrderDetail(context: Context, orderId: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("type", "order_rejected")
            putExtra("order_id", orderId)
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
