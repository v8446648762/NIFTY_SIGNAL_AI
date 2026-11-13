package com.niftysignal.ai

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 18, 32)
        window.navigationBarColor = Color.rgb(12, 18, 32)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(Color.rgb(12, 18, 32))
        }

        fun label(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, dp(7), 0, dp(7))
            }

        root.addView(label("NIFTY_SIGNAL_AI", 25f, Color.WHITE, true))
        root.addView(label("DEMO MODE  •  LIVE DATA NOT CONNECTED", 12f, Color.YELLOW, true))
        root.addView(label("Market: NIFTY 50", 18f, Color.WHITE, true))
        root.addView(label("Sample price: 25,200.00", 16f, Color.LTGRAY))
        root.addView(label("Timeframe: 5 minutes  |  RSI: 51.6", 14f, Color.LTGRAY))
        root.addView(label("CURRENT SIGNAL", 13f, Color.LTGRAY, true))
        root.addView(label("WAIT", 32f, Color.YELLOW, true))
        root.addView(label("Demo signal only. Not a trading recommendation.", 13f, Color.LTGRAY))
        root.addView(label("Order approval", 18f, Color.WHITE, true))
        root.addView(label("No real order can be placed in this demo. Any future broker order must require your explicit approval.", 14f, Color.LTGRAY))

        val preview = Button(this).apply {
            text = "Preview demo order"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Demo order preview")
                    .setMessage("Example only: NIFTY option order\nNo live price or broker connection.\n\nThis will NOT place an actual order.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Confirm demo") { _, _ ->
                        Toast.makeText(this@MainActivity, "Demo confirmed — no real order placed.", Toast.LENGTH_LONG).show()
                    }
                    .show()
            }
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(18) })

        root.addView(label("Next steps: connect verified market data, then secure Angel One SmartAPI. Keep manual approval enabled.", 12f, Color.LTGRAY).apply {
            setPadding(0, dp(20), 0, 0)
        })
        setContentView(root)
    }
}
