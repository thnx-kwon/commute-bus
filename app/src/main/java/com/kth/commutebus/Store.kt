package com.kth.commutebus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Ride(val ts: Long, var slot: String, val src: String)

data class Settings(
    // 기본: 06:00부터 15분 간격 ~ 09:15, 막차 09:20 (설정에서 실제 시간표로 수정)
    val slots: List<String> = (0 until 14).map { Predictor.hm(360.0 + it * 15) } + "09:20",
    val walk: Int = 5,             // 집 → 정류장 걷는 시간(분)
    val safe: Double = 0.1,        // 0.1 = 10번 중 9번 탑승, 0.25, 0.5
    val lag: Int = 0,              // 자동 기록 보정(분): 기록 시각에서 뺌
    val w1: String = "05:50",      // 자동 기록 허용 시간대
    val w2: String = "09:45",
    val weekdaysOnly: Boolean = true,
    val requireVehicle: Boolean = true, // 반경 이탈 + 차량 탑승 둘 다 있어야 기록
    val fav: String? = null,       // 주로 타는 버스
    val stopLat: Double? = null,
    val stopLng: Double? = null,
    val radius: Int = 100,
    val alarmOn: Boolean = true,
    val alertBefore: Int = 3,      // 나갈 시각 몇 분 전에 알림
    val trackingOn: Boolean = false,
)

object Store {
    private const val P = "bus"
    private fun prefs(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun rides(c: Context): MutableList<Ride> {
        val arr = JSONArray(prefs(c).getString("rides", "[]"))
        return MutableList(arr.length()) {
            val o = arr.getJSONObject(it)
            Ride(o.getLong("ts"), o.getString("slot"), o.optString("src", "manual"))
        }
    }

    fun saveRides(c: Context, list: List<Ride>) {
        val arr = JSONArray()
        list.sortedBy { it.ts }.forEach {
            arr.put(JSONObject().put("ts", it.ts).put("slot", it.slot).put("src", it.src))
        }
        prefs(c).edit().putString("rides", arr.toString()).apply()
    }

    /** 20분 안에 기존 기록이 있으면 null(중복). */
    fun addRide(c: Context, ts: Long, src: String): Ride? {
        val list = rides(c)
        if (list.any { kotlin.math.abs(it.ts - ts) < 20 * 60_000L }) return null
        val s = settings(c)
        val r = Ride(ts, Predictor.slotFor(Predictor.minOf(ts), s.slots), src)
        list.add(r)
        saveRides(c, list)
        Notifier.scheduleNext(c)
        return r
    }

    fun settings(c: Context): Settings {
        val raw = prefs(c).getString("settings", null) ?: return Settings()
        val o = JSONObject(raw)
        val d = Settings()
        val sl = o.optJSONArray("slots")
        return Settings(
            slots = if (sl != null) List(sl.length()) { sl.getString(it) } else d.slots,
            walk = o.optInt("walk", d.walk),
            safe = o.optDouble("safe", d.safe),
            lag = o.optInt("lag", d.lag),
            w1 = o.optString("w1", d.w1),
            w2 = o.optString("w2", d.w2),
            weekdaysOnly = o.optBoolean("weekdaysOnly", d.weekdaysOnly),
            requireVehicle = o.optBoolean("requireVehicle", d.requireVehicle),
            fav = if (o.has("fav") && !o.isNull("fav")) o.getString("fav") else null,
            stopLat = if (o.has("stopLat")) o.getDouble("stopLat") else null,
            stopLng = if (o.has("stopLng")) o.getDouble("stopLng") else null,
            radius = o.optInt("radius", d.radius),
            alarmOn = o.optBoolean("alarmOn", d.alarmOn),
            alertBefore = o.optInt("alertBefore", d.alertBefore),
            trackingOn = o.optBoolean("trackingOn", d.trackingOn),
        )
    }

    fun saveSettings(c: Context, s: Settings) {
        val o = JSONObject()
            .put("slots", JSONArray(s.slots))
            .put("walk", s.walk).put("safe", s.safe).put("lag", s.lag)
            .put("w1", s.w1).put("w2", s.w2)
            .put("weekdaysOnly", s.weekdaysOnly).put("requireVehicle", s.requireVehicle)
            .put("radius", s.radius).put("alarmOn", s.alarmOn)
            .put("alertBefore", s.alertBefore).put("trackingOn", s.trackingOn)
        s.fav?.let { o.put("fav", it) }
        s.stopLat?.let { o.put("stopLat", it) }
        s.stopLng?.let { o.put("stopLng", it) }
        prefs(c).edit().putString("settings", o.toString()).apply()
    }

    // 감지 상태 (정류장 진입/이탈, 차량 탑승 시각)
    fun getT(c: Context, k: String) = prefs(c).getLong("t_$k", 0L)
    fun putT(c: Context, k: String, v: Long) = prefs(c).edit().putLong("t_$k", v).apply()
}
