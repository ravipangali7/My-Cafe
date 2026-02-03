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

        // Pass incoming order payload from full-screen intent to Flutter
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "getPendingIncomingOrder") {
                val data = pendingIncomingOrderData
                pendingIncomingOrderData = null
                if (data != null) {
                    result.success(HashMap(data))
                } else {
                    result.success(null)
                }
            } else {
                result.notImplemented()
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

    private fun captureIncomingOrderExtras(intent: Intent?) {
        val extras = intent?.extras ?: return
        val type = extras.getString("type") ?: return
        if (type != "incoming_order") return
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
