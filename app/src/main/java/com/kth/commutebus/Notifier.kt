package com.kth.commutebus

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Calendar

object Notifier {
    private const val CH_INFO = "info"
    private const val CH_LEAVE = "leave"

    private fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "탑승 기록", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_LEAVE, "출발 알림", NotificationManager.IMPORTANCE_HIGH))
    }

    @SuppressLint("MissingPermission")
    private fun post(c: Context, ch: String, id: Int, title: String, text: String) {
        if (!Tracker.has(c, Manifest.permission.POST_NOTIFICATIONS) && Build.VERSION.SDK_INT >= 33) return
        channels(c)
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(c, ch)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true)
            .setPriority(if (ch == CH_LEAVE) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(c).notify(id, n)
    }

    fun info(c: Context, title: String, text: String) = post(c, CH_INFO, 1, title, text)

    private fun alarmPi(c: Context) = PendingIntent.getBroadcast(
        c, 10, Intent(c, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 다음 출근일의 '나갈 시각 - N분'에 알림 예약. 주로 타는 버스 기준(없으면 첫 배차). */
    fun scheduleNext(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(alarmPi(c))
        val s = Store.settings(c)
        if (!s.alarmOn) return
        val slot = s.fav ?: return
        val rides = Store.rides(c)
        val now = System.currentTimeMillis()
        for (day in 0..7) {
            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, day)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            if (s.weekdaysOnly && (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY)) continue
            val p = Predictor.plan(rides, s, slot, dow)
            val at = cal.timeInMillis + ((p.leave - s.alertBefore) * 60_000).toLong()
            if (at <= now + 30_000) continue
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmPi(c))
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmPi(c))
            }
            Store.putT(c, "nextAlarm", at)
            return
        }
    }

    fun leaveNow(c: Context) {
        val s = Store.settings(c)
        val slot = s.fav ?: return
        val now = System.currentTimeMillis()
        val p = Predictor.plan(Store.rides(c), s, slot, Predictor.dowOf(now))
        val range = if (p.ok) {
            val sm = Predictor.toM(slot)
            " (보통 ${Predictor.hm(sm + p.p10)}–${Predictor.hm(sm + p.p90)})"
        } else " (기록 부족 · 예정 시각 기준)"
        post(
            c, CH_LEAVE, 2,
            "${Predictor.hm(p.leave)}에 나가세요 · $slot 버스",
            "버스 예상 ${Predictor.hm(p.bus)}$range · 걷는 ${s.walk}분 포함",
        )
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Notifier.leaveNow(c)
        Notifier.scheduleNext(c)
    }
}
