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
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var map: MapView
    private lateinit var speedView: TextView
    private lateinit var searchBox: EditText
    private lateinit var fab: TextView
    private lateinit var card: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView

    private var meMarker: Marker? = null
    private var selMarker: Marker? = null
    private val signMarkers = mutableListOf<Marker>()
    private var lastQuery: Location? = null
    private var lastLoc: Location? = null
    private var firstFix = true
    private var follow = true
    private var started = false
    private var reqId = 0

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun ovalBg(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osm", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        val root = FrameLayout(this)

        // ---------- الخريطة ----------
        map = MapView(this)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(17.0)
        map.controller.setCenter(GeoPoint(32.0, 44.33))
        map.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_MOVE) follow = false
            false
        }
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                selectPoint(p, null)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }
        map.overlays.add(0, MapEventsOverlay(receiver))
        root.addView(map, FrameLayout.LayoutParams(-1, -1))

        // ---------- طبقة الواجهة ----------
        val ui = FrameLayout(this)
        root.addView(ui, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(ui) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom)
            insets
        }

        // شريط البحث
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = roundBg(Color.WHITE, 28)
        bar.elevation = dp(8).toFloat()
        bar.setPadding(dp(16), dp(4), dp(8), dp(4))
        searchBox = EditText(this)
        searchBox.hint = "ابحث عن مكان…"
        searchBox.setSingleLine(true)
        searchBox.background = null
        searchBox.textSize = 16f
        searchBox.inputType = InputType.TYPE_CLASS_TEXT
        searchBox.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchBox.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
        val searchBtn = TextView(this)
        searchBtn.text = "🔍"
        searchBtn.textSize = 22f
        searchBtn.setPadding(dp(12), dp(8), dp(12), dp(8))
        searchBtn.setOnClickListener { doSearch() }
        bar.addView(searchBox, LinearLayout.LayoutParams(0, dp(48), 1f))
        bar.addView(searchBtn)
        val blp = FrameLayout.LayoutParams(-1, -2, Gravity.TOP)
        blp.setMargins(dp(12), dp(12), dp(12), 0)
        ui.addView(bar, blp)

        // مقياس السرعة
        speedView = TextView(this)
        speedView.text = "0\nكم/س"
        speedView.setTextColor(Color.WHITE)
        speedView.textSize = 26f
        speedView.typeface = Typeface.DEFAULT_BOLD
        speedView.gravity = Gravity.CENTER
        val sbg = GradientDrawable()
        sbg.shape = GradientDrawable.OVAL
        sbg.setColor(Color.parseColor("#CC1B2A41"))
        sbg.setStroke(6, Color.parseColor("#2E86DE"))
        speedView.background = sbg
        val size = dp(110)
        val slp = FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.START)
        slp.setMargins(dp(16), 0, 0, dp(32))
        ui.addView(speedView, slp)

        // زر موقعي الحالي
        fab = TextView(this)
        fab.text = "📍"
        fab.textSize = 24f
        fab.gravity = Gravity.CENTER
        fab.background = ovalBg(Color.WHITE)
        fab.elevation = dp(8).toFloat()
        fab.setOnClickListener { goToMyLocation() }
        val flp = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END)
        flp.setMargins(0, 0, dp(16), dp(32))
        ui.addView(fab, flp)

        // بطاقة تفاصيل المكان
        card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = roundBg(Color.WHITE, 20)
        card.elevation = dp(12).toFloat()
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        card.visibility = View.GONE
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        titleView = TextView(this)
        titleView.textSize = 18f
        titleView.typeface = Typeface.DEFAULT_BOLD
        titleView.setTextColor(Color.parseColor("#111111"))
        val close = TextView(this)
        close.text = "✕"
        close.textSize = 20f
        close.setTextColor(Color.parseColor("#555555"))
        close.setPadding(dp(12), dp(4), dp(4), dp(4))
        close.setOnClickListener { hideCard() }
        head.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(close)
        bodyView = TextView(this)
        bodyView.textSize = 14f
        bodyView.setTextColor(Color.parseColor("#333333"))
        bodyView.setLineSpacing(0f, 1.2f)
        val bodyLp = LinearLayout.LayoutParams(-1, -2)
        bodyLp.topMargin = dp(8)
        card.addView(head, LinearLayout.LayoutParams(-1, -2))
        card.addView(bodyView, bodyLp)
        val clp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        clp.setMargins(dp(12), 0, dp(12), dp(12))
        ui.addView(card, clp)

        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (card.visibility == View.VISIBLE) {
                    hideCard()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (hasLocationPermission()) startLocation() else askPermission()
    }

    // ---------- الصلاحيات والموقع ----------
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun askPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            1
        )
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.any { it == PackageManager.PERMISSION_GRANTED }) startLocation()
        else toast("يحتاج التطبيق إلى إذن الموقع حتى يعمل")
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (started) return
        started = true
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this) } catch (e: Exception) { }
        try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, this) } catch (e: Exception) { }
        var best: Location? = null
        for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                val l = lm.getLastKnownLocation(prov)
                if (l != null && (best == null || l.time > best.time)) best = l
            } catch (e: Exception) { }
        }
        if (best != null) onLocationChanged(best)
    }

    override fun onLocationChanged(location: Location) {
        lastLoc = location
        if (location.provider == LocationManager.GPS_PROVIDER) {
            val kmh = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt() else 0
            speedView.text = "$kmh\nكم/س"
        }
        val p = GeoPoint(location.latitude, location.longitude)
        if (firstFix) {
            map.controller.setZoom(17.0)
            map.controller.setCenter(p)
            firstFix = false
        } else if (follow) {
            map.controller.animateTo(p)
        }
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

    private fun goToMyLocation() {
        if (!hasLocationPermission()) { askPermission(); return }
        startLocation()
        val l = lastLoc
        if (l == null) {
            toast("جارٍ تحديد موقعك… تأكد من تشغيل الـ GPS")
            return
        }
        follow = true
        map.controller.animateTo(GeoPoint(l.latitude, l.longitude), 17.0, 600L)
    }

    // ---------- البحث عن مكان ----------
    private fun doSearch() {
        val q = searchBox.text.toString().trim()
        if (q.isEmpty()) return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
        val c = map.mapCenter
        val lat = c.latitude
        val lon = c.longitude
        Thread {
            try {
                val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&addressdetails=1&accept-language=ar" +
                    "&viewbox=${lon - 1},${lat + 1},${lon + 1},${lat - 1}&q=" + URLEncoder.encode(q, "UTF-8")
                val arr = JSONArray(httpGet(url))
                runOnUiThread { onSearchResults(arr) }
            } catch (e: Exception) {
                toast("تعذر البحث، تأكد من الاتصال بالإنترنت")
            }
        }.start()
    }

    private fun onSearchResults(arr: JSONArray) {
        if (arr.length() == 0) { toast("لم يتم العثور على المكان"); return }
        if (arr.length() == 1) { pickResult(arr.getJSONObject(0)); return }
        val names = Array(arr.length()) { i -> shortName(arr.getJSONObject(i)) }
        AlertDialog.Builder(this)
            .setTitle("اختر المكان")
            .setItems(names) { _, i -> pickResult(arr.getJSONObject(i)) }
            .show()
    }

    private fun shortName(o: JSONObject): String {
        val dn = o.optString("display_name")
        val nm = o.optString("name")
        return if (nm.isBlank()) dn else nm + "\n" + dn.substringAfter(",").trim()
    }

    private fun pickResult(o: JSONObject) {
        val p = GeoPoint(o.getDouble("lat"), o.getDouble("lon"))
        follow = false
        map.controller.animateTo(p, 17.0, 800L)
        selectPoint(p, o)
    }

    // ---------- تحديد نقطة وعرض تفاصيلها ----------
    private fun selectPoint(p: GeoPoint, known: JSONObject?) {
        placeMarker(p, "")
        val id = ++reqId
        if (known != null) {
            showDetails(p, known)
            return
        }
        titleView.text = "جارٍ جلب التفاصيل…"
        bodyView.text = coordText(p)
        showCard()
        Thread {
            try {
                val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1&accept-language=ar" +
                    "&lat=${p.latitude}&lon=${p.longitude}"
                val o = JSONObject(httpGet(url))
                runOnUiThread { if (id == reqId) showDetails(p, o) }
            } catch (e: Exception) {
                runOnUiThread { if (id == reqId) showDetails(p, JSONObject()) }
                toast("تعذر جلب التفاصيل، تأكد من الاتصال بالإنترنت")
            }
        }.start()
    }

    private fun pick(addr: JSONObject?, vararg keys: String): String {
        if (addr == null) return ""
        for (k in keys) {
            val v = addr.optString(k)
            if (v.isNotBlank()) return v
        }
        return ""
    }

    private fun showDetails(p: GeoPoint, o: JSONObject) {
        val addr = o.optJSONObject("address")
        val display = o.optString("display_name")
        var title = o.optString("name")
        if (title.isBlank()) {
            title = pick(addr, "road", "pedestrian", "suburb", "neighbourhood", "city_district", "city", "town", "village")
        }
        if (title.isBlank() && display.isNotBlank()) title = display.substringBefore(",")
        if (title.isBlank()) title = "موقع غير مسمّى"

        val sb = StringBuilder()
        if (display.isNotBlank()) sb.append("📌 ").append(display).append("\n")
        val kind = arabicType(o.optString("category"), o.optString("type"))
        if (kind.isNotBlank()) sb.append("🏷️ النوع: ").append(kind).append("\n")
        val area = pick(addr, "suburb", "neighbourhood", "city_district", "city", "town", "village")
        val state = pick(addr, "state", "county")
        if (area.isNotBlank() || state.isNotBlank()) {
            sb.append("🏙️ المنطقة: ").append(listOf(area, state).filter { it.isNotBlank() }.joinToString("، ")).append("\n")
        }
        val me = lastLoc
        if (me != null) {
            val res = FloatArray(1)
            Location.distanceBetween(me.latitude, me.longitude, p.latitude, p.longitude, res)
            val m = res[0]
            val dist = if (m < 1000f) "${m.roundToInt()} م" else String.format(Locale.US, "%.1f كم", m / 1000f)
            sb.append("🧭 تبعد عنك: ").append(dist).append("\n")
        }
        sb.append("📍 ").append(coordText(p))

        titleView.text = title
        bodyView.text = sb.toString()
        placeMarker(p, title)
        showCard()
    }

    private fun coordText(p: GeoPoint): String =
        String.format(Locale.US, "%.5f, %.5f", p.latitude, p.longitude)

    private fun placeMarker(p: GeoPoint, title: String) {
        selMarker?.let { map.overlays.remove(it) }
        val m = Marker(map)
        m.position = p
        m.title = title
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        m.setInfoWindow(null)
        m.setOnMarkerClickListener { _, _ -> true }
        selMarker = m
        map.overlays.add(m)
        map.invalidate()
    }

    private fun showCard() {
        card.visibility = View.VISIBLE
        card.post {
            val shift = -(card.height + dp(12)).toFloat()
            speedView.translationY = shift
            fab.translationY = shift
        }
    }

    private fun hideCard() {
        card.visibility = View.GONE
        speedView.translationY = 0f
        fab.translationY = 0f
        selMarker?.let { map.overlays.remove(it) }
        selMarker = null
        reqId++
        map.invalidate()
    }

    private fun arabicType(category: String, type: String): String {
        val t = when (type) {
            "restaurant" -> "مطعم"
            "cafe" -> "مقهى"
            "fast_food" -> "وجبات سريعة"
            "hospital" -> "مستشفى"
            "clinic" -> "عيادة"
            "pharmacy" -> "صيدلية"
            "school" -> "مدرسة"
            "university" -> "جامعة"
            "college" -> "كلية"
            "kindergarten" -> "روضة"
            "place_of_worship" -> "مكان عبادة"
            "fuel" -> "محطة وقود"
            "bank" -> "مصرف"
            "atm" -> "صراف آلي"
            "marketplace" -> "سوق"
            "parking" -> "موقف سيارات"
            "police" -> "مركز شرطة"
            "hotel" -> "فندق"
            "supermarket" -> "سوبرماركت"
            "mall" -> "مجمع تجاري"
            "bus_stop" -> "موقف حافلات"
            "residential" -> "شارع سكني"
            "primary", "secondary", "tertiary", "trunk", "motorway", "unclassified" -> "طريق"
            "city" -> "مدينة"
            "town" -> "بلدة"
            "village" -> "قرية"
            "suburb", "neighbourhood", "quarter" -> "حي"
            "house", "apartments", "building" -> "مبنى"
            "yes" -> "مبنى"
            "" -> ""
            else -> type.replace("_", " ")
        }
        return if (t.isBlank() && category.isNotBlank()) category.replace("_", " ") else t
    }

    // ---------- الإشارات والعلامات من OpenStreetMap ----------
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
                val json = JSONObject(httpGet(url))
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

    // ---------- أدوات ----------
    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Dallini/0.3 (Android)")
        c.setRequestProperty("Accept-Language", "ar")
        c.connectTimeout = 15000
        c.readTimeout = 25000
        try {
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun toast(s: String) {
        runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { map.onPause(); super.onPause() }
    override fun onDestroy() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(this)
        } catch (e: Exception) { }
        map.onDetach()
        super.onDestroy()
    }
}
