package com.kth.commutebus

import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 나갈 시각 예측
 *  1) 배차(예: 07:15)마다 과거 탑승 기록의 "지연(분) = 실제 탑승 - 예정"을 모은다 (최근 40개).
 *     같은 요일 기록이 4개 이상이면 그 요일 것만 쓴다.
 *  2) 예상 도착 = 예정 + 지연의 중앙값
 *  3) 집 출발 = 예정 + 지연의 하위 분위수(확실도) - 걷는 시간
 *     하위 10%를 쓰면 "버스가 평소보다 일찍 오는 날"까지 대비 → 10번 중 9번은 탄다.
 *  기록이 3개 미만이면 예정 시각 - 걷는 시간 - 2분.
 */
object Predictor {
    fun toM(s: String): Int {
        val p = s.trim().split(":")
        return p[0].toInt() * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    fun hm(m: Double): String {
        val t = ((m.roundToInt() % 1440) + 1440) % 1440
        return "%02d:%02d".format(t / 60, t % 60)
    }

    fun minOf(ts: Long): Double {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60.0
    }

    fun dowOf(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.DAY_OF_WEEK)

    /** 탑승 시각 m 이 어느 배차인지: (m + 3분) 이전의 가장 늦은 배차. */
    fun slotFor(m: Double, slots: List<String>): String {
        val sorted = slots.sortedBy { toM(it) }
        return sorted.lastOrNull { toM(it) <= m + 3 } ?: sorted.first()
    }

    fun quantile(a: List<Double>, p: Double): Double {
        if (a.isEmpty()) return Double.NaN
        val b = a.sorted()
        val i = (b.size - 1) * p
        val lo = floor(i).toInt()
        val hi = ceil(i).toInt()
        return b[lo] + (b[hi] - b[lo]) * (i - lo)
    }

    data class Plan(
        val slot: String, val n: Int, val basis: String, val ok: Boolean,
        val bus: Double, val leave: Double,
        val p10: Double, val med: Double, val p90: Double, val delays: List<Double>,
    )

    private val DAYS = arrayOf("", "일", "월", "화", "수", "목", "금", "토")

    fun plan(rides: List<Ride>, s: Settings, slot: String, dow: Int): Plan {
        val sm = toM(slot).toDouble()
        val all = rides.filter { it.slot == slot }.sortedByDescending { it.ts }.take(40)
        val same = all.filter { dowOf(it.ts) == dow }
        val use = if (same.size >= 4) same else all
        val dl = use.map { minOf(it.ts) - sm }
        val basis = if (same.size >= 4) "${DAYS[dow]}요일" else "전체"
        if (all.size < 3) {
            return Plan(slot, all.size, basis, false, sm, sm - s.walk - 2, 0.0, 0.0, 0.0, dl)
        }
        val med = quantile(dl, 0.5)
        return Plan(
            slot, all.size, basis, true,
            bus = sm + med,
            leave = sm + quantile(dl, s.safe) - s.walk,
            p10 = quantile(dl, 0.1), med = med, p90 = quantile(dl, 0.9), delays = dl,
        )
    }

    /** 표시할 배차: 고른 것 → 주로 타는 버스 → 아직 나갈 수 있는 첫 배차. */
    fun pickSlot(rides: List<Ride>, s: Settings, sel: String?): String {
        if (sel != null && sel in s.slots) return sel
        if (s.fav != null && s.fav in s.slots) return s.fav
        val now = System.currentTimeMillis()
        val nm = minOf(now)
        val dow = dowOf(now)
        return s.slots.sortedBy { toM(it) }.firstOrNull { plan(rides, s, it, dow).leave >= nm }
            ?: s.slots.first()
    }
}
