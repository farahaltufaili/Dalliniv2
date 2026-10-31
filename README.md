# دلّيني Dallini v3 (development preview)

Native Android Kotlin app. Application ID `com.dallini.app`, min SDK 26, compile/target SDK 35.

## Included in v3

- Map using osmdroid/OpenStreetMap with a day/night presentation toggle (night colors are a local raster color filter, not a native dark map style).
- Search using public Nominatim with Arabic labels and a results dialog.
- Device-local recent searches/locations, favorites, Home and Work via `SharedPreferences`.
- Destination and route details: OSRM public *demo* server driving route, duration, distance and route polyline.
- Foreground navigation preview: turn banner, Arabic text-to-speech when supported by device, and reroute attempt if the GPS location differs from the nearest route vertex by >100 m.
- GPS-based speed display, map controls, uniform traffic marker symbols for a limited OSM set from public Overpass.
- Preserves prior application icon, splash screen, and credit: `تم تطوير التطبيق بواسطة كرار الطفيلي`.

## Critical limitations / requirements for production

- **No speed limits are asserted**: OSM `maxspeed` is commonly attached to ways, and a safe way/road matching service has NOT been implemented. The app displays `الحد: غير متوفر`. Excess-speed warning code exists but is inactive without a verified speed limit. Do **not** use it as a speed-limit authority.
- Routing through `router.project-osrm.org` is for low-volume development tests only. Move to a licensed/hosted routing backend with quotas/SLAs before release. No live traffic, road closure awareness or turn-restriction guarantees beyond data supplied by demo routing.
- Rerouting uses distance to the closest **vertex**, not a robust map-matching algorithm. Directions are approximate; no background navigation or foreground location service; screen-off updates are not guaranteed.
- Public Nominatim and Overpass have usage policies, limited capacity, and may throttle or fail. Add caching, suitable user-agent/contact, and production providers before public distribution. Requests should not be made at high rate.
- No downloaded offline map tiles or offline routing. Requires internet.
- Navigation/voice depends on Android Text-to-Speech Arabic voice installed and enabled.
- This project was **not compiled or run in this environment**. Please test on emulator/device via Android Studio or GitHub Actions before installing on a device.

## Building

Open the **repository root** (containing `settings.gradle.kts`) in Android Studio. Allow SDK 35 and Gradle dependencies to download and Gradle Sync to complete. Choose an emulator/device, then Run. For APK use **Build > Build Bundle(s) / APK(s) > Build APK(s)**. Or run GitHub Actions workflow `Build Dallini APK`; download the `Dallini-debug-APK` artifact.

### Only changed from uploaded `Dalliniv2-main.zip`

- `app/src/main/java/com/dallini/app/MainActivity.kt` — UI, search, favorites, map style, route, voice, rerouting, signs.
- `app/src/main/java/com/dallini/app/SplashActivity.kt` — restored correct standalone SplashActivity (uploaded project duplicated MainActivity here).
- `app/build.gradle.kts` — version to 0.3.
- `.github/workflows/build.yml` — better build logs and APK artifact.
- `README.md` — instructions, limitations.

Upload these paths into the **same existing directories** of your GitHub repository; don't nest an extra root folder.
