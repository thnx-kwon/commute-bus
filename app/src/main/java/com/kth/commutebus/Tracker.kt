package com.kth.commutebus

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import java.util.Calendar
import kotlin.math.abs

/**
 * 탑승 감지
 *  - 정류장 지오펜스 진입(near) → 이탈(exit)
 *  - 차량 탑승 인식 IN_VEHICLE 진입(veh)
 *  출근 시간대에 exit 와 veh 가 10분 안에 함께 일어나면 탑승으로 기록.
 *  기록 시각 = 둘 중 이른 쪽 - 보정(분). (설정에서 차량 인식 없이 이탈만으로 기록도 가능)
 */
object Tracker {
    private const val WINDOW = 10 * 60_000L

    fun has(c: Context, p: String) =
        ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun permsOk(c: Context) =
        has(c, Manifest.permission.ACCESS_FINE_LOCATION) &&
            has(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION) &&
            has(c, Manifest.permission.ACTIVITY_RECOGNITION)

    private fun pi(c: Context, cls: Class<out BroadcastReceiver>, code: Int) =
        PendingIntent.getBroadcast(
            c, code, Intent(c, cls),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    /** 감지 등록. 성공/실패 메시지를 콜백으로. */
    @SuppressLint("MissingPermission")
    fun start(c: Context, done: (String) -> Unit = {}) {
        val s = Store.settings(c)
        if (!s.trackingOn) { stop(c); done("자동 기록 꺼짐"); return }
        if (s.stopLat == null || s.stopLng == null) { done("정류장 위치를 먼저 정하세요"); return }
        if (!permsOk(c)) { done("권한이 부족해요 (위치 '항상 허용', 신체 활동)"); return }

        val fence = Geofence.Builder()
            .setRequestId("stop")
            .setCircularRegion(s.stopLat, s.stopLng, s.radius.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val req = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(fence).build()
        val gpi = pi(c, GeofenceReceiver::class.java, 1)
        val gc = LocationServices.getGeofencingClient(c)
        gc.removeGeofences(gpi).addOnCompleteListener {
            gc.addGeofences(req, gpi)
                .addOnSuccessListener { done("자동 기록 켜짐 · 정류장 반경 ${s.radius}m") }
                .addOnFailureListener { e -> done("지오펜스 등록 실패: ${e.message} (위치·Google 위치 정확도 켜기)") }
        }

        val tr = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
        )
        ActivityRecognition.getClient(c)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(tr), pi(c, ActivityReceiver::class.java, 2))
    }

    @SuppressLint("MissingPermission")
    fun stop(c: Context) {
        LocationServices.getGeofencingClient(c).removeGeofences(pi(c, GeofenceReceiver::class.java, 1))
        if (has(c, Manifest.permission.ACTIVITY_RECOGNITION)) {
            ActivityRecognition.getClient(c).removeActivityTransitionUpdates(pi(c, ActivityReceiver::class.java, 2))
        }
    }

    fun inWindow(s: Settings, ts: Long): Boolean {
        val m = Predictor.minOf(ts)
        if (m < Predictor.toM(s.w1) || m > Predictor.toM(s.w2)) return false
        if (s.weekdaysOnly) {
            val d = Predictor.dowOf(ts)
            if (d == Calendar.SATURDAY || d == Calendar.SUNDAY) return false
        }
        return true
    }

    /** 조건이 갖춰지면 탑승 기록. 리시버들이 이벤트마다 호출. */
    fun evaluate(c: Context) {
        val s = Store.settings(c)
        val near = Store.getT(c, "near")
        val exit = Store.getT(c, "exit")
        val veh = Store.getT(c, "veh")
        if (exit == 0L) return
        val now = System.currentTimeMillis()

        // 출근 시간대가 아니거나, 정류장에 머문 적 없이 이탈만 왔으면 버림
        if (!inWindow(s, exit) || near == 0L || near > exit || exit - near > 3 * 3600_000L) {
            Store.putT(c, "exit", 0); return
        }
        val vehOk = veh != 0L && abs(veh - exit) <= WINDOW
        if (s.requireVehicle && !vehOk) {
            if (now - exit > 2 * WINDOW) Store.putT(c, "exit", 0) // 너무 오래 기다림 → 포기
            return
        }
        val base = if (vehOk) minOf(exit, veh) else exit
        val ts = base - s.lag * 60_000L
        Store.putT(c, "exit", 0); Store.putT(c, "veh", 0); Store.putT(c, "near", 0)
        val r = Store.addRide(c, ts, "auto") ?: return
        val d = Predictor.minOf(r.ts) - Predictor.toM(r.slot)
        Notifier.info(c, "탑승 기록 ${Predictor.hm(Predictor.minOf(r.ts))}",
            "${r.slot} 버스 (${if (d >= 0) "+" else ""}${d.toInt()}분) · 틀렸으면 앱에서 고치세요")
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val e = GeofencingEvent.fromIntent(i) ?: return
        if (e.hasError()) return
        val t = e.triggeringLocation?.time ?: System.currentTimeMillis()
        when (e.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> Store.putT(c, "near", t)
            Geofence.GEOFENCE_TRANSITION_EXIT -> { Store.putT(c, "exit", t); Tracker.evaluate(c) }
        }
    }
}

class ActivityReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (!ActivityTransitionResult.hasResult(i)) return
        val r = ActivityTransitionResult.extractResult(i) ?: return
        for (ev in r.transitionEvents) {
            if (ev.activityType == DetectedActivity.IN_VEHICLE &&
                ev.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
            ) {
                // 이벤트 실제 발생 시각(부팅 기준)을 벽시계로 변환
                val ago = (SystemClock.elapsedRealtimeNanos() - ev.elapsedRealTimeNanos) / 1_000_000L
                Store.putT(c, "veh", System.currentTimeMillis() - ago)
            }
        }
        Tracker.evaluate(c)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Tracker.start(c)       // 재부팅하면 지오펜스가 지워지므로 다시 등록
        Notifier.scheduleNext(c)
    }
}
