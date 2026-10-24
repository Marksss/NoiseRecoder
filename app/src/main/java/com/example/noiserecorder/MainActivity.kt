package com.example.noiserecorder

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.ViewGroup
import android.view.View
import android.view.Gravity
import android.widget.*
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {
    companion object { @Volatile private var exporting = false }
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var level: TextView
    private lateinit var start: EditText
    private lateinit var end: EditText
    private lateinit var threshold: EditText
    private lateinit var offset: EditText
    private lateinit var records: LinearLayout
    private lateinit var scheduleSummary: TextView
    private lateinit var dailyReservation: CheckBox
    private lateinit var immediateStart: Button
    private lateinit var immediateStop: Button
    private lateinit var reportButton: Button
    private lateinit var recordSummary: TextView
    private var monitorStartPending=false
    private var monitorStartDeadline=0L
    private var monitorStopPending=false
    private val pages = mutableListOf<ScrollView>()
    private val tabs = mutableListOf<Button>()
    private var selectedTab = 0
    private val pendingScrolls = IntArray(3) { -1 }
    private val handler = Handler(Looper.getMainLooper())
    private var exportKind = "zip"
    private var pendingReport: String? = null
    private var pendingMonitorMode = MonitorMode.DAILY
    private val selectedReportSessions = linkedSetOf<Long>()
    private val expandedSessions = linkedSetOf<Long>()
    private var legacyRecordingsExpanded=false
    private var progressDialog: AlertDialog? = null
    private val ticker = object : Runnable {
        override fun run() {
            val state = MonitorService.status
            if (status.text.toString() != state) status.text = state
            val reading = when {
                MonitorService.listening -> String.format(Locale.US, "%.1f dB 估算值   /   %.1f dBFS", MonitorService.currentLevel, MonitorService.currentDbfs)
                MonitorService.running -> "声级：预约待命，尚未采集声音"
                else -> "声级：等待开启值守"
            }
            if (level.text.toString() != reading) level.text = reading
            updateMonitoringControls()
            handler.postDelayed(this, 500)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportKind = savedInstanceState?.getString("exportKind") ?: "zip"
        pendingReport = savedInstanceState?.getString("pendingReport")
        pendingMonitorMode = MonitorMode.fromValue(savedInstanceState?.getString("pendingMonitorMode"))
        monitorStartPending=savedInstanceState?.getBoolean("monitorStartPending") ?: false
        monitorStartDeadline=savedInstanceState?.getLong("monitorStartDeadline") ?: 0L
        selectedReportSessions.addAll(savedInstanceState?.getLongArray("selectedReportSessions")?.toList() ?: emptyList())
        expandedSessions.addAll(savedInstanceState?.getLongArray("expandedSessions")?.toList() ?: emptyList())
        legacyRecordingsExpanded=savedInstanceState?.getBoolean("legacyRecordingsExpanded") ?: false
        savedInstanceState?.getIntArray("pageScrolls")?.forEachIndexed { index, y -> if(index < pendingScrolls.size) pendingScrolls[index]=y }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(245, 247, 250)) }
        val tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        root.addView(TextView(this).apply { text="夜间声证"; textSize=24f; setTextColor(Color.rgb(25,42,58)); setPadding(dp(16),dp(16),dp(16),dp(4)) })
        root.addView(tabBar)
        val pageHost = FrameLayout(this)
        root.addView(pageHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        listOf("首页", "记录", "设置").forEachIndexed { index, title ->
            tabBar.addView(Button(this).apply {
                text=title; isAllCaps=false; textSize=16f
                setOnClickListener { selectTab(index) }
                tabs.add(this)
            },LinearLayout.LayoutParams(0,dp(48),1f).apply { setMargins(dp(3),0,dp(3),0) })
            val scroll=ScrollView(this).apply { isFillViewport=true }
            scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if(scroll.visibility==View.VISIBLE && scroll.height>0 && pendingScrolls[index]>=0) {
                    val y=pendingScrolls[index]; pendingScrolls[index]=-1; scroll.scrollTo(0,y)
                }
            }
            pages.add(scroll)
            pageHost.addView(scroll,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
        }
        fun page(index: Int) {
            content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(24)) }
            pages[index].addView(content)
        }
        page(0)
        label("监听首页", 23f)
        label("自动监听 · 原始录音 · 本地保存", 14f)
        status = label("", 18f); level = label("", 18f)
        scheduleSummary = label("", 16f)
        updateScheduleSummary()
        dailyReservation=CheckBox(this).apply {
            text="预约每日值守"; textSize=18f; setTextColor(Color.rgb(25,42,58)); minHeight=dp(48)
            setOnClickListener {
                if(isChecked) startMonitoring(MonitorMode.DAILY)
                else if(MonitorService.running && MonitorService.activeMode==MonitorMode.DAILY) stopMonitoring()
                updateMonitoringControls()
            }
            content.addView(this)
        }
        immediateStart=button("立即开启值守") { startMonitoring(MonitorMode.IMMEDIATE) }
        immediateStop=button("停止立即值守") { stopMonitoring() }
        updateMonitoringControls()
        button("查看录音记录") { selectTab(1) }
        button("调整监听设置") { selectTab(2) }
        label("勾选预约：每天在已保存时段内监听，取消勾选会保存录音并取消预约。\n立即值守：现在开始监听，手动停止才结束。两种方式互斥，切换前先结束当前方式。重启或强行停止后需重新开启。",14f)

        page(1)
        val recordsHeading=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        recordsHeading.addView(recordText("录音记录",23f),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        recordsHeading.addView(recordAction("刷新",outlined=false) { refreshRecords() },LinearLayout.LayoutParams(dp(64),ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(recordsHeading)
        label("勾选监听时段生成报告，展开后可回放或逐条删除录音。",13f)
        reportButton=recordAction("生成所选时段报告（0）",primary=true) { generateSelectedSessionReport() }
        reportButton.contentDescription="生成所选时段报告"
        content.addView(reportButton,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin=dp(8); bottomMargin=dp(8) })
        val reportActions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        addRecordAction(reportActions,recordAction("预览报告") { previewReport() })
        addRecordAction(reportActions,recordAction("导出报告") {
            AlertDialog.Builder(this).setTitle("选择报告格式")
                .setItems(arrayOf("Word 报告（含音频附件）","独立 HTML 报告（含音频）","完整报告包（Word + HTML + 音频）")) { _, index -> chooseReportExport(listOf("docx","html","reportzip")[index]) }
                .setNegativeButton("取消",null).show()
        })
        addRecordAction(reportActions,recordAction("导出证据包") { chooseExport("zip") }.apply { contentDescription="导出证据包（原始 WAV + CSV + SHA-256）" })
        content.addView(reportActions)
        content.addView(recordAction("删除本地所有已生成报告",destructive=true,outlined=false) { deleteReports() },LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity=Gravity.END; topMargin=dp(4) })
        recordSummary=recordText("",13f,Color.rgb(100,116,139)).apply { setPadding(0,dp(8),0,0) }
        content.addView(recordSummary)
        records=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        content.addView(records)

        page(2)
        label("监听设置", 23f)
        val s = MonitorSettings.load(this)
        start = field("监听开始（HH:mm）", savedInstanceState?.getString("draftStart") ?: clock(s.start))
        end = field("监听结束（HH:mm，支持跨午夜）", savedInstanceState?.getString("draftEnd") ?: clock(s.end))
        threshold = field("触发阈值（估算 dB SPL）", savedInstanceState?.getString("draftThreshold") ?: s.threshold.toString(), true)
        offset = field("校准偏移（dB SPL = dBFS + 偏移）", savedInstanceState?.getString("draftOffset") ?: s.offset.toString(), true)
        button("保存设置") { saveSettings() }
        button("用参考声级校准") { calibrate() }
        label("修改后请保存，再从首页开启值守。默认 02:30–06:00、阈值 45 dB。默认偏移 90 仅为占位值，未经校准不代表真实声压级；采用 100 ms RMS，无 A 计权，不能代替专业声级计。设置在下次开启值守时生效。", 13f)
        label("后台运行",19f)
        button("打开电池优化设置") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        label("立即值守持续采集；每日预约在时段外释放麦克风，保留常驻通知等待下一次。预约时段结束会收尾录音。建议连接电源，并在系统电池设置中允许后台运行。",13f)
        selectTab(savedInstanceState?.getInt("selectedTab")?.coerceIn(0,2) ?: 0)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun selectTab(index: Int) {
        selectedTab=index
        pages.forEachIndexed { page, scroll -> scroll.visibility=if(page==index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { tab, button ->
            val selected=tab==index
            button.isSelected=selected
            button.setTextColor(if(selected) Color.WHITE else Color.rgb(25,42,58))
            button.background=GradientDrawable().apply { cornerRadius=dp(10).toFloat(); setColor(if(selected) Color.rgb(28,100,160) else Color.rgb(228,234,240)) }
            button.contentDescription="${button.text}，${if(selected) "当前页" else "切换页面"}"
        }
        currentFocus?.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken,0)
        if(index==1 && ::records.isInitialized) refreshRecords()
    }
    private fun updateScheduleSummary() {
        val saved=MonitorSettings.load(this)
        scheduleSummary.text="每日预约时段：${clock(saved.start)}—${clock(saved.end)}\n触发阈值：${ReportFormat.number(saved.threshold)} dB（需校准）\n立即值守不受预约时段限制"
    }
    private fun label(text: String, size: Float): TextView = TextView(this).apply { this.text = text; textSize = size; setTextColor(Color.rgb(25, 42, 58)); setPadding(0, 10, 0, 10); content.addView(this) }
    private fun field(title: String, value: String, number: Boolean = false): EditText {
        label(title, 14f)
        return EditText(this).apply { setText(value); setSingleLine(); if (number) inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED; content.addView(this) }
    }
    private fun button(title: String, action: () -> Unit) = Button(this).apply { text = title; isAllCaps = false; setOnClickListener { action() }; content.addView(this) }
    private fun recordText(value: String, size: Float, color: Int=Color.rgb(30,41,59)) = TextView(this).apply {
        text=value; textSize=size; setTextColor(color)
    }
    private fun recordAction(title: String, primary: Boolean=false, destructive: Boolean=false, outlined: Boolean=true, action: () -> Unit) = Button(this).apply {
        text=title; isAllCaps=false; textSize=14f; gravity=Gravity.CENTER
        minHeight=dp(48); minimumHeight=dp(48); minWidth=0; minimumWidth=0
        setPadding(dp(8),dp(6),dp(8),dp(6)); elevation=0f; stateListAnimator=null
        setTextColor(if(primary) Color.WHITE else if(destructive) Color.rgb(168,52,52) else Color.rgb(28,100,160))
        val shape=GradientDrawable().apply {
            cornerRadius=dp(8).toFloat()
            setColor(if(primary) Color.rgb(28,100,160) else if(outlined) Color.WHITE else Color.TRANSPARENT)
            if(outlined && !primary) setStroke(dp(1),Color.rgb(220,227,235))
        }
        background=RippleDrawable(ColorStateList.valueOf(Color.argb(35,100,135,170)),shape,null)
        setOnClickListener { action() }
    }
    private fun addRecordAction(row: LinearLayout, action: View) {
        row.addView(action,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply {
            if(row.childCount>0) marginStart=dp(8)
        })
    }
    private fun recordStatus(value: String) = when(value) {
        "recording" -> "录制中"
        "monitoring" -> "监听中"
        "complete" -> "已完成"
        "user_stop" -> "已停止"
        "window_end" -> "预约结束"
        "interrupted" -> "意外中断"
        "error" -> "异常结束"
        "missing" -> "文件缺失"
        "segment" -> "连续分段"
        else -> "已结束"
    }
    private fun updateMonitoringControls() {
        if(!::dailyReservation.isInitialized || !::immediateStart.isInitialized) return
        if(MonitorService.running || SystemClock.elapsedRealtime()>=monitorStartDeadline) monitorStartPending=false
        if(!MonitorService.running) monitorStopPending=false
        val running=MonitorService.running
        val daily=running && MonitorService.activeMode==MonitorMode.DAILY
        dailyReservation.isChecked=!monitorStopPending && (daily || (monitorStartPending && pendingMonitorMode==MonitorMode.DAILY))
        dailyReservation.isEnabled=!monitorStartPending && !monitorStopPending && (!running || daily)
        immediateStart.isEnabled=!running && !monitorStartPending && !monitorStopPending
        immediateStop.isEnabled=running && !daily && !monitorStopPending
    }
    private fun stopMonitoring() {
        monitorStopPending=true
        stopService(Intent(this,MonitorService::class.java))
        updateMonitoringControls()
    }
    private fun clock(min: Int) = String.format(Locale.US, "%02d:%02d", min / 60, min % 60)
    private fun parseTime(s: String): Int {
        require(Regex("\\d{2}:\\d{2}").matches(s)) { "时间请使用 HH:mm 格式" }
        val parts = s.split(':').map { it.toInt() }; require(parts[0] in 0..23 && parts[1] in 0..59) { "时间超出范围" }
        return parts[0] * 60 + parts[1]
    }
    private fun saveSettings(): Boolean = try {
        val s = MonitorSettings(parseTime(start.text.toString()), parseTime(end.text.toString()), threshold.text.toString().toDouble(), offset.text.toString().toDouble())
        require(s.start != s.end) { "开始和结束时间不能相同" }
        require(s.threshold.isFinite() && s.threshold in 0.0..140.0) { "阈值范围为 0–140 dB" }
        require(s.offset.isFinite() && s.offset in -20.0..160.0) { "偏移范围为 -20–160 dB" }
        s.save(this); updateScheduleSummary(); toast("设置已保存，下次开启值守生效"); true
    } catch (e: Exception) { toast(e.message ?: "设置无效"); false }
    private fun startMonitoring(mode: MonitorMode) {
        if (exporting) { toast("请等待导出完成"); return }
        if (MonitorService.running) { toast("请先停止当前值守或取消预约，再切换方式"); return }
        pendingMonitorMode=mode
        updateScheduleSummary()
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (permissions.isNotEmpty()) {
            monitorStartPending=true; monitorStartDeadline=Long.MAX_VALUE
            updateMonitoringControls()
            requestPermissions(permissions.toTypedArray(), 10); return
        }
        launchMonitor()
    }
    private fun launchMonitor() {
        try {
            monitorStartPending=true; monitorStartDeadline=SystemClock.elapsedRealtime()+10000
            startForegroundService(Intent(this, MonitorService::class.java).putExtra(MonitorService.EXTRA_MODE,pendingMonitorMode.name))
        } catch (e: Exception) { monitorStartPending=false; toast("无法启动：${e.message}") }
        updateMonitoringControls()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launchMonitor()
            else { monitorStartPending=false; updateMonitoringControls(); toast("需要麦克风权限，请在系统应用设置中授权") }
        }
    }
    private fun calibrate() {
        if (!MonitorService.running || MonitorService.currentDbfs < -100) { toast("请先开启值守，并播放稳定参考声源"); return }
        val measured = MonitorService.currentDbfs
        val input = EditText(this).apply { hint = "参考声级计读数，例如 60.0"; inputType = 8194 }
        AlertDialog.Builder(this).setTitle("单点校准").setMessage("保持声源稳定，手机与声级计相邻。采样值 %.1f dBFS。输入参考读数后保存；停止并重新开启值守使其生效。".format(measured)).setView(input)
            .setPositiveButton("计算偏移") { _, _ ->
                val reference = input.text.toString().toDoubleOrNull()
                if (reference != null && reference.isFinite() && reference in 0.0..140.0) offset.setText(String.format(Locale.US, "%.2f", reference - measured)) else toast("参考读数无效")
            }.setNegativeButton("取消", null).show()
    }
    private fun refreshRecords() {
        records.removeAllViews()
        val (list,sessions) = EvidenceStore(this).use { store -> if (!MonitorService.running) store.recover(); store.list() to store.sessions() }
        recordSummary.text="${sessions.size} 个监听时段 · ${list.size} 段录音"
        selectedReportSessions.retainAll(sessions.map { it.id }.toSet())
        updateReportButton()
        expandedSessions.retainAll(sessions.map { it.id }.toSet())
        if (list.isEmpty() && sessions.isEmpty()) records.addView(TextView(this).apply { text = "暂无监听时段或录音。" })
        fun group(title: String, rows: List<Evidence>, session: MonitorSession?) {
            val box=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(12),dp(12),dp(8))
                background=GradientDrawable().apply { cornerRadius=dp(10).toFloat(); setColor(Color.WHITE); setStroke(dp(1),Color.rgb(227,232,238)) }
            }
            records.addView(box,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin=dp(12) })
            box.addView(recordText(title,17f))
            if(session!=null) box.addView(recordText("${rows.size} 段录音 · 阈值 ${ReportFormat.number(session.threshold)} dB · ${recordStatus(session.status)}",13f,Color.rgb(100,116,139)).apply { setPadding(0,dp(6),0,dp(4)) })
            val controls=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
            if(session!=null) addRecordAction(controls,CheckBox(this).apply {
                text="选入报告"; textSize=14f; setTextColor(Color.rgb(51,65,85)); minHeight=dp(48)
                contentDescription="选择报告时段 ${session.id}"
                isChecked=session.id in selectedReportSessions
                setOnClickListener {
                    if(isChecked) {
                        if(selectedReportSessions.size>=31) { isChecked=false; toast("一次最多选择 31 个监听时段") }
                        else selectedReportSessions.add(session.id)
                    } else selectedReportSessions.remove(session.id)
                    updateReportButton()
                }
            })
            val audioList=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
            val expand=recordAction("",outlined=false) { }
            fun updateExpansion() {
                val opened=if(session==null) legacyRecordingsExpanded else session.id in expandedSessions
                audioList.visibility=if(opened) View.VISIBLE else View.GONE
                expand.text=if(opened) "收起录音" else "展开 ${rows.size} 段"
                expand.contentDescription=if(opened) "收起录音（${rows.size} 段）" else "展开录音（${rows.size} 段）"
            }
            if(rows.isNotEmpty()) {
                expand.setOnClickListener {
                    if(session==null) legacyRecordingsExpanded=!legacyRecordingsExpanded
                    else if(!expandedSessions.add(session.id)) expandedSessions.remove(session.id)
                    updateExpansion()
                }
                addRecordAction(controls,expand)
            }
            if(session!=null) addRecordAction(controls,recordAction("删除时段",destructive=true,outlined=false) { confirmDeleteSession(session,rows.size) }.apply { contentDescription="删除整个监听时段 ${session.id}" })
            box.addView(controls)
            box.addView(audioList)
            if(rows.isEmpty()) box.addView(recordText("无录音，声级记录仍可用于报告",13f,Color.rgb(100,116,139)).apply { setPadding(0,dp(4),0,dp(4)) })
            rows.forEach { addRecordingRow(audioList,it) }
            updateExpansion()
        }
        sessions.forEach { session -> group(sessionTitle(session),list.filter { it.sessionId==session.id },session) }
        val ids=sessions.map { it.id }.toSet()
        val legacy=list.filter { it.sessionId==null || it.sessionId !in ids }
        if(legacy.isNotEmpty()) group("未归属监听时段的录音",legacy,null)
    }
    private fun sessionTitle(session: MonitorSession): String {
        val zone=java.time.ZoneId.systemDefault()
        val start=java.time.Instant.ofEpochMilli(session.start).atZone(zone)
        val end=java.time.Instant.ofEpochMilli(session.end).atZone(zone)
        val date=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val time=java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        return if(start.toLocalDate()==end.toLocalDate()) "${start.format(date)}\n${start.format(time)} — ${end.format(time)}"
            else "${start.format(date)} ${start.format(time)}\n至 ${end.format(date)} ${end.format(time)}"
    }
    private fun addRecordingRow(parent: LinearLayout, e: Evidence) {
        parent.addView(View(this).apply { setBackgroundColor(Color.rgb(232,237,242)) },LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(1)))
        val row=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,dp(12),0,dp(10)) }
        parent.addView(row)
        val time=runCatching { java.time.Instant.parse(e.started).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) }.getOrDefault(e.started)
        row.addView(recordText("$time · ${String.format(Locale.US,"%.1f",e.duration)} 秒",16f))
        row.addView(recordText("峰值 ${ReportFormat.number(e.peak)} dB · ${recordStatus(e.status)}",13f,Color.rgb(100,116,139)).apply { setPadding(0,dp(4),0,dp(4)) })
        row.addView(recordText(e.name,12f,Color.rgb(100,116,139)).apply { setTextIsSelectable(true); maxLines=2 })
        val actions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(0,dp(8),0,0) }
        addRecordAction(actions,recordAction("播放") { playRecording(e) }.apply { contentDescription="播放录音 ${e.name}" })
        addRecordAction(actions,recordAction("删除",destructive=true,outlined=false) { confirmDelete(listOf(e.name)) }.apply { contentDescription="删除录音 ${e.name}" })
        row.addView(actions)
    }
    private fun playRecording(e: Evidence) {
        if(e.status=="recording") { toast("正在录音，停止值守后可回放"); return }
        val uri=Uri.parse("content://$packageName.evidence/${e.name}")
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"audio/wav").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch(_: ActivityNotFoundException) { toast("请安装支持 WAV 的播放器，或导出后回放") }
    }
    private fun confirmDeleteSession(session: MonitorSession, count: Int) {
        if(MonitorService.running || exporting) { toast("请先停止立即值守或取消预约，并等待其他任务完成"); return }
        AlertDialog.Builder(this).setTitle("永久删除整个监听时段？")
            .setMessage("${sessionTitle(session)}\n\n将删除此时段的全部录音（当前 $count 段可用）、录音索引、触发日志、声级曲线和超限统计。此操作不可撤销。已生成或导出的报告、音频副本需另外删除。")
            .setNegativeButton("取消",null).setPositiveButton("删除整个时段") { _, _ ->
                if(MonitorService.running || exporting) { toast("当前任务未结束，请稍后再试"); return@setPositiveButton }
                exporting=true
                Thread {
                    var message="整个监听时段已删除"
                    try { EvidenceStore(this).use { it.deleteSession(session.id) } }
                    catch(e: Exception) { message="时段删除未完成：${e.message}；请刷新后检查，剩余记录保留" }
                    finally { exporting=false; runOnUiThread { expandedSessions.remove(session.id); refreshRecords(); toast(message) } }
                }.start()
            }.show()
    }
    private fun confirmDelete(names: List<String>) {
        if(MonitorService.running || exporting) { toast("请先停止值守并等待导出或报告生成完成"); return }
        if(names.isEmpty()) { toast("请先选择录音"); return }
        AlertDialog.Builder(this).setTitle("永久删除 ${names.size} 段录音？")
            .setMessage("删除原始音频，保留监听声级、超限统计及删除标记。此操作不可撤销。已生成或导出的报告、音频副本不会被删除。")
            .setNegativeButton("取消",null).setPositiveButton("永久删除") { _, _ ->
                if(MonitorService.running || exporting) { toast("当前任务未结束，请稍后再试"); return@setPositiveButton }
                exporting=true
                Thread {
                    var removed=0; val failures=mutableListOf<String>()
                    try { EvidenceStore(this).use { store -> names.forEach { name -> try { store.deleteRecording(name); removed++ } catch(e:Exception) { failures.add(e.message ?: name) } } } }
                    finally { exporting=false; runOnUiThread { refreshRecords(); toast("已删除 $removed 段" + if(failures.isEmpty()) "" else "；失败：${failures.first()}") } }
                }.start()
            }.show()
    }
    private fun reportFolder(name: String? = null): File? {
        val base=File(filesDir,"reports").canonicalFile
        val value=name ?: getSharedPreferences("reports",MODE_PRIVATE).getString("latest",null) ?: return null
        val file=File(base,value).canonicalFile
        return file.takeIf { it.parentFile==base && File(it,"READY").isFile }
    }
    private fun updateReportButton() {
        if(::reportButton.isInitialized) reportButton.text="生成所选时段报告（${selectedReportSessions.size}）"
    }
    private fun generateSelectedSessionReport() {
        if(MonitorService.running || exporting) { toast("请先停止值守并等待其他任务完成"); return }
        val sessions=EvidenceStore(this).use { it.recover(); it.sessions() }
        selectedReportSessions.retainAll(sessions.map { it.id }.toSet())
        updateReportButton()
        val chosen=sessions.filter { it.id in selectedReportSessions }
        if(chosen.isEmpty()) { toast("请先勾选需要生成报告的监听时段"); return }
        val ids=chosen.map { it.id }.toSet()
        AlertDialog.Builder(this).setTitle("生成 ${chosen.size} 个监听时段的报告？")
            .setMessage(chosen.joinToString("\n\n") { sessionTitle(it) }+"\n\n将包含这些时段的全部声级、超限统计和可用录音，无需选择单个录音。")
            .setNegativeButton("取消",null).setPositiveButton("生成 Word 和 HTML") { _, _ ->
                generateReport(ids,false)
            }.show()
    }
    private fun generateReport(ids: Set<Long>, legacy: Boolean) {
        if(MonitorService.running || exporting) { toast("请先结束当前任务"); return }
        exporting=true
        val progressLabel=TextView(this).apply { text="正在读取声级记录…"; setPadding(36,36,36,36) }
        val dialog=AlertDialog.Builder(this).setTitle("生成监测报告").setView(progressLabel).setCancelable(false).create()
        progressDialog=dialog; dialog.show()
        Thread {
            val root=File(filesDir,"reports/report_${java.util.UUID.randomUUID()}")
            try {
                EvidenceStore(this).use { store ->
                    val report=store.report(ids,legacy)
                    ReportBuilder(report,store.directory).generate(root) { message -> runOnUiThread { if(!isFinishing && !isDestroyed) progressLabel.text=message } }
                }
                check(getSharedPreferences("reports",MODE_PRIVATE).edit().putString("latest",root.name).commit())
                runOnUiThread { toast("报告已生成，可在浏览器查看或导出 Word / HTML") }
            } catch(e:Exception) { root.deleteRecursively(); runOnUiThread { toast("报告生成失败：${e.message}") } }
            finally { exporting=false; runOnUiThread { if(!isDestroyed) dialog.dismiss() } }
        }.start()
    }
    private fun previewReport() {
        val root=reportFolder() ?: run { toast("请先生成报告"); return }
        if(exporting) { toast("请等待当前任务完成"); return }
        try {
            ReportPreviewService.url=null; ReportPreviewService.error=null
            startForegroundService(Intent(this,ReportPreviewService::class.java).putExtra("report",root.name))
            val deadline=SystemClock.elapsedRealtime()+5000
            val open=object:Runnable { override fun run() {
                val url=ReportPreviewService.url
                when {
                    url!=null -> try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) } catch(_:ActivityNotFoundException) { toast("请安装浏览器后查看报告") }
                    ReportPreviewService.error!=null -> toast("预览失败：${ReportPreviewService.error}")
                    SystemClock.elapsedRealtime()<deadline -> handler.postDelayed(this,100)
                    else -> toast("浏览器预览启动超时，请重试")
                }
            } }
            handler.postDelayed(open,100)
        } catch(e:Exception) { toast("无法打开预览：${e.message}") }
    }
    private fun chooseReportExport(kind: String) {
        val root=reportFolder() ?: run { toast("请先生成报告"); return }
        if(MonitorService.running || exporting) { toast("请先停止值守并等待其他任务完成"); return }
        exportKind=kind; pendingReport=root.name
        val extension=if(kind=="reportzip") "zip" else kind
        val mime=when(kind) { "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; "html" -> "text/html"; else -> "application/zip" }
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE,"noise_report_${System.currentTimeMillis()}.$extension"),21)
    }
    private fun deleteReports() {
        if(exporting) { toast("请等待当前任务完成"); return }
        AlertDialog.Builder(this).setTitle("删除本地所有已生成报告？").setMessage("将永久删除本应用内部保存的全部 Word、HTML、图表及报告音频副本，并关闭浏览器预览。\n\n原始录音、监听时段、声级和超限统计保留。已导出到下载目录、电脑等其他位置的文件需自行删除。此操作不可撤销。").setNegativeButton("取消",null).setPositiveButton("删除全部报告") { _, _ ->
            if(exporting) return@setPositiveButton
            stopService(Intent(this,ReportPreviewService::class.java))
            exporting=true
            Thread {
                try {
                    val base=File(filesDir,"reports").canonicalFile
                    check(!base.exists() || base.deleteRecursively()) { "部分报告未能删除" }
                    getSharedPreferences("reports",MODE_PRIVATE).edit().remove("latest").commit()
                    runOnUiThread { pendingReport=null; toast("本地所有已生成报告已删除，原始记录保留") }
                } catch(e:Exception) { runOnUiThread { toast("删除失败：${e.message}") } }
                finally { exporting=false }
            }.start()
        }.show()
    }
    private fun chooseExport(kind: String) {
        if (MonitorService.running) { toast("请先停止值守，确保原始录音完成保存"); return }
        if (exporting) return
        exportKind = kind
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = if (kind == "zip") "application/zip" else "text/csv"; putExtra(Intent.EXTRA_TITLE, "noise_evidence_${System.currentTimeMillis()}.$kind") }, 20)
    }
    @Deprecated("Platform result API used to keep the app dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in setOf(20,21) || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (MonitorService.running) { toast("值守已开启，请停止后重新导出"); return }
        exporting = true
        val kind = exportKind
        Thread {
            try {
                if(requestCode==21) {
                    val root=reportFolder(pendingReport) ?: error("报告不存在，请重新生成")
                    val output=requireNotNull(contentResolver.openOutputStream(uri,"wt"))
                    output.use { stream -> when(kind) { "docx" -> File(root,"report.docx").inputStream().use { it.copyTo(stream) }; "html" -> ReportBuilder.exportHtml(root,stream); "reportzip" -> ReportBuilder.exportZip(root,stream); else -> error("未知报告格式") } }
                    runOnUiThread { toast("报告导出完成，可复制到电脑查看") }
                    return@Thread
                }
                EvidenceStore(this).use { store ->
                    store.recover()
                    val stream = requireNotNull(contentResolver.openOutputStream(uri, "wt"))
                    if (kind == "csv") stream.bufferedWriter(Charsets.UTF_8).use { it.write(store.csv("triggers")) }
                    else ZipOutputStream(stream.buffered()).use { zip ->
                        fun entry(name: String, value: String) { zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
                        listOf("recordings","triggers","sessions","levels","exceed_events").forEach { entry("$it.csv",store.csv(it)) }
                        val hashes = StringBuilder()
                        store.list().forEach { evidence ->
                            val f = File(store.directory, evidence.name)
                            if (f.exists()) {
                                zip.putNextEntry(ZipEntry("audio/${f.name}"))
                                val digest = MessageDigest.getInstance("SHA-256")
                                f.inputStream().use { input -> val buffer = ByteArray(65536); var n = input.read(buffer); while (n != -1) { zip.write(buffer, 0, n); digest.update(buffer, 0, n); n = input.read(buffer) } }
                                zip.closeEntry(); hashes.append(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }).append("  audio/${f.name}\n")
                            }
                        }
                        entry("SHA256SUMS.txt", hashes.toString())
                        entry("README.txt", "Original PCM WAV: 16000 Hz, mono, signed 16-bit little-endian. CSV timestamps: UTC ISO-8601; filenames: local time. Estimated SPL = dBFS + calibration offset, unweighted RMS. triggers.csv: at most one sample per second during sustained exceedance; recording tail extends on every exceeding frame. SHA-256 verifies exported file bytes, not legal authenticity. Missing recordings remain listed with status=missing.\n")
                    }
                }
                runOnUiThread { toast("导出完成") }
            } catch (e: Exception) { runOnUiThread { toast("导出失败，目标文件可能不完整：${e.message}") } }
            finally { exporting = false }
        }.start()
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exportKind",exportKind); outState.putString("pendingReport",pendingReport)
        outState.putString("pendingMonitorMode",pendingMonitorMode.name)
        outState.putBoolean("monitorStartPending",monitorStartPending); outState.putLong("monitorStartDeadline",monitorStartDeadline)
        outState.putLongArray("selectedReportSessions",selectedReportSessions.toLongArray())
        outState.putLongArray("expandedSessions",expandedSessions.toLongArray()); outState.putBoolean("legacyRecordingsExpanded",legacyRecordingsExpanded)
        outState.putInt("selectedTab",selectedTab); outState.putIntArray("pageScrolls",pages.mapIndexed { index, scroll -> if(pendingScrolls[index]>=0) pendingScrolls[index] else scroll.scrollY }.toIntArray())
        outState.putString("draftStart",start.text.toString()); outState.putString("draftEnd",end.text.toString())
        outState.putString("draftThreshold",threshold.text.toString()); outState.putString("draftOffset",offset.text.toString())
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); handler.post(ticker); if(selectedTab==1) refreshRecords() }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
    override fun onDestroy() { progressDialog?.dismiss(); handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
