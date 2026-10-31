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

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#1B2A41"))

        val center = LinearLayout(this)
        center.orientation = LinearLayout.VERTICAL
        center.gravity = Gravity.CENTER

        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ic_logo)
        center.addView(logo, LinearLayout.LayoutParams((140 * d).toInt(), (140 * d).toInt()))

        val title = TextView(this)
        title.text = "دليني"
        title.setTextColor(Color.WHITE)
        title.textSize = 40f
        title.typeface = Typeface.DEFAULT_BOLD
        title.gravity = Gravity.CENTER
        val tlp = LinearLayout.LayoutParams(-2, -2)
        tlp.topMargin = (12 * d).toInt()
        center.addView(title, tlp)

        val sub = TextView(this)
        sub.text = "رفيقك على الطريق"
        sub.setTextColor(Color.parseColor("#9CC9F5"))
        sub.textSize = 16f
        sub.gravity = Gravity.CENTER
        val slp = LinearLayout.LayoutParams(-2, -2)
        slp.topMargin = (4 * d).toInt()
        center.addView(sub, slp)

        root.addView(center, FrameLayout.LayoutParams(-1, -1))

        val credit = TextView(this)
        credit.text = "تم تطوير التطبيق بواسطة\nكرار الطفيلي"
        credit.setTextColor(Color.parseColor("#9FB3C8"))
        credit.textSize = 13f
        credit.gravity = Gravity.CENTER
        val clp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        clp.bottomMargin = (36 * d).toInt()
        root.addView(credit, clp)

        setContentView(root)

        center.alpha = 0f
        credit.alpha = 0f
        center.animate().alpha(1f).setDuration(600).start()
        credit.animate().alpha(1f).setStartDelay(300).setDuration(600).start()

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 2800)
    }
}
