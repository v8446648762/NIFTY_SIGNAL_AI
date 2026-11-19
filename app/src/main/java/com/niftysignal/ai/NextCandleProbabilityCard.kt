package com.niftysignal.ai

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Standalone, read-only card. It consumes an already-provided snapshot and never
 * connects to, refreshes, or mutates the market-data feed.
 * MainActivity wiring is intentionally deferred until a separate compatibility review.
 */
object NextCandleProbabilityCard {
    fun create(
        context: Context,
        snapshot: MarketDataSnapshot?,
        status: FeedConnectionState,
        nowMillis: Long
    ): LinearLayout {
        val fresh = status == FeedConnectionState.LIVE &&
            snapshot != null &&
            snapshot.receivedAtMillis > 0L &&
            nowMillis >= snapshot.receivedAtMillis &&
            nowMillis - snapshot.receivedAtMillis <= 30_000L

        val estimate = snapshot?.let {
            NextFiveMinuteCandleEngine.evaluate(
                it.fiveMinuteCandles, it.oneMinuteCandles, fresh, nowMillis
            )
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            background = GradientDrawable().apply {
                setColor(0xFF101820.toInt())
                cornerRadius = 18f
                setStroke(1, 0xFF25404A.toInt())
            }
            addView(TextView(context).apply {
                text = "NEXT 5-MIN CANDLE AI"
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
            })
            val signal = when (estimate?.direction) {
                NextCandleDirection.GREEN -> "Next Candle is 🟢"
                NextCandleDirection.RED -> "Next Candle is 🔴"
                NextCandleDirection.NO_EDGE -> "WAIT — No clear edge"
                else -> "WAIT — Live data unavailable"
            }
            addView(TextView(context).apply {
                text = signal
                setTextColor(Color.WHITE)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 12, 0, 8)
            })
            val probabilities = if (estimate?.greenProbability != null && estimate.redProbability != null) {
                "Green: ${estimate.greenProbability}%   |   Red: ${estimate.redProbability}%"
            } else "Probability hidden until fresh, sufficient candles arrive."
            addView(TextView(context).apply {
                text = probabilities
                setTextColor(0xFFB8C7D9.toInt())
                textSize = 13f
            })
            estimate?.reasons?.take(3)?.forEach { reason ->
                addView(TextView(context).apply {
                    text = "• $reason"
                    setTextColor(0xFFB8C7D9.toInt())
                    textSize = 12f
                })
            }
        }
    }
}
