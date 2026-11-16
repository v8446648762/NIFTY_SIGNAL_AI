package com.niftysignal.ai

/**
 * Offline metrics for already-completed historical/paper trades.
 * This class does not generate signals or fetch data. The caller is responsible
 * for constructing trades without look-ahead bias and with realistic fills.
 */
data class BacktestTrade(
    val side: TradeBias,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Int,
    val entryTimeMillis: Long,
    val exitTimeMillis: Long,
    val feesAndSlippage: Double = 0.0
)

data class BacktestReport(
    val tradeCount: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val winRatePercent: Double,
    val grossProfit: Double,
    val grossLoss: Double,
    val netPnl: Double,
    val profitFactor: Double?,
    val maximumDrawdown: Double,
    val averagePnlPerTrade: Double,
    val warnings: List<String>
)

object HistoricalBacktestMetrics {
    fun calculate(trades: List<BacktestTrade>): BacktestReport {
        require(trades.all(::isValid)) { "Backtest contains invalid trade records." }
        val warnings = mutableListOf<String>()
        if (trades.isEmpty()) {
            return BacktestReport(0, 0, 0, 0.0, 0.0, 0.0, 0.0, null, 0.0, 0.0,
                listOf("No completed trades supplied; performance cannot be measured."))
        }
        val pnls = trades.sortedBy { it.exitTimeMillis }.map { trade ->
            val direction = if (trade.side == TradeBias.BUY_CE) 1.0 else -1.0
            (trade.exitPrice - trade.entryPrice) * direction * trade.quantity - trade.feesAndSlippage
        }
        val wins = pnls.count { it > 0.0 }
        val losses = pnls.count { it < 0.0 }
        val grossProfit = pnls.filter { it > 0.0 }.sum()
        val grossLoss = -pnls.filter { it < 0.0 }.sum()
        val net = pnls.sum()
        var equity = 0.0
        var peak = 0.0
        var maxDrawdown = 0.0
        pnls.forEach {
            equity += it
            if (equity > peak) peak = equity
            maxDrawdown = maxOf(maxDrawdown, peak - equity)
        }
        val profitFactor = when {
            grossLoss > 0.0 -> grossProfit / grossLoss
            grossProfit > 0.0 -> null // Undefined/infinite when there are no losing trades.
            else -> 0.0
        }
        if (grossLoss == 0.0 && grossProfit > 0.0) warnings += "Profit factor is undefined because there are no losing trades."
        warnings += "Metrics describe supplied completed trades only; they do not prove future performance."
        warnings += "Include realistic brokerage, taxes, slippage and option lot sizing in the supplied records."
        return BacktestReport(
            tradeCount = pnls.size,
            winningTrades = wins,
            losingTrades = losses,
            winRatePercent = wins * 100.0 / pnls.size,
            grossProfit = grossProfit,
            grossLoss = grossLoss,
            netPnl = net,
            profitFactor = profitFactor,
            maximumDrawdown = maxDrawdown,
            averagePnlPerTrade = net / pnls.size,
            warnings = warnings
        )
    }

    private fun isValid(t: BacktestTrade): Boolean =
        (t.side == TradeBias.BUY_CE || t.side == TradeBias.BUY_PE) &&
            t.entryPrice.isFinite() && t.entryPrice > 0.0 &&
            t.exitPrice.isFinite() && t.exitPrice > 0.0 &&
            t.quantity > 0 && t.entryTimeMillis > 0L &&
            t.exitTimeMillis > t.entryTimeMillis &&
            t.feesAndSlippage.isFinite() && t.feesAndSlippage >= 0.0
}
