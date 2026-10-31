package com.dallini.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration as AndroidConfiguration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
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
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.*

/** Dallini v3: experimental foreground navigation. Requires network for routing/search. */
class MainActivity : AppCompatActivity(), LocationListener, TextToSpeech.OnInitListener {
    private val dark by lazy {
        when (prefs.getString("theme", "auto")) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and AndroidConfiguration.UI_MODE_NIGHT_MASK) == AndroidConfiguration.UI_MODE_NIGHT_YES
        }
    }
    private val prefs by lazy { getSharedPreferences("dallini_v3", MODE_PRIVATE) }
    private val teal = Color.rgb(6, 132, 128)
    private val navy = Color.rgb(20, 36, 61)
    private val white get() = if (dark) Color.rgb(32, 45, 57) else Color.WHITE
    private val ink get() = if (dark) Color.WHITE else navy
    private val muted get() = if (dark) Color.rgb(185, 201, 209) else Color.rgb(90, 104, 117)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var map: MapView
    private lateinit var searchBox: EditText
    private lateinit var speedView: TextView
    private lateinit var limitView: TextView
    private lateinit var card: LinearLayout
    private lateinit var cardTitle: TextView
    private lateinit var cardBody: TextView
    private lateinit var routeButton: TextView
    private lateinit var starButton: TextView
    private lateinit var navBanner: TextView
    private lateinit var locationButton: TextView
    private lateinit var themeButton: TextView
    private var meMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private val signMarkers = mutableListOf<Marker>()
    private var routeOverlay: Polyline? = null
    private var lastLocation: Location? = null
    private var lastSignsAt: Location? = null
    private var route: Route? = null
    private var selected: Place? = null
    private var navigating = false
    private var following = true
    private var firstFix = true
    private var requestingLocation = false
    private var searchRequest = 0
    private var routeRequest = 0
    private var signRequest = 0
    private var currentLimit: Int? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var spokenStep = -1
    private var lastReroute = 0L
    private var lastSpeedWarning = 0L
    private var nextVoiceStep = 0

    private data class Place(val name: String, val lat: Double, val lon: Double, val address: String = "") {
        fun point() = GeoPoint(lat, lon)
        fun toJson() = JSONObject().put("name", name).put("lat", lat).put("lon", lon).put("address", address)
        companion object {
            fun fromJson(o: JSONObject) = Place(o.optString("name", "مكان"), o.getDouble("lat"), o.getDouble("lon"), o.optString("address"))
        }
    }
    private data class Turn(val point: GeoPoint, val instruction: String, val distance: Double)
    private data class Route(val points: List<GeoPoint>, val turns: List<Turn>, val meters: Double, val seconds: Double)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    private fun bg(c: Int = white, r: Int = 22) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun text(s: String, size: Float = 15f, bold: Boolean = false, color: Int = ink): TextView = TextView(this).apply {
        this.text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun tappable(s: String, action: () -> Unit): TextView = text(s, 15f, true, Color.WHITE).apply {
        gravity = Gravity.CENTER; background = bg(teal, 16); setPadding(dp(16), dp(11), dp(16), dp(11))
        setOnClickListener { action() }
    }
    private fun iconButton(s: String, action: () -> Unit): TextView = text(s, 22f, true).apply {
        gravity = Gravity.CENTER; background = bg(white, 18); elevation = dp(4).toFloat()
        contentDescription = s; setOnClickListener { action() }
    }
    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchBox.windowToken, 0)
        searchBox.clearFocus()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osm", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        tts = TextToSpeech(this, this)
        window.statusBarColor = if (dark) Color.rgb(18, 27, 37) else navy
        window.navigationBarColor = if (dark) Color.rgb(18, 27, 37) else Color.WHITE
        val root = FrameLayout(this)
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(32.0, 44.33))
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_MOVE) following = false
                false
            }
        }
        applyMapTheme()
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { selectPlace(Place("الموقع المحدد", p.latitude, p.longitude)); return true }
            override fun longPressHelper(p: GeoPoint): Boolean { selectPlace(Place("الموقع المحدد", p.latitude, p.longitude)); return true }
        }))
        root.addView(map, FrameLayout.LayoutParams(-1, -1))
        val ui = FrameLayout(this)
        root.addView(ui, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(ui) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom); insets
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = bg(); elevation = dp(6).toFloat()
            setPadding(dp(14), dp(3), dp(6), dp(3))
        }
        searchBox = EditText(this).apply {
            hint = "وين تريد تروح؟"; setHintTextColor(muted); setTextColor(ink)
            textSize = 16f; setSingleLine(true); background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) { searchOnline(); true } else false
            }
            setOnFocusChangeListener { _, focused -> if (focused && text.isEmpty()) showSavedPlaces() }
        }
        searchBar.addView(searchBox, LinearLayout.LayoutParams(0, dp(51), 1f))
        searchBar.addView(text("⌕", 28f, true, teal).apply {
            gravity = Gravity.CENTER; setPadding(dp(9), 0, dp(9), 0); setOnClickListener { searchOnline() }
        })
        top.addView(searchBar)
        navBanner = text("", 17f, true, Color.WHITE).apply {
            background = bg(teal, 18); setPadding(dp(18), dp(14), dp(18), dp(14))
            visibility = View.GONE; setOnClickListener { announceCurrentTurn() }
        }
        top.addView(navBanner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        ui.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(12), dp(12), dp(12), 0) })

        val leftBottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        limitView = text("الحد: غير متوفر", 12f, true).apply {
            gravity = Gravity.CENTER; background = bg(); setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        leftBottom.addView(limitView, LinearLayout.LayoutParams(-2, -2))
        speedView = text("0\nكم/س", 24f, true, Color.WHITE).apply {
            gravity = Gravity.CENTER; background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(navy); setStroke(dp(3), teal)
            }
            contentDescription = "السرعة الحالية"
        }
        leftBottom.addView(speedView, LinearLayout.LayoutParams(dp(84), dp(84)).apply { topMargin = dp(7) })
        ui.addView(leftBottom, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), 0, 0, dp(158)) })

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        locationButton = iconButton("◎") { goToMyLocation() }
        right.addView(locationButton, LinearLayout.LayoutParams(dp(50), dp(50)))
        themeButton = iconButton(if (dark) "☀" else "☾") { changeTheme() }
        right.addView(themeButton, LinearLayout.LayoutParams(dp(50), dp(50)).apply { topMargin = dp(10) })
        right.addView(iconButton("★") { showSavedPlaces() }, LinearLayout.LayoutParams(dp(50), dp(50)).apply { topMargin = dp(10) })
        ui.addView(right, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(12), dp(158)) })

        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = bg(); elevation = dp(10).toFloat()
            setPadding(dp(16), dp(13), dp(16), dp(14)); visibility = View.GONE
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cardTitle = text("الوجهة", 19f, true)
        row.addView(cardTitle, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text("✕", 22f, true, muted).apply {
            setPadding(dp(10), 0, dp(3), 0); setOnClickListener { closeCard() }
        })
        card.addView(row)
        cardBody = text("", 14f, false, muted).apply { setPadding(0, dp(8), 0, dp(10)) }
        card.addView(cardBody)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        starButton = tappable("☆ حفظ") { saveSelected() }
        buttons.addView(starButton, LinearLayout.LayoutParams(0, -2, 1f))
        routeButton = tappable("عرض المسار") { if (navigating) stopNavigation() else if (route == null) calculateRoute(false) else beginNavigation() }
        buttons.addView(routeButton, LinearLayout.LayoutParams(0, -2, 2f).apply { marginStart = dp(9) })
        card.addView(buttons)
        ui.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(16)) })
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (card.visibility == View.VISIBLE) closeCard() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        if (hasLocation()) requestLocation() else ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
    }

    private fun applyMapTheme() {
        // Contrast filter is a local visual transform of OSM's standard raster tiles, not a real dark tile style.
        val filter = if (dark) ColorMatrixColorFilter(floatArrayOf(
            -0.65f, 0f, 0f, 0f, 170f,
            0f, -0.65f, 0f, 0f, 177f,
            0f, 0f, -0.65f, 0f, 185f,
            0f, 0f, 0f, 1f, 0f
        )) else null
        map.overlayManager.tilesOverlay.setColorFilter(filter)
        map.invalidate()
    }
    private fun changeTheme() {
        val options = arrayOf("تلقائي حسب إعداد الهاتف", "نهاري", "ليلي")
        val codes = arrayOf("auto", "light", "dark")
        AlertDialog.Builder(this).setTitle("مظهر دلّيني").setSingleChoiceItems(options,
            codes.indexOf(prefs.getString("theme", "auto")).coerceAtLeast(0)) { dialog, which ->
            prefs.edit().putString("theme", codes[which]).apply()
            dialog.dismiss(); recreate()
        }.setNegativeButton("إلغاء", null).show()
    }
    private fun hasLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 1 && results.any { it == PackageManager.PERMISSION_GRANTED }) requestLocation()
        else toast("فعّلي صلاحية الموقع حتى يشتغل GPS")
    }
    @SuppressLint("MissingPermission")
    private fun requestLocation() {
        if (requestingLocation || !hasLocation()) return
        requestingLocation = true
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        try { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this) } catch (_: Exception) {}
        try { manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, this) } catch (_: Exception) {}
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try { manager.getLastKnownLocation(provider)?.let { onLocationChanged(it) } } catch (_: Exception) {}
        }
    }
    override fun onLocationChanged(location: Location) {
        val previous = lastLocation
        if (previous != null && location.time < previous.time - 5000L) return
        lastLocation = location
        val kmh = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt() else 0
        speedView.text = "$kmh\nكم/س"
        val limit = currentLimit
        speedView.setTextColor(if (limit != null && kmh > limit + 5) Color.rgb(255, 135, 104) else Color.WHITE)
        if (limit != null && kmh > limit + 5 && System.currentTimeMillis() - lastSpeedWarning > 30000) {
            lastSpeedWarning = System.currentTimeMillis(); speak("انتبه، تجاوزت حد السرعة")
        }
        val point = GeoPoint(location.latitude, location.longitude)
        if (firstFix) { firstFix = false; map.controller.setZoom(17.0); map.controller.setCenter(point) }
        else if (following) map.controller.animateTo(point)
        if (meMarker == null) {
            meMarker = Marker(map).apply { title = "موقعك"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER) }
            meMarker?.let { map.overlays.add(it) }
        }
        meMarker?.position = point
        map.invalidate()
        if (lastSignsAt == null || lastSignsAt!!.distanceTo(location) > 600f) {
            lastSignsAt = location; fetchSigns(location.latitude, location.longitude)
        }
        if (navigating) updateNavigation(location)
    }
    private fun goToMyLocation() {
        if (!hasLocation()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            return
        }
        val l = lastLocation ?: run { toast("جاري تحديد الموقع، تأكدي من GPS"); return }
        following = true; map.controller.animateTo(GeoPoint(l.latitude, l.longitude), 17.0, 550L)
    }

    private fun readPlaces(key: String): MutableList<Place> = try {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).map { Place.fromJson(array.getJSONObject(it)) }.toMutableList()
    } catch (_: Exception) { mutableListOf() }
    private fun writePlaces(key: String, places: List<Place>) {
        val a = JSONArray(); places.take(15).forEach { a.put(it.toJson()) }
        prefs.edit().putString(key, a.toString()).apply()
    }
    private fun addRecent(place: Place) {
        val list = readPlaces("recent")
        list.removeAll { abs(it.lat - place.lat) < 0.00001 && abs(it.lon - place.lon) < 0.00001 }
        list.add(0, place); writePlaces("recent", list)
    }
    private fun saveSelected() {
        val place = selected ?: return
        val labels = arrayOf("المفضلة ★", "البيت ⌂", "العمل ▣")
        AlertDialog.Builder(this).setTitle("احفظ الموقع في").setItems(labels) { _, i ->
            val key = when (i) { 1 -> "home"; 2 -> "work"; else -> "favorites" }
            if (key == "favorites") {
                val list = readPlaces(key)
                list.removeAll { abs(it.lat - place.lat) < 0.00001 && abs(it.lon - place.lon) < 0.00001 }
                list.add(0, place); writePlaces(key, list)
            } else writePlaces(key, listOf(place))
            toast("تم حفظ المكان على الجهاز")
        }.show()
    }
    private fun showSavedPlaces() {
        val items = mutableListOf<Pair<String, Place>>()
        readPlaces("home").firstOrNull()?.let { items.add("⌂ البيت — ${it.name}" to it) }
        readPlaces("work").firstOrNull()?.let { items.add("▣ العمل — ${it.name}" to it) }
        readPlaces("favorites").forEach { items.add("★ ${it.name}" to it) }
        readPlaces("recent").forEach { items.add("↺ ${it.name}" to it) }
        if (items.isEmpty()) { toast("بعد ما حفظتي أي مكان. حددي موقعاً ثم اضغطي حفظ") ; return }
        AlertDialog.Builder(this).setTitle("أماكني والمواقع الأخيرة")
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> selectPlace(items[i].second) }.show()
    }
    private fun searchOnline() {
        val query = searchBox.text.toString().trim()
        if (query.isEmpty()) { showSavedPlaces(); return }
        hideKeyboard()
        val id = ++searchRequest
        toast("جاري البحث…")
        Thread {
            try {
                val c = map.mapCenter
                val u = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=7&addressdetails=1&accept-language=ar" +
                    "&viewbox=${c.longitude - 1},${c.latitude + 1},${c.longitude + 1},${c.latitude - 1}&q=" + URLEncoder.encode(query, "UTF-8")
                val arr = JSONArray(httpGet(u))
                val places = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Place(o.optString("name").ifBlank { o.optString("display_name").substringBefore(",") },
                        o.getDouble("lat"), o.getDouble("lon"), o.optString("display_name"))
                }
                runOnUiThread {
                    if (id != searchRequest) return@runOnUiThread
                    if (places.isEmpty()) toast("ما لقينا نتائج لهذا البحث")
                    else AlertDialog.Builder(this).setTitle("نتائج البحث")
                        .setItems(places.map { it.name + "\n" + it.address.take(85) }.toTypedArray()) { _, i -> selectPlace(places[i]) }.show()
                }
            } catch (_: Exception) { toast("تعذر البحث. تحققي من الإنترنت") }
        }.start()
    }
    private fun selectPlace(place: Place) {
        if (navigating) stopNavigation()
        selected = place; route = null; ++routeRequest
        addRecent(place)
        destinationMarker?.let { map.overlays.remove(it) }
        destinationMarker = Marker(map).apply {
            position = place.point(); title = place.name; icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_pin)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        destinationMarker?.let { map.overlays.add(it) }
        routeOverlay?.let { map.overlays.remove(it) }; routeOverlay = null
        cardTitle.text = place.name
        cardBody.text = place.address.ifEmpty { String.format(Locale.US, "%.5f, %.5f", place.lat, place.lon) }
        routeButton.text = "عرض المسار"
        starButton.text = "☆ حفظ"
        card.visibility = View.VISIBLE
        following = false
        map.controller.animateTo(place.point(), 16.5, 600L)
        map.invalidate()
    }
    private fun closeCard() {
        if (navigating) stopNavigation()
        card.visibility = View.GONE
        destinationMarker?.let { map.overlays.remove(it) }; destinationMarker = null
        routeOverlay?.let { map.overlays.remove(it) }; routeOverlay = null
        route = null; selected = null; ++routeRequest
        map.invalidate()
    }
    private fun formatDistance(m: Double) = if (m < 1000) "${m.roundToInt()} م" else String.format(Locale.US, "%.1f كم", m / 1000)
    private fun calculateRoute(autoStart: Boolean) {
        val dest = selected ?: return
        val loc = lastLocation ?: run { toast("GPS لم يحدد موقعج بعد") ; return }
        val id = ++routeRequest
        routeButton.text = "جاري حساب الطريق…"
        Thread {
            try {
                // Public OSRM demo instance: development testing only; no uptime or accuracy guarantees.
                val url = "https://router.project-osrm.org/route/v1/driving/${loc.longitude},${loc.latitude};${dest.lon},${dest.lat}" +
                    "?overview=full&geometries=geojson&steps=true"
                val r = JSONObject(httpGet(url)).getJSONArray("routes").getJSONObject(0)
                val coordinates = r.getJSONObject("geometry").getJSONArray("coordinates")
                val pts = (0 until coordinates.length()).map { i ->
                    val a = coordinates.getJSONArray(i); GeoPoint(a.getDouble(1), a.getDouble(0))
                }
                val steps = mutableListOf<Turn>()
                val legs = r.getJSONArray("legs")
                for (i in 0 until legs.length()) {
                    val ss = legs.getJSONObject(i).getJSONArray("steps")
                    for (j in 0 until ss.length()) {
                        val s = ss.getJSONObject(j)
                        val man = s.getJSONObject("maneuver")
                        val a = man.getJSONArray("location")
                        steps.add(Turn(GeoPoint(a.getDouble(1), a.getDouble(0)), instruction(man, s.optString("name")), s.optDouble("distance", 0.0)))
                    }
                }
                val result = Route(pts, steps, r.getDouble("distance"), r.getDouble("duration"))
                runOnUiThread {
                    if (id != routeRequest) return@runOnUiThread
                    route = result; showRoute(result)
                    if (autoStart) { navigating = true; spokenStep = -1; nextVoiceStep = 0; routeButton.text = "إنهاء الملاحة"; updateNavigation(loc) }
                }
            } catch (_: Exception) {
                runOnUiThread { if (id == routeRequest) { routeButton.text = if (navigating) "إنهاء الملاحة" else "عرض المسار"; toast("خدمة التوجيه غير متاحة أو تعذر حساب الطريق") } }
            }
        }.start()
    }
    private fun instruction(m: JSONObject, road: String): String {
        val type = m.optString("type")
        val modifier = m.optString("modifier")
        val action = when {
            type == "arrive" -> "وصلت إلى الوجهة"
            type == "depart" -> "انطلق"
            type == "roundabout" || type == "rotary" -> "ادخل الدوار"
            modifier.contains("left") -> "انعطف يساراً"
            modifier.contains("right") -> "انعطف يميناً"
            modifier == "uturn" -> "التف للخلف عند السماح"
            else -> "استمر للأمام"
        }
        return if (road.isNotBlank() && type != "arrive") "$action نحو $road" else action
    }
    private fun showRoute(result: Route) {
        routeOverlay?.let { map.overlays.remove(it) }
        routeOverlay = Polyline().apply {
            setPoints(result.points)
            outlinePaint.color = teal; outlinePaint.strokeWidth = dp(7).toFloat()
            outlinePaint.strokeCap = Paint.Cap.ROUND; outlinePaint.isAntiAlias = true
        }
        map.overlays.add(routeOverlay)
        cardBody.text = "المسافة: ${formatDistance(result.meters)}  •  المدة: ${max(1, ceil(result.seconds / 60).toInt())} دقيقة\nتقدير تجريبي، تحقق من إشارات الطريق"
        routeButton.text = if (navigating) "إنهاء الملاحة" else "ابدأ الملاحة"
        if (result.points.size > 1) {
            val n = result.points.map { it.latitude }.maxOrNull() ?: return
            val s = result.points.map { it.latitude }.minOrNull() ?: return
            val e = result.points.map { it.longitude }.maxOrNull() ?: return
            val w = result.points.map { it.longitude }.minOrNull() ?: return
            try { map.zoomToBoundingBox(BoundingBox(n, e, s, w), true, dp(65)) } catch (_: Exception) {}
        }
        map.invalidate()
    }
    private fun beginNavigation() {
        if (route == null) { calculateRoute(true); return }
        navigating = true; following = true; spokenStep = -1; nextVoiceStep = 0
        routeButton.text = "إنهاء الملاحة"
        lastLocation?.let { updateNavigation(it) }
        toast("بدأ التوجيه التجريبي. لا تعتمدي عليه وحده أثناء القيادة")
    }
    private fun stopNavigation() {
        navigating = false; ++routeRequest; navBanner.visibility = View.GONE
        routeButton.text = if (route != null) "ابدأ الملاحة" else "عرض المسار"
        tts?.stop()
    }
    private fun updateNavigation(loc: Location) {
        val r = route ?: return
        val dest = selected ?: return
        val remainingStraight = distance(loc, dest.point())
        if (remainingStraight < 35) {
            speak("وصلت إلى الوجهة"); toast("وصلتِ إلى الوجهة"); stopNavigation(); return
        }
        if (r.points.isNotEmpty()) {
            // Nearest route vertex: conservative approximation, not true road-map matching.
            val offRoute = r.points.minOf { distance(loc, it) }
            if (offRoute > 100 && System.currentTimeMillis() - lastReroute > 25000L) {
                lastReroute = System.currentTimeMillis()
                navBanner.text = "يتم إعادة حساب الطريق…"
                calculateRoute(true)
                return
            }
        }
        if (r.turns.isEmpty()) { navBanner.text = "تابع المسار على الخريطة"; navBanner.visibility = View.VISIBLE; return }
        while (nextVoiceStep < r.turns.size - 1 && distance(loc, r.turns[nextVoiceStep].point) < 25) nextVoiceStep++
        // Advance to later maneuver when earlier point is passed and a later point is much closer.
        if (nextVoiceStep < r.turns.size - 1 && distance(loc, r.turns[nextVoiceStep + 1].point) + 75 < distance(loc, r.turns[nextVoiceStep].point)) nextVoiceStep++
        val step = r.turns[nextVoiceStep]
        val meters = distance(loc, step.point)
        navBanner.visibility = View.VISIBLE
        navBanner.text = "${formatDistance(meters)}  •  ${step.instruction}"
        if (meters < 220 && spokenStep != nextVoiceStep) {
            spokenStep = nextVoiceStep
            speak("بعد ${formatDistance(meters)}، ${step.instruction}")
        }
    }
    private fun announceCurrentTurn() {
        val r = route ?: return
        r.turns.getOrNull(nextVoiceStep)?.let { speak(it.instruction) }
    }
    private fun distance(loc: Location, p: GeoPoint): Double {
        val out = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, p.latitude, p.longitude, out)
        return out[0].toDouble()
    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = tts?.setLanguage(Locale("ar")) != TextToSpeech.LANG_MISSING_DATA
        }
    }
    private fun speak(message: String) {
        if (ttsReady) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "dallini-${System.currentTimeMillis()}")
    }

    private fun fetchSigns(lat: Double, lon: Double) {
        val id = ++signRequest
        val d = 0.008
        val bbox = "${lat-d},${lon-d},${lat+d},${lon+d}"
        val q = "[out:json][timeout:20];(" +
            "node[\"highway\"~\"stop|give_way|traffic_signals|crossing|speed_camera\"]($bbox);" +
            "node[\"traffic_sign\"]($bbox);node[\"traffic_calming\"]($bbox);" +
            "way[\"highway\"][\"maxspeed\"]($bbox););out center 180;"
        Thread {
            try {
                val url = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8")
                val data = JSONObject(httpGet(url)).getJSONArray("elements")
                val found = mutableListOf<Pair<GeoPoint, String>>()
                var bestLimit: Int? = null
                var bestLimitDistance = 35.0
                val current = lastLocation
                for (i in 0 until data.length()) {
                    val e = data.getJSONObject(i)
                    val tags = e.optJSONObject("tags") ?: continue
                    val center = e.optJSONObject("center") ?: e
                    if (!center.has("lat") || !center.has("lon")) continue
                    val p = GeoPoint(center.getDouble("lat"), center.getDouble("lon"))
                    if (e.optString("type") == "way") {
                        // The way's center is NOT an accurate speed limit match; do not display it as a legal limit.
                        continue
                    }
                    val label = signLabel(tags)
                    if (label != null) found.add(p to label)
                    // Speed limits require proper way geometry matching, intentionally unavailable in this MVP.
                }
                runOnUiThread {
                    if (id != signRequest) return@runOnUiThread
                    map.overlays.removeAll(signMarkers.toSet()); signMarkers.clear()
                    found.take(55).forEach { (p, label) ->
                        val m = Marker(map).apply {
                            position = p; title = label
                            icon = signDrawable(label); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        }
                        signMarkers.add(m); map.overlays.add(m)
                    }
                    currentLimit = bestLimit
                    limitView.text = if (bestLimit == null) "الحد: غير متوفر" else "الحد: $bestLimit"
                    map.invalidate()
                }
            } catch (_: Exception) { /* Overpass public service is best-effort. */ }
        }.start()
    }
    private fun signLabel(t: JSONObject): String? = when {
        t.optString("highway") == "stop" -> "قف"
        t.optString("highway") == "give_way" -> "أعطِ الأولوية"
        t.optString("highway") == "traffic_signals" -> "إشارة مرور"
        t.optString("highway") == "crossing" -> "عبور مشاة"
        t.optString("highway") == "speed_camera" -> "كاميرا سرعة"
        t.has("traffic_calming") -> "مطب"
        t.has("traffic_sign") -> "علامة مرورية"
        else -> null
    }
    private fun signDrawable(label: String): android.graphics.drawable.Drawable {
        val bmp = Bitmap.createBitmap(dp(34), dp(34), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = when (label) { "قف" -> Color.rgb(204, 53, 58); "إشارة مرور" -> teal; else -> Color.rgb(238, 157, 40) }
        canvas.drawCircle(dp(17).toFloat(), dp(17).toFloat(), dp(15).toFloat(), paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat()
        canvas.drawCircle(dp(17).toFloat(), dp(17).toFloat(), dp(14).toFloat(), paint)
        paint.style = Paint.Style.FILL; paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER; paint.textSize = dp(14).toFloat()
        val symbol = when (label) { "قف" -> "قف"; "إشارة مرور" -> "●"; "عبور مشاة" -> "م"; "كاميرا سرعة" -> "ك"; "مطب" -> "!"; else -> "!" }
        canvas.drawText(symbol, dp(17).toFloat(), dp(22).toFloat(), paint)
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }
    private fun httpGet(u: String): String {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000; conn.readTimeout = 22000
        conn.setRequestProperty("User-Agent", "DalliniNavigation/0.3 (personal testing app)")
        conn.setRequestProperty("Accept-Language", "ar")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }
    private fun toast(message: String) { runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() } }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { map.onPause(); super.onPause() }
    override fun onDestroy() {
        ++searchRequest; ++routeRequest; ++signRequest
        handler.removeCallbacksAndMessages(null)
        try { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this) } catch (_: Exception) {}
        tts?.stop(); tts?.shutdown(); map.onDetach()
        super.onDestroy()
    }
}
