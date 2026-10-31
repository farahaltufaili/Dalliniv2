package com.dallini.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var map: MapView
    private lateinit var speedView: TextView
    private var meMarker: Marker? = null
    private val signMarkers = mutableListOf<Marker>()
    private var lastQuery: Location? = null
    private var firstFix = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osm", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        val root = FrameLayout(this)
        map = MapView(this)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(17.0)
        map.controller.setCenter(GeoPoint(32.0, 44.33))
        root.addView(map, FrameLayout.LayoutParams(-1, -1))

        // مقياس السرعة
        speedView = TextView(this)
        speedView.text = "0\nكم/س"
        speedView.setTextColor(Color.WHITE)
        speedView.textSize = 26f
        speedView.typeface = Typeface.DEFAULT_BOLD
        speedView.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#CC1B2A41"))
        bg.setStroke(6, Color.parseColor("#2E86DE"))
        speedView.background = bg
        val size = (110 * resources.displayMetrics.density).toInt()
        val lp = FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.START)
        lp.setMargins(32, 0, 0, 64)
        root.addView(speedView, lp)
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) startLocation()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startLocation()
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if (last != null) onLocationChanged(last)
    }

    override fun onLocationChanged(location: Location) {
        val kmh = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt() else 0
        speedView.text = "$kmh\nكم/س"
        val p = GeoPoint(location.latitude, location.longitude)
        if (firstFix) { map.controller.setZoom(17.0); firstFix = false }
        map.controller.animateTo(p)
        if (meMarker == null) {
            val m = Marker(map)
            m.title = "موقعك"
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            meMarker = m
            map.overlays.add(m)
        }
        meMarker?.position = p
        map.invalidate()
        val lq = lastQuery
        if (lq == null || lq.distanceTo(location) > 500f) {
            lastQuery = location
            loadSigns(location.latitude, location.longitude)
        }
    }

    // الإشارات والعلامات من OpenStreetMap عبر Overpass
    private fun loadSigns(lat: Double, lon: Double) {
        val d = 0.01
        val bbox = "${lat - d},${lon - d},${lat + d},${lon + d}"
        val q = "[out:json][timeout:20];(" +
            "node[\"highway\"~\"speed_camera|stop|give_way|traffic_signals|crossing\"]($bbox);" +
            "node[\"traffic_sign\"]($bbox);" +
            "node[\"traffic_calming\"]($bbox);" +
            ");out body 300;"
        Thread {
            try {
                val url = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8")
                val conn = URL(url).openConnection()
                conn.setRequestProperty("User-Agent", "Dallini/0.2")
                conn.connectTimeout = 15000
                conn.readTimeout = 25000
                val json = JSONObject(conn.getInputStream().bufferedReader().readText())
                val els = json.getJSONArray("elements")
                runOnUiThread {
                    map.overlays.removeAll(signMarkers.toSet())
                    signMarkers.clear()
                    for (i in 0 until els.length()) {
                        val e = els.getJSONObject(i)
                        val tags = e.optJSONObject("tags") ?: continue
                        val m = Marker(map)
                        m.position = GeoPoint(e.getDouble("lat"), e.getDouble("lon"))
                        m.title = label(tags)
                        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        signMarkers.add(m)
                        map.overlays.add(m)
                    }
                    map.invalidate()
                }
            } catch (e: Exception) {
                lastQuery = null
            }
        }.start()
    }

    private fun label(t: JSONObject): String {
        return when {
            t.optString("highway") == "speed_camera" -> "📷 كاميرا سرعة"
            t.optString("highway") == "stop" -> "🛑 قف"
            t.optString("highway") == "give_way" -> "⚠️ أعطِ الأولوية"
            t.optString("highway") == "traffic_signals" -> "🚦 إشارة ضوئية"
            t.optString("highway") == "crossing" -> "🚶 عبور مشاة"
            t.has("traffic_calming") -> "🚧 مطب / تهدئة سرعة"
            t.has("maxspeed") -> "حد السرعة " + t.optString("maxspeed")
            else -> "علامة مرورية"
        }
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { map.onPause(); super.onPause() }
}
