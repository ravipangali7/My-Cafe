package com.infelo.mycafe

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.PermissionRequest
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.HashMap

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "incoming_order"
        private var pendingIncomingOrderData: Map<String, String>? = null
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Pass incoming order payload and stop order alert sound
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getPendingIncomingOrder" -> {
                    val data = pendingIncomingOrderData
                    pendingIncomingOrderData = null
                    if (data != null) {
                        result.success(HashMap(data))
                    } else {
                        result.success(null)
                    }
                }
                "stopOrderAlertSound" -> {
                    RingtoneService.stop(this@MainActivity)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureIncomingOrderExtras(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureIncomingOrderExtras(intent)
    }

    /**
     * Captures intent extras for incoming order or accept/reject action.
     * Stores when we have order_id and either type (incoming_order / order_rejected) or action (accept / reject)
     * so Flutter can call handleIncomingOrderAction and React can update order status.
     */
    private fun captureIncomingOrderExtras(intent: Intent?) {
        val extras = intent?.extras ?: return
        val orderId = extras.getString("order_id") ?: return
        val type = extras.getString("type")
        val action = extras.getString("action")
        // Accept: from OrderActionReceiver (type=incoming_order, action=accept) or similar
        // Reject: from OrderActionReceiver (type=order_rejected, action=reject)
        val hasRelevantData = (type == "incoming_order" || type == "order_rejected") || (action == "accept" || action == "reject")
        if (!hasRelevantData) return
        val data = mutableMapOf<String, String>()
        extras.keySet()?.forEach { key ->
            val value = extras.get(key)?.toString()
            if (value != null) data[key] = value
        }
        if (data.isNotEmpty()) {
            pendingIncomingOrderData = data
        }
    }
}
