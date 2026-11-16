package com.kth.commutebus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.provider.Settings as AndroidSettings

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private var sel: String? = null
    private val red = Color.parseColor("#E4002B")
    private val ink get() = if (night()) Color.parseColor("#F2F2F2") else Color.parseColor("#111111")
    private val mut get() = if (night()) Color.parseColor("#9A9A9A") else Color.parseColor("#6B6B6B")
    private val days = arrayOf("", "일", "월", "화", "수", "목", "금", "토")

    private val bgPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        restart(); render()
    }
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askBackground(); render()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(40))
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); render() }

    // ---------- 화면 ----------
    private fun render() {
        root.removeAllViews()
        val s = Store.settings(this)
        val rides = Store.rides(this)
        val now = System.currentTimeMillis()
        val dow = Predictor.dowOf(now)
        val slot = Predictor.pickSlot(rides, s, sel)
        val p = Predictor.plan(rides, s, slot, dow)
        val sm = Predictor.toM(slot)

        rule(10); text("출근버스 예측", 22f, true)

        // 결론
        text("$slot 버스를 타려면 집에서 나갈 시각", 14f, color = mut, top = 18)
        text(Predictor.hm(p.leave), 72f, true, red)
        text("버스 예상 ${Predictor.hm(p.bus)}" +
            if (p.ok) "  ·  보통 ${Predictor.hm(sm + p.p10)}–${Predictor.hm(sm + p.p90)}" else "", 18f, true)
        text(
            if (p.ok) "걷는 ${s.walk}분 포함 · ${safeLabel(s.safe)} · ${p.basis} 기록 ${p.delays.size}개 기준"
            else "기록 ${p.n}개 — 3개부터 예측합니다. 지금은 예정 시각 − 걷는 시간 − 2분.",
            14f, color = mut,
        )

        // 배차 선택
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        s.slots.forEach { x ->
            chips.addView(btn(x, on = x == slot) { sel = x; render() })
        }
        root.addView(HorizontalScrollView(this).apply {
            addView(chips); isHorizontalScrollBarEnabled = false
            setPadding(0, dp(14), 0, 0)
        })
        if (s.fav == slot) text("★ 주로 타는 버스 · 출발 알림 대상", 13f, color = mut)
        else root.addView(btn("이 버스를 주로 타는 버스로 (출발 알림)") {
            Store.saveSettings(this, s.copy(fav = slot)); Notifier.scheduleNext(this); render()
        })
        root.addView(btn("지금 탑승 기록", primary = true) {
            val r = Store.addRide(this, System.currentTimeMillis(), "manual")
            toast(if (r == null) "20분 안에 이미 기록이 있어요" else "${r.slot} 버스로 기록했어요")
            render()
        })

        // 배차별 표
        section("배차별 예측")
        tableRow("예정", "예상 도착", "범위", "집 출발", "기록", header = true)
        s.slots.forEach { x ->
            val q = Predictor.plan(rides, s, x, dow); val xm = Predictor.toM(x)
            tableRow(
                x,
                if (q.ok) "${Predictor.hm(q.bus)} (${sign(q.med)})" else "—",
                if (q.ok) "${Predictor.hm(xm + q.p10)}–${Predictor.hm(xm + q.p90)}" else "—",
                Predictor.hm(q.leave), "${q.n}", hi = x == slot,
            )
        }
        text("최근 40번까지 사용. 같은 요일 기록이 4개 이상이면 요일별로 따로 계산.", 12f, color = mut, top = 6)

        // 자동 기록
        section("자동 기록")
        val fmt = SimpleDateFormat("M/d HH:mm", Locale.KOREA)
        fun t(k: String) = Store.getT(this, k).let { if (it == 0L) "—" else fmt.format(Date(it)) }
        val bat = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        text(
            "권한: " + (if (Tracker.permsOk(this)) "OK" else "부족 (위치 '항상 허용' + 신체 활동 필요)") +
                "\n배터리 최적화 예외: " + (if (bat) "OK" else "아님 — 감지가 늦거나 빠질 수 있음") +
                "\n정류장: " + (if (s.stopLat != null) "%.5f, %.5f · 반경 %dm".format(s.stopLat, s.stopLng, s.radius) else "미설정") +
                "\n최근 감지 — 정류장 진입 ${t("near")} · 이탈 ${t("exit")} · 차량 ${t("veh")}" +
                "\n다음 출발 알림: " + (if (s.alarmOn && s.fav != null) t("nextAlarm") else "꺼짐 (주로 타는 버스를 정하면 켜짐)"),
            14f,
        )
        root.addView(SwitchCompat(this).apply {
            text = "자동 기록 켜기"; textSize = 16f; isChecked = s.trackingOn
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, v ->
                Store.saveSettings(this@MainActivity, Store.settings(this@MainActivity).copy(trackingOn = v))
                restart(); render()
            }
        })
        val r1 = row()
        r1.addView(btn("권한 설정") { askPerms() })
        r1.addView(btn("배터리 예외") {
            startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        root.addView(btn("지금 서 있는 곳을 정류장으로") { setStopHere() })
        val ll = edit("위도,경도 직접 입력 (지도 앱에서 복사)", "", InputType.TYPE_CLASS_TEXT)
        root.addView(btn("입력한 좌표로 정류장 설정") {
            val v = ll.text.split(",").mapNotNull { it.trim().toDoubleOrNull() }
            if (v.size != 2) { toast("예: 37.21234, 127.07123"); return@btn }
            Store.saveSettings(this, Store.settings(this).copy(stopLat = v[0], stopLng = v[1])); restart(); render()
        })

        // 기록
        section("탑승 기록 (총 ${rides.size}회)")
        if (rides.isEmpty()) text("아직 기록이 없어요. 버스에 타면 '지금 탑승 기록'을 누르거나 자동 기록을 켜세요.", 14f, color = mut)
        val df = SimpleDateFormat("M/d", Locale.KOREA)
        rides.sortedByDescending { it.ts }.take(40).forEach { r ->
            val m = Predictor.minOf(r.ts); val d = m - Predictor.toM(r.slot)
            val tv = text(
                "${df.format(Date(r.ts))}(${days[Predictor.dowOf(r.ts)]})  ${Predictor.hm(m)}   ${r.slot} 버스 ${sign(d)}   ${if (r.src == "auto") "자동" else "직접"}",
                15f, color = if (d > 3) red else ink, top = 0,
            )
            tv.setPadding(0, dp(10), 0, dp(10))
            tv.setOnClickListener { editRide(r) }
        }
        text("항목을 누르면 배차 변경·삭제", 12f, color = mut)

        // 설정
        section("설정")
        val eSlots = edit("배차 시각 (쉼표로 구분)", s.slots.joinToString(", "), InputType.TYPE_CLASS_TEXT)
        val eWalk = edit("집 → 정류장 걷는 시간 (분)", "${s.walk}", InputType.TYPE_CLASS_NUMBER)
        text("얼마나 확실하게 탈까요", 14f, true, top = 10)
        val rg = RadioGroup(this)
        listOf(0.1 to "확실하게 — 10번 중 9번 탑승", 0.25 to "보통 — 4번 중 3번", 0.5 to "빠듯하게 — 반반").forEachIndexed { i, (v, l) ->
            rg.addView(RadioButton(this).apply { id = 100 + i; text = l; textSize = 15f; isChecked = s.safe == v; tag = v })
        }
        root.addView(rg)
        val eAlert = edit("출발 알림: 나갈 시각 몇 분 전", "${s.alertBefore}", InputType.TYPE_CLASS_NUMBER)
        val eLag = edit("자동 기록 보정 (분, 기록 시각에서 뺌)", "${s.lag}", InputType.TYPE_CLASS_NUMBER)
        val eRad = edit("정류장 반경 (m, 80~150 권장)", "${s.radius}", InputType.TYPE_CLASS_NUMBER)
        val eW1 = edit("자동 기록 시작 시각", s.w1, InputType.TYPE_CLASS_DATETIME)
        val eW2 = edit("자동 기록 끝 시각", s.w2, InputType.TYPE_CLASS_DATETIME)
        val swWd = switch("평일만", s.weekdaysOnly)
        val swVeh = switch("차량 탑승 인식까지 확인 (끄면 반경 이탈만으로 기록)", s.requireVehicle)
        val swAl = switch("출발 알림 받기", s.alarmOn)
        root.addView(btn("설정 저장", primary = true) {
            val sl = eSlots.text.split(Regex("[,\\s]+")).filter { it.isNotBlank() }.mapNotNull {
                runCatching { Predictor.hm(Predictor.toM(it).toDouble()) }.getOrNull()
            }.distinct().sortedBy { Predictor.toM(it) }
            if (sl.isEmpty()) { toast("배차 시각을 확인하세요"); return@btn }
            val okT = { x: String, d: String -> runCatching { Predictor.hm(Predictor.toM(x).toDouble()) }.getOrDefault(d) }
            val cur = Store.settings(this)
            Store.saveSettings(this, cur.copy(
                slots = sl,
                walk = eWalk.text.toString().toIntOrNull() ?: cur.walk,
                safe = (rg.findViewById<RadioButton>(rg.checkedRadioButtonId)?.tag as? Double) ?: cur.safe,
                alertBefore = eAlert.text.toString().toIntOrNull() ?: cur.alertBefore,
                lag = eLag.text.toString().toIntOrNull() ?: cur.lag,
                radius = (eRad.text.toString().toIntOrNull() ?: cur.radius).coerceIn(50, 500),
                w1 = okT(eW1.text.toString(), cur.w1), w2 = okT(eW2.text.toString(), cur.w2),
                weekdaysOnly = swWd.isChecked, requireVehicle = swVeh.isChecked, alarmOn = swAl.isChecked,
                fav = cur.fav?.takeIf { it in sl },
            ))
            restart(); Notifier.scheduleNext(this); toast("저장했어요"); render()
        })

        // 백업
        section("백업")
        root.addView(btn("기록 CSV 공유") {
            val f = SimpleDateFormat("yyyy-MM-dd,HH:mm", Locale.KOREA)
            val csv = "date,time,slot,src\n" + Store.rides(this).sortedBy { it.ts }
                .joinToString("\n") { "${f.format(Date(it.ts))},${it.slot},${it.src}" }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, csv)
            }, "기록 내보내기"))
        })
        val eImp = edit("붙여넣기: 2026-11-16,07:18 (한 줄에 하나)", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        eImp.minLines = 3
        root.addView(btn("붙여넣은 기록 가져오기") {
            var n = 0
            eImp.text.lines().forEach { l ->
                val m = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})[,\t ]+(\d{1,2}):(\d{2})""").find(l) ?: return@forEach
                val (y, mo, d, h, mi) = m.destructured
                val cal = Calendar.getInstance().apply {
                    set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), 0); set(Calendar.MILLISECOND, 0)
                }
                if (Store.addRide(this, cal.timeInMillis, "manual") != null) n++
            }
            toast("${n}건 가져왔어요"); render()
        })
    }

    // ---------- 동작 ----------
    private fun restart() = Tracker.start(this) { msg -> runOnUiThread { toast(msg); render() } }

    private fun askPerms() {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        perms.launch(list.toTypedArray())
    }

    private fun askBackground() {
        if (!Tracker.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) return
        if (Tracker.has(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) { restart(); return }
        AlertDialog.Builder(this)
            .setTitle("위치 '항상 허용'이 필요해요")
            .setMessage("화면이 꺼져 있어도 정류장을 떠나는 순간을 알아채려면, 다음 화면에서 '항상 허용'을 골라 주세요.")
            .setPositiveButton("계속") { _, _ -> bgPerm.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
            .setNegativeButton("나중에", null).show()
    }

    @SuppressLint("MissingPermission")
    private fun setStopHere() {
        if (!Tracker.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) { askPerms(); return }
        toast("현재 위치 확인 중…")
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { loc ->
                if (loc == null) { toast("위치를 받지 못했어요. 위치를 켜고 다시 시도하세요"); return@addOnSuccessListener }
                Store.saveSettings(this, Store.settings(this).copy(stopLat = loc.latitude, stopLng = loc.longitude))
                toast("정류장 설정 (오차 약 ${loc.accuracy.toInt()}m)"); restart(); render()
            }
            .addOnFailureListener { toast("위치 오류: ${it.message}") }
    }

    private fun editRide(r: Ride) {
        val s = Store.settings(this)
        val opts = (s.slots + listOf("삭제")).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${Predictor.hm(Predictor.minOf(r.ts))} 기록 — 어느 버스였나요?")
            .setItems(opts) { _, i ->
                val list = Store.rides(this)
                if (i == opts.size - 1) list.removeAll { it.ts == r.ts }
                else list.find { it.ts == r.ts }?.slot = opts[i]
                Store.saveRides(this, list); Notifier.scheduleNext(this); render()
            }.show()
    }

    // ---------- UI 도우미 ----------
    private fun night() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun sign(d: Double) = "${if (d >= 0) "+" else ""}${Math.round(d)}분"
    private fun safeLabel(v: Double) = when (v) { 0.1 -> "10번 중 9번 탑승"; 0.25 -> "4번 중 3번 탑승"; else -> "반반" }

    private fun text(t: String, size: Float, bold: Boolean = false, color: Int? = null, top: Int = 4): TextView =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color ?: ink)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(top), 0, 0)
            if (size > 40) { includeFontPadding = false; letterSpacing = -0.02f }
            root.addView(this)
        }

    private fun rule(h: Int) = root.addView(View(this).apply {
        setBackgroundColor(ink)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h)).apply { topMargin = dp(4) }
    })

    private fun section(t: String) {
        root.addView(View(this).apply {
            setBackgroundColor(ink)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5)).apply { topMargin = dp(32) }
        })
        text(t, 20f, true, top = 8)
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; root.addView(this) }

    private fun btn(label: String, on: Boolean = false, primary: Boolean = false, f: () -> Unit) =
        Button(this).apply {
            text = label; isAllCaps = false; textSize = 15f
            if (primary || on) { setBackgroundColor(if (primary) red else ink); setTextColor(if (primary) Color.WHITE else if (night()) Color.BLACK else Color.WHITE) }
            setOnClickListener { f() }
        }

    private fun tableRow(vararg c: String, header: Boolean = false, hi: Boolean = false) {
        val r = row()
        val w = floatArrayOf(1f, 1.5f, 1.7f, 1.1f, 0.6f)
        c.forEachIndexed { i, v ->
            r.addView(TextView(this).apply {
                text = v; textSize = if (header) 12f else 15f
                setTextColor(if (header) mut else if (i == 3) red else ink)
                if (!header && (i == 0 || i == 3)) typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                else if (!header) typeface = Typeface.MONOSPACE
                gravity = if (i == 0) Gravity.START else Gravity.END
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w[i])
            })
        }
        r.setPadding(dp(4), dp(8), dp(4), dp(8))
        if (hi) r.setBackgroundColor(if (night()) Color.parseColor("#1D1D1D") else Color.parseColor("#EFEFEF"))
    }

    private fun edit(label: String, v: String, type: Int): EditText {
        text(label, 14f, true, top = 10)
        return EditText(this).apply { setText(v); inputType = type; textSize = 16f; root.addView(this) }
    }

    private fun switch(label: String, v: Boolean) = SwitchCompat(this).apply {
        text = label; textSize = 15f; isChecked = v; setPadding(0, dp(8), 0, dp(8)); root.addView(this)
    }
}
