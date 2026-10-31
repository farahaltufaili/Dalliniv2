package com.dallini.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Short welcome screen. Change SPLASH_DURATION_MS to adjust the delay. */
class SplashActivity : AppCompatActivity() {
    companion object {
        private const val SPLASH_DURATION_MS = 1800L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val openApp = Runnable {
        if (!isFinishing && !isDestroyed) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val navy = Color.parseColor("#14243D")
        window.statusBarColor = navy
        window.navigationBarColor = navy

        val root = FrameLayout(this)
        root.setBackgroundColor(navy)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "شعار دلّيني"
        }
        content.addView(logo, LinearLayout.LayoutParams(dp(104), dp(104)))

        val name = TextView(this).apply {
            text = "دلّيني"
            textSize = 34f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val nameParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) }
        content.addView(name, nameParams)

        val tagline = TextView(this).apply {
            text = "دليلك على الطريق"
            textSize = 16f
            setTextColor(Color.parseColor("#C2DFE7"))
            gravity = Gravity.CENTER
        }
        content.addView(tagline, LinearLayout.LayoutParams(-2, -2))

        val cp = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)
        root.addView(content, cp)

        val credit = TextView(this).apply {
            text = "تم تطوير التطبيق بواسطة كرار الطفيلي"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        val creditParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        creditParams.setMargins(dp(16), 0, dp(16), dp(40))
        root.addView(credit, creditParams)
        setContentView(root)
        handler.postDelayed(openApp, SPLASH_DURATION_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(openApp)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
