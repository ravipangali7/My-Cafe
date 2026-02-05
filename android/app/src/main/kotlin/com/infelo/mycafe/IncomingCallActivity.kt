package com.infelo.mycafe

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray

/**
 * Data class representing a single order item with full details.
 * All fields have safe defaults to prevent null pointer exceptions.
 */
data class OrderItem(
    val productName: String = "Unknown Product",
    val variantName: String = "",
    val quantity: String = "1",
    val price: String = "0",           // Final price after discount
    val total: String = "0",
    val originalPrice: String = "0"    // Original price before discount
) {
    /**
     * Check if there's a discount (original price > final price)
     */
    fun hasDiscount(): Boolean {
        return try {
            val original = originalPrice.toDoubleOrNull() ?: 0.0
            val final = price.toDoubleOrNull() ?: 0.0
            original > final && original > 0
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Full-screen call-style Activity that appears when an incoming order arrives.
 * 
 * Features:
 * - Shows over lock screen and turns screen on
 * - Displays full customer info (name, phone, table)
 * - Displays order details with prices (no images for faster loading)
 * - Shows discount prices with strikethrough original price
 * - Slide-to-action gesture: slide right = Accept, slide left = Reject
 * - Modern dark UI design similar to phone call screens
 * - Safe null handling for all data fields
 * 
 * This activity is launched directly from MyCafeFirebaseMessagingService
 * for both foreground and background scenarios.
 */
class IncomingCallActivity : Activity() {

    companion object {
        private const val TAG = "IncomingCallActivity"
        
        // Broadcast action to close this activity
        const val ACTION_CLOSE = "com.infelo.mycafe.CLOSE_INCOMING_CALL"
        
        // Intent extras (matches FCM payload)
        const val EXTRA_ORDER_ID = "order_id"
        const val EXTRA_CUSTOMER_NAME = "name"
        const val EXTRA_TABLE_NO = "table_no"
        const val EXTRA_PHONE = "phone"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_ITEMS_COUNT = "items_count"
        const val EXTRA_ITEMS = "items" // JSON array of order items
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_ACTION_TOKEN = "action_token"
        
        // Slide thresholds
        private const val SLIDE_THRESHOLD_RATIO = 0.35f // 35% of bar width to trigger action
    }

    // Order data with safe defaults
    private var orderId: String = ""
    private var customerName: String = "Customer"
    private var tableNo: String = ""
    private var phone: String = ""
    private var total: String = "0"
    private var itemsCount: String = "0"
    private var itemsJson: String = ""
    private var items: List<OrderItem> = emptyList()
    private var notificationId: Int = 0
    private var actionToken: String? = null

    // Slide state
    private var slideOffset: Float = 0f
    private var slideBarWidth: Float = 0f
    private var isProcessing: Boolean = false

    // Broadcast receiver to close activity when action is taken
    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CLOSE) {
                Log.d(TAG, "Received close broadcast, finishing activity")
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "IncomingCallActivity onCreate")
        
        // Configure window flags to show over lock screen
        configureWindow()
        
        // Extract order data from intent
        extractOrderData(intent)
        
        // Build and set the UI programmatically
        setContentView(buildUI())
        
        // Register broadcast receiver to close activity
        registerCloseReceiver()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractOrderData(intent)
        // Rebuild UI with new data
        setContentView(buildUI())
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterCloseReceiver()
    }

    /**
     * Configures window flags to show over lock screen and turn screen on.
     */
    private fun configureWindow() {
        // For Android O and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            
            // Dismiss keyguard for this activity
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        
        // Keep screen on while this activity is visible
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Extracts order data from the intent extras with safe defaults.
     */
    private fun extractOrderData(intent: Intent?) {
        intent?.extras?.let { extras ->
            orderId = extras.getString(EXTRA_ORDER_ID, "") ?: ""
            customerName = extras.getString(EXTRA_CUSTOMER_NAME, "Customer") ?: "Customer"
            tableNo = extras.getString(EXTRA_TABLE_NO, "") ?: ""
            phone = extras.getString(EXTRA_PHONE, "") ?: ""
            total = extras.getString(EXTRA_TOTAL, "0") ?: "0"
            itemsCount = extras.getString(EXTRA_ITEMS_COUNT, "0") ?: "0"
            itemsJson = extras.getString(EXTRA_ITEMS, "") ?: ""
            notificationId = extras.getInt(EXTRA_NOTIFICATION_ID, 0)
            actionToken = extras.getString(EXTRA_ACTION_TOKEN)
            
            // Parse items JSON
            items = parseItems(itemsJson)
            
            Log.d(TAG, "Order data: id=$orderId, name=$customerName, table=$tableNo, total=$total, items=${items.size}")
        }
    }

    /**
     * Parses the items JSON string into a list of OrderItem objects.
     * JSON format: [{"n":"Product","v":"Variant","q":"1","p":"100","t":"100","op":"120"}, ...]
     * All fields have safe defaults if missing.
     */
    private fun parseItems(itemsJson: String): List<OrderItem> {
        if (itemsJson.isBlank()) return emptyList()
        
        return try {
            val jsonArray = JSONArray(itemsJson)
            (0 until jsonArray.length()).mapNotNull { i ->
                try {
                    val obj = jsonArray.getJSONObject(i)
                    OrderItem(
                        productName = obj.optString("n", "Unknown Product").ifBlank { "Unknown Product" },
                        variantName = obj.optString("v", ""),
                        quantity = obj.optString("q", "1").ifBlank { "1" },
                        price = obj.optString("p", "0").ifBlank { "0" },
                        total = obj.optString("t", "0").ifBlank { "0" },
                        originalPrice = obj.optString("op", obj.optString("p", "0")).ifBlank { obj.optString("p", "0") }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing item at index $i: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing items JSON: ${e.message}")
            emptyList()
        }
    }

    /**
     * Registers the broadcast receiver to close the activity.
     */
    private fun registerCloseReceiver() {
        val filter = IntentFilter(ACTION_CLOSE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(closeReceiver, filter)
        }
    }

    /**
     * Unregisters the broadcast receiver.
     */
    private fun unregisterCloseReceiver() {
        try {
            unregisterReceiver(closeReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver: ${e.message}")
        }
    }

    /**
     * Builds the complete UI programmatically.
     */
    private fun buildUI(): View {
        val context = this
        
        // Root layout - dark gradient background
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#1a1a1a"), Color.parseColor("#0d0d0d"))
            )
        }
        
        // Scrollable content area
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
        }
        
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
        }
        
        // Header: "Incoming order" with icon
        contentLayout.addView(createHeader(context))
        
        // Customer Card
        contentLayout.addView(createCustomerCard(context))
        
        // Order Items Card
        contentLayout.addView(createOrderItemsCard(context))
        
        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView)
        
        // Slide-to-action bar at bottom
        rootLayout.addView(createSlideToActionBar(context))
        
        return rootLayout
    }

    /**
     * Creates the header section with icon and title.
     */
    private fun createHeader(context: Context): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(16))
            
            // Icon row
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                
                addView(TextView(context).apply {
                    text = "🍽️"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                })
                
                addView(TextView(context).apply {
                    text = "  Incoming order"
                    setTextColor(Color.parseColor("#e2e8f0"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
            })
        }
    }

    /**
     * Creates the Customer card with name, phone, and table.
     */
    private fun createCustomerCard(context: Context): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#1e1e1e"))
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(12))
            }
            
            // Card title
            addView(TextView(context).apply {
                text = "CUSTOMER"
                setTextColor(Color.parseColor("#6b7280"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                letterSpacing = 0.1f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(12))
            })
            
            // Name row
            addView(createDetailRow(context, "Name", customerName.ifBlank { "—" }))
            
            // Phone row
            addView(createDetailRow(context, "Phone", phone.ifBlank { "—" }))
            
            // Table row
            addView(createDetailRow(context, "Table", tableNo.ifBlank { "—" }))
        }
    }

    /**
     * Creates the Order Items card with all items, prices, and total.
     */
    private fun createOrderItemsCard(context: Context): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#1e1e1e"))
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(12))
            }
            
            // Card title with order ID
            addView(TextView(context).apply {
                text = if (orderId.isNotBlank()) "ORDER #$orderId" else "ORDER"
                setTextColor(Color.parseColor("#6b7280"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                letterSpacing = 0.1f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(12))
            })
            
            // Individual items
            if (items.isNotEmpty()) {
                items.forEachIndexed { index, item ->
                    addView(createOrderItemRow(context, item))
                    if (index < items.size - 1) {
                        // Add subtle divider between items
                        addView(View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                dp(1)
                            ).apply {
                                setMargins(0, dp(10), 0, dp(10))
                            }
                            setBackgroundColor(Color.parseColor("#2a2a2a"))
                        })
                    }
                }
            } else {
                // Show items count if no detailed items available
                val count = itemsCount.toIntOrNull() ?: 0
                addView(TextView(context).apply {
                    text = if (count > 0) "$count item(s)" else "No items"
                    setTextColor(Color.parseColor("#9ca3af"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                })
            }
            
            // Divider before total
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(2)
                ).apply {
                    setMargins(0, dp(16), 0, dp(12))
                }
                setBackgroundColor(Color.parseColor("#374151"))
            })
            
            // Subtotal row (if we have items)
            if (items.isNotEmpty()) {
                val subtotal = items.sumOf { it.total.toDoubleOrNull() ?: 0.0 }
                addView(createTotalRow(context, "Subtotal", "₹${String.format("%.0f", subtotal)}", false))
            }
            
            // Total row
            addView(createTotalRow(context, "Total", "₹$total", true))
        }
    }

    /**
     * Creates a single order item row with name, prices, and quantity.
     * Note: Images are not shown on native alert to reduce payload size and improve performance.
     */
    private fun createOrderItemRow(context: Context, item: OrderItem): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            
            // Simple bullet point indicator instead of image
            addView(TextView(context).apply {
                text = "•"
                setTextColor(Color.parseColor("#6b7280"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                layoutParams = LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, dp(8), 0)
                }
                gravity = Gravity.CENTER
            })
            
            // Item details container
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                
                // Product name and variant
                addView(TextView(context).apply {
                    text = if (item.variantName.isNotBlank()) {
                        "${item.productName} (${item.variantName})"
                    } else {
                        item.productName
                    }
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 2
                })
                
                // Price row (with discount if applicable)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(2), 0, 0)
                    
                    // Final price
                    addView(TextView(context).apply {
                        text = "₹${item.price}"
                        setTextColor(Color.parseColor("#10b981")) // Green for final price
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    })
                    
                    // Original price with strikethrough if discounted
                    if (item.hasDiscount()) {
                        addView(TextView(context).apply {
                            text = "  ₹${item.originalPrice}"
                            setTextColor(Color.parseColor("#6b7280"))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                        })
                    }
                })
                
                // Quantity × Price = Total
                addView(TextView(context).apply {
                    text = "${item.quantity} × ₹${item.price} = ₹${item.total}"
                    setTextColor(Color.parseColor("#9ca3af"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, dp(2), 0, 0)
                })
            })
        }
    }

    /**
     * Creates a detail row with label and value.
     */
    private fun createDetailRow(context: Context, label: String, value: String): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(8))
            }
            
            addView(TextView(context).apply {
                text = "$label:"
                setTextColor(Color.parseColor("#9ca3af"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            
            addView(TextView(context).apply {
                text = value
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
    }

    /**
     * Creates a total/subtotal row.
     */
    private fun createTotalRow(context: Context, label: String, value: String, isBold: Boolean): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(4))
            }
            
            addView(TextView(context).apply {
                text = label
                setTextColor(if (isBold) Color.parseColor("#e5e7eb") else Color.parseColor("#9ca3af"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isBold) 16f else 14f)
                if (isBold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            
            addView(TextView(context).apply {
                text = value
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isBold) 20f else 14f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
    }

    /**
     * Creates the slide-to-action bar at the bottom.
     * Slide right = Accept (green)
     * Slide left = Reject (red)
     */
    private fun createSlideToActionBar(context: Context): View {
        val barHeight = dp(72)
        val pillSize = dp(60)
        val barPadding = dp(6)
        
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
            setBackgroundColor(Color.parseColor("#0d0d0d"))
            
            // Instruction text
            addView(TextView(context).apply {
                text = "← Slide to Reject  |  Slide to Accept →"
                setTextColor(Color.parseColor("#6b7280"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(12))
            })
            
            // Slide bar container
            val barContainer = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    barHeight
                )
            }
            
            // Background bar with gradient hints
            val backgroundBar = LinearLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply {
                    cornerRadius = (barHeight / 2).toFloat()
                    setColor(Color.parseColor("#262626"))
                }
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                
                // Left indicator (Reject)
                addView(TextView(context).apply {
                    text = "❌"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    alpha = 0.5f
                })
                
                // Spacer
                addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
                
                // Right indicator (Accept)
                addView(TextView(context).apply {
                    text = "✓"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setTextColor(Color.parseColor("#22c55e"))
                    alpha = 0.5f
                })
            }
            barContainer.addView(backgroundBar)
            
            // Draggable pill in center
            val pill = LinearLayout(context).apply {
                val pillParams = FrameLayout.LayoutParams(pillSize, pillSize)
                pillParams.gravity = Gravity.CENTER
                layoutParams = pillParams
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#3b82f6"))
                }
                elevation = dp(4).toFloat()
                
                addView(TextView(context).apply {
                    text = "⬌"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    setTextColor(Color.WHITE)
                })
            }
            barContainer.addView(pill)
            
            // Touch handling for the bar
            barContainer.post {
                slideBarWidth = barContainer.width.toFloat()
                val maxSlide = (slideBarWidth - pillSize - barPadding * 2) / 2
                
                barContainer.setOnTouchListener { _, event ->
                    if (isProcessing) return@setOnTouchListener true
                    
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val centerX = slideBarWidth / 2
                            val touchX = event.x
                            val newOffset = (touchX - centerX).coerceIn(-maxSlide, maxSlide)
                            
                            slideOffset = newOffset
                            pill.translationX = newOffset
                            
                            // Update pill color based on direction
                            val pillBg = pill.background as GradientDrawable
                            when {
                                newOffset > maxSlide * 0.3f -> {
                                    // Moving towards accept (green)
                                    val ratio = (newOffset / maxSlide).coerceIn(0f, 1f)
                                    pillBg.setColor(blendColors(Color.parseColor("#3b82f6"), Color.parseColor("#22c55e"), ratio))
                                }
                                newOffset < -maxSlide * 0.3f -> {
                                    // Moving towards reject (red)
                                    val ratio = (-newOffset / maxSlide).coerceIn(0f, 1f)
                                    pillBg.setColor(blendColors(Color.parseColor("#3b82f6"), Color.parseColor("#ef4444"), ratio))
                                }
                                else -> {
                                    pillBg.setColor(Color.parseColor("#3b82f6"))
                                }
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            val threshold = maxSlide * SLIDE_THRESHOLD_RATIO
                            
                            when {
                                slideOffset >= threshold -> {
                                    // Accept
                                    isProcessing = true
                                    pill.animate()
                                        .translationX(maxSlide)
                                        .setDuration(150)
                                        .withEndAction { onAcceptAction() }
                                        .start()
                                }
                                slideOffset <= -threshold -> {
                                    // Reject
                                    isProcessing = true
                                    pill.animate()
                                        .translationX(-maxSlide)
                                        .setDuration(150)
                                        .withEndAction { onRejectAction() }
                                        .start()
                                }
                                else -> {
                                    // Return to center
                                    pill.animate()
                                        .translationX(0f)
                                        .setDuration(200)
                                        .start()
                                    slideOffset = 0f
                                    (pill.background as GradientDrawable).setColor(Color.parseColor("#3b82f6"))
                                }
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
            
            addView(barContainer)
        }
    }

    /**
     * Blends two colors based on ratio.
     */
    private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
        val inverseRatio = 1f - ratio
        val r = (Color.red(color1) * inverseRatio + Color.red(color2) * ratio).toInt()
        val g = (Color.green(color1) * inverseRatio + Color.green(color2) * ratio).toInt()
        val b = (Color.blue(color1) * inverseRatio + Color.blue(color2) * ratio).toInt()
        return Color.rgb(r, g, b)
    }

    /**
     * Handles Accept action.
     */
    private fun onAcceptAction() {
        Log.d(TAG, "Accept action for order: $orderId")
        
        // Send accept action via broadcast
        val intent = Intent(this, OrderActionReceiver::class.java).apply {
            action = OrderActionReceiver.ACTION_ACCEPT
            putExtra(OrderActionReceiver.EXTRA_ORDER_ID, orderId)
            putExtra(OrderActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            actionToken?.let { putExtra(OrderActionReceiver.EXTRA_ACTION_TOKEN, it) }
        }
        sendBroadcast(intent)
        
        finish()
    }

    /**
     * Handles Reject action.
     */
    private fun onRejectAction() {
        Log.d(TAG, "Reject action for order: $orderId")
        
        // Send reject action via broadcast
        val intent = Intent(this, OrderActionReceiver::class.java).apply {
            action = OrderActionReceiver.ACTION_REJECT
            putExtra(OrderActionReceiver.EXTRA_ORDER_ID, orderId)
            putExtra(OrderActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            actionToken?.let { putExtra(OrderActionReceiver.EXTRA_ACTION_TOKEN, it) }
        }
        sendBroadcast(intent)
        
        finish()
    }

    /**
     * Converts dp to pixels.
     */
    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
