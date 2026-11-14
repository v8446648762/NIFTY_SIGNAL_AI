package com.niftysignal.ai

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private val bg=Color.rgb(12,18,32)
    private val panel=Color.rgb(23,32,50)
    private val muted=Color.LTGRAY
    private val up=Color.rgb(46,204,113)
    private val down=Color.rgb(245,90,90)
    private var tf="5 MIN"
    private var lots=1
    private lateinit var chart:DemoChart
    private lateinit var tfLabel:TextView
    private lateinit var qtyLabel:TextView
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg; window.navigationBarColor=bg
        val root=LinearLayout(this).apply { orientation=1; setPadding(dp(16),dp(18),dp(16),dp(24)); setBackgroundColor(bg) }
        fun label(s:String,size:Float=14f,color:Int=Color.WHITE,bold:Boolean=false)=TextView(this).apply {
            text=s; textSize=size; setTextColor(color); if(bold)setTypeface(null,android.graphics.Typeface.BOLD); setPadding(0,dp(5),0,dp(5))
        }
        fun heading(s:String){root.addView(label(s,16f,Color.WHITE,true).apply{setPadding(0,dp(16),0,dp(8))})}
        fun btn(s:String,action:()->Unit)=Button(this).apply{text=s;isAllCaps=false;setOnClickListener{action()}}
        root.addView(label("NIFTY_SIGNAL_AI",25f,Color.WHITE,true))
        root.addView(label("CHART DEMO  •  SAMPLE DATA ONLY",12f,Color.YELLOW,true))
        root.addView(label("NIFTY 50  |  Demo price: 25,200.00",17f,Color.WHITE,true))
        root.addView(label("Not connected to live market data",12f,muted))
        heading("PRICE CHART")
        tfLabel=label("Selected timeframe: 5 MIN",13f,muted,true);root.addView(tfLabel)
        val row=LinearLayout(this).apply{orientation=0}
        row.addView(btn("5 MIN"){setTf("5 MIN")},LinearLayout.LayoutParams(0,dp(48),1f))
        row.addView(btn("1 MIN"){setTf("1 MIN")},LinearLayout.LayoutParams(0,dp(48),1f))
        root.addView(row)
        chart=DemoChart();root.addView(chart,LinearLayout.LayoutParams(-1,dp(245)))
        root.addView(label("Green: bullish candle   Red: bearish candle",11f,muted))
        heading("INDICATORS  •  DEMO VALUES")
        root.addView(label("VWAP     25,190.00"))
        root.addView(label("EMA 9    25,205.00",14f,up))
        root.addView(label("EMA 21  25,198.00",14f,Color.rgb(255,190,75)))
        root.addView(label("RSI         51.6"))
        root.addView(label("Illustrative values only; not calculated from live candles.",11f,muted))
        heading("ADVANCED MARKET LEVELS")
        val levels=LinearLayout(this).apply{orientation=1;setPadding(dp(12),dp(8),dp(12),dp(8));setBackgroundColor(panel)}
        levels.addView(label("Support: Waiting for live candles",13f,muted))
        levels.addView(label("Resistance: Waiting for live candles",13f,muted))
        levels.addView(label("CPR / Previous Day High-Low / Opening Range: Pending live data",12f,muted))
        root.addView(levels)
        heading("ADVANCED INDICATORS")
        val advanced=LinearLayout(this).apply{orientation=1;setPadding(dp(12),dp(8),dp(12),dp(8));setBackgroundColor(panel)}
        advanced.addView(label("Supertrend     LOCKED — needs live OHLC candles",13f,muted))
        advanced.addView(label("MACD              LOCKED — needs live OHLC candles",13f,muted))
        advanced.addView(label("ADX / Bollinger Bands  LOCKED — needs live OHLC candles",13f,muted))
        advanced.addView(label("Volume confirmation  LOCKED — needs live volume feed",13f,muted))
        root.addView(advanced)
        heading("SMART ALERTS")
        val alerts=LinearLayout(this).apply{orientation=1;setPadding(dp(12),dp(8),dp(12),dp(8));setBackgroundColor(panel)}
        alerts.addView(label("CE / PE setup alerts: Not active",13f,muted))
        alerts.addView(label("VWAP breakout / EMA crossover: Not active",13f,muted))
        alerts.addView(label("Alerts activate only after live data is connected.",11f,Color.YELLOW))
        root.addView(alerts)
        heading("SIGNAL SUGGESTION")
        val signal=LinearLayout(this).apply{orientation=1;setPadding(dp(14),dp(10),dp(14),dp(10));setBackgroundColor(panel)}
        signal.addView(label("WAIT",28f,Color.YELLOW,true));signal.addView(label("Demo only — no live signal.",13f,muted));root.addView(signal)
        heading("TRADE PLANNER  •  DEMO ONLY")
        root.addView(label("Entry: —     Stop Loss: —     Target: —"))
        root.addView(label("Manual quantity (lots)",13f,muted,true))
        val qrow=LinearLayout(this).apply{orientation=0}
        qrow.addView(btn("−"){lots=max(1,lots-1);updateQty()},LinearLayout.LayoutParams(dp(72),dp(48)))
        qtyLabel=label("  1 lot(s)  ",16f,Color.WHITE,true);qrow.addView(qtyLabel,LinearLayout.LayoutParams(0,dp(48),1f))
        qrow.addView(btn("+"){lots=min(20,lots+1);updateQty()},LinearLayout.LayoutParams(dp(72),dp(48)));root.addView(qrow)
        root.addView(btn("Preview demo order"){
            AlertDialog.Builder(this).setTitle("Demo order preview")
                .setMessage("Example only: NIFTY option\nQuantity: $lots lot(s)\nNo live price or broker connection.\n\nThis will NOT place an actual order.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Confirm demo"){_,_->Toast.makeText(this,"Demo confirmed — no real order placed.",Toast.LENGTH_LONG).show()}.show()
        },LinearLayout.LayoutParams(-1,dp(52)).apply{topMargin=dp(12)})
        root.addView(label("IMPORTANT: Sample data only, not live market data. Do not trade using demo values. Any future real entry or exit order must require your explicit approval.",12f,Color.YELLOW).apply{setPadding(0,dp(20),0,0)})
        setContentView(ScrollView(this).apply{setBackgroundColor(bg);addView(root)})
    }
    private fun setTf(v:String){tf=v;tfLabel.text="Selected timeframe: $tf  •  DEMO";chart.invalidate()}
    private fun updateQty(){qtyLabel.text="  $lots lot(s)  "}

    private inner class DemoChart:View(this@MainActivity){
        private val grid=Paint(3).apply{color=Color.rgb(48,61,82);strokeWidth=dp(1).toFloat()}
        private val txt=Paint(3).apply{color=muted;textSize=dp(10).toFloat()}
        private val wick=Paint(3).apply{strokeWidth=dp(2).toFloat()}
        private val body=Paint(3)
        private val line=Paint(3).apply{style=Paint.Style.STROKE;strokeWidth=dp(2).toFloat()}
        override fun onDraw(c:Canvas){
            super.onDraw(c);c.drawColor(panel)
            val l=dp(36).toFloat();val r=width-dp(10).toFloat();val t=dp(20).toFloat();val b=height-dp(28).toFloat()
            for(i in 0..4){val y=t+(b-t)*i/4f;c.drawLine(l,y,r,y,grid);c.drawText("${25240-i*20}",dp(2).toFloat(),y-dp(3),txt)}
            val a=if(tf=="5 MIN") listOf(25190,25202,25196,25210,25204,25218,25208,25222,25215,25228,25216,25205,25214,25202,25218,25224,25212,25230,25220,25235,25225,25218,25229,25200) else listOf(25205,25210,25202,25214,25208,25218,25212,25222,25216,25226,25218,25210,25216,25208,25220,25225,25214,25228,25222,25232,25224,25218,25226,25200)
            val step=(r-l)/a.size
            fun yy(v:Float)=b-((v-25180f)/65f)*(b-t)
            val e9=Path();val e21=Path()
            a.forEachIndexed{i,close->
                val o=if(i==0)close-4 else a[i-1];val hi=max(o,close)+(3+i%4);val lo=min(o,close)-(2+i%3)
                val x=l+step*(i+.5f);val col=if(close>=o)up else down;wick.color=col;body.color=col
                c.drawLine(x,yy(hi.toFloat()),x,yy(lo.toFloat()),wick)
                val half=max(dp(2).toFloat(),step*.27f)
                c.drawRect(x-half,min(yy(o.toFloat()),yy(close.toFloat())),x+half,max(yy(o.toFloat()),yy(close.toFloat()))+dp(1),body)
                val v9=close-3f+i%3;val v21=close-7f+i%2
                if(i==0){e9.moveTo(x,yy(v9));e21.moveTo(x,yy(v21))}else{e9.lineTo(x,yy(v9));e21.lineTo(x,yy(v21))}
            }
            line.color=Color.rgb(82,190,255);c.drawPath(e9,line);line.color=Color.rgb(255,190,75);c.drawPath(e21,line)
            c.drawText("EMA 9",l,height-dp(8).toFloat(),txt);c.drawText("EMA 21",l+dp(55),height-dp(8).toFloat(),txt);c.drawText("SAMPLE CANDLES",r-dp(105),dp(14).toFloat(),txt)
        }
    }
}