package com.nscb.spiritscan.feeds

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.concurrent.thread
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Live context that can EXPLAIN an event, never create one:
 *  - NOAA SWPC 1-minute estimated planetary Kp   (services.swpc.noaa.gov/json/planetary_k_index_1m.json)
 *  - Open-Meteo current weather for a lat/lon you enter (api.open-meteo.com/v1/forecast?current=...)
 *  - USGS M4.5+ earthquakes of the last day      (earthquake.usgs.gov .../summary/4.5_day.geojson)
 * Polls every 5 minutes. Without a location only the global feeds (Kp, quakes) are used.
 */
data class ContextSnapshot(
    val kp: Float? = null,
    val kpAgeMin: Int? = null,
    val tempC: Float? = null,
    val humidity: Float? = null,
    val pressureHpa: Float? = null,
    val windKmh: Float? = null,
    val gustKmh: Float? = null,
    val weatherCode: Int? = null,
    val quakeMag: Float? = null,
    val quakeAgoMin: Int? = null,
    val quakeKm: Int? = null,
    val quakePlace: String? = null,
    val updatedMs: Long = 0L,
    val error: String = "",
    val hasLocation: Boolean = false
)

class ContextFeeds(context: Context) {
    private val prefs = context.getSharedPreferences("spirit_ctx", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(ContextSnapshot(hasLocation = prefs.contains("lat") && prefs.contains("lon")))
    val state: StateFlow<ContextSnapshot> = _state

    @Volatile
    private var running = false

    @Volatile
    private var wake = false

    fun location(): Pair<Double, Double>? {
        if (!prefs.contains("lat") || !prefs.contains("lon")) return null
        val la = prefs.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lo = prefs.getString("lon", null)?.toDoubleOrNull() ?: return null
        return Pair(la, lo)
    }

    fun setLocation(lat: Double, lon: Double) {
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return
        prefs.edit().putString("lat", lat.toString()).putString("lon", lon.toString()).apply()
        _state.value = _state.value.copy(hasLocation = true)
        refreshNow()
    }

    fun refreshNow() {
        wake = true
    }

    fun start() {
        if (running) return
        running = true
        thread(name = "context-feeds", isDaemon = true) {
            while (running) {
                try {
                    poll()
                } catch (_: Exception) {
                }
                var waited = 0
                wake = false
                while (running && !wake && waited < 300) {
                    try {
                        Thread.sleep(1000)
                    } catch (_: Exception) {
                    }
                    waited++
                }
            }
        }
    }

    fun stop() {
        running = false
    }

    private fun httpGet(url: String): String? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", "SpiritScan/8.9 (personal research app)")
            if (c.responseCode != 200) {
                c.disconnect()
                null
            } else {
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                body
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun poll() {
        var s = _state.value
        val errors = ArrayList<String>()
        val loc = location()
        s = s.copy(hasLocation = loc != null)

        val kpBody = httpGet("https://services.swpc.noaa.gov/json/planetary_k_index_1m.json")
        if (kpBody != null) {
            try {
                val arr = JSONArray(kpBody)
                if (arr.length() > 0) {
                    val last = arr.getJSONObject(arr.length() - 1)
                    val kp = last.optDouble("estimated_kp", Double.NaN)
                    val tag = last.optString("time_tag", "")
                    var age: Int? = null
                    if (tag.isNotEmpty()) {
                        val t = LocalDateTime.parse(tag).toInstant(ZoneOffset.UTC).toEpochMilli()
                        age = ((System.currentTimeMillis() - t) / 60000L).toInt()
                    }
                    if (!kp.isNaN()) s = s.copy(kp = kp.toFloat(), kpAgeMin = age)
                }
            } catch (_: Exception) {
                errors.add("kp parse")
            }
        } else {
            errors.add("kp")
        }

        if (loc != null) {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${loc.first}&longitude=${loc.second}" +
                "&current=temperature_2m,relative_humidity_2m,surface_pressure,wind_speed_10m,wind_gusts_10m,weather_code"
            val body = httpGet(url)
            if (body != null) {
                try {
                    val cur: JSONObject = JSONObject(body).getJSONObject("current")
                    s = s.copy(
                        tempC = cur.optDouble("temperature_2m", Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
                        humidity = cur.optDouble("relative_humidity_2m", Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
                        pressureHpa = cur.optDouble("surface_pressure", Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
                        windKmh = cur.optDouble("wind_speed_10m", Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
                        gustKmh = cur.optDouble("wind_gusts_10m", Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
                        weatherCode = if (cur.has("weather_code")) cur.optInt("weather_code") else null
                    )
                } catch (_: Exception) {
                    errors.add("weather parse")
                }
            } else {
                errors.add("weather")
            }
        }

        val qBody = httpGet("https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/4.5_day.geojson")
        if (qBody != null) {
            try {
                val feats = JSONObject(qBody).getJSONArray("features")
                var best: JSONObject? = null
                var bestT = 0L
                for (i in 0 until feats.length()) {
                    val f = feats.getJSONObject(i)
                    val t = f.getJSONObject("properties").optLong("time", 0L)
                    if (t > bestT) {
                        bestT = t
                        best = f
                    }
                }
                if (best != null) {
                    val p = best.getJSONObject("properties")
                    val coords = best.getJSONObject("geometry").getJSONArray("coordinates")
                    var km: Int? = null
                    if (loc != null) {
                        km = haversineKm(loc.first, loc.second, coords.getDouble(1), coords.getDouble(0)).toInt()
                    }
                    s = s.copy(
                        quakeMag = p.optDouble("mag", Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
                        quakeAgoMin = ((System.currentTimeMillis() - bestT) / 60000L).toInt(),
                        quakeKm = km,
                        quakePlace = p.optString("place", "")
                    )
                }
            } catch (_: Exception) {
                errors.add("quake parse")
            }
        } else {
            errors.add("quake")
        }
        _state.value = s.copy(updatedMs = System.currentTimeMillis(), error = errors.joinToString(", "))
    }

    private fun haversineKm(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6371.0
        val dLa = Math.toRadians(la2 - la1)
        val dLo = Math.toRadians(lo2 - lo1)
        val a = sin(dLa / 2) * sin(dLa / 2) + cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLo / 2) * sin(dLo / 2)
        return 2 * r * asin(sqrt(a))
    }

    /** One line of external context that could plausibly explain a magnetic / acoustic / EM disturbance right now. */
    fun explain(): String {
        val s = _state.value
        val parts = ArrayList<String>()
        val kp = s.kp
        if (kp != null) {
            if (kp >= 5f) parts.add("geomagnetic storm (Kp ${"%.1f".format(kp)})")
            else if (kp >= 4f) parts.add("active geomagnetic field (Kp ${"%.1f".format(kp)})")
        }
        val wc = s.weatherCode
        if (wc != null && wc >= 95 && wc <= 99) parts.add("thunderstorm reported nearby")
        val g = s.gustKmh
        if (g != null && g >= 50f) parts.add("strong wind gusts (${g.toInt()} km/h)")
        val qm = s.quakeMag
        val qa = s.quakeAgoMin
        if (qm != null && qa != null && qa <= 60) {
            val km = s.quakeKm
            if ((km != null && km <= 1500) || qm >= 6.5f) {
                parts.add("M${"%.1f".format(qm)} quake ${qa} min ago${if (km != null) ", $km km away" else ""}")
            }
        }
        return if (parts.isEmpty()) "no matching external context" else parts.joinToString("; ")
    }
}
