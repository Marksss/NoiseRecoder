package com.example.noiserecorder

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.ViewGroup
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
    private val handler = Handler(Looper.getMainLooper())
    private var exportKind = "zip"
    private val ticker = object : Runnable {
        override fun run() {
            val state = MonitorService.status
            if (status.text.toString() != state) status.text = state
            val reading = if (MonitorService.running) String.format(Locale.US, "%.1f dB 估算值   /   %.1f dBFS", MonitorService.currentLevel, MonitorService.currentDbfs) else "声级：等待开启值守"
            if (level.text.toString() != reading) level.text = reading
            handler.postDelayed(this, 500)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportKind = savedInstanceState?.getString("exportKind") ?: "zip"
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 40, 36, 40); setBackgroundColor(Color.rgb(245, 247, 250)) }
        scroll.addView(content); setContentView(scroll)
        label("夜间声证", 28f)
        label("自动监听 · 原始录音 · 本地保存", 14f)
        status = label("", 18f); level = label("", 18f)
        val s = MonitorSettings.load(this)
        start = field("监听开始（HH:mm）", clock(s.start))
        end = field("监听结束（HH:mm，支持跨午夜）", clock(s.end))
        threshold = field("触发阈值（估算 dB SPL）", s.threshold.toString(), true)
        offset = field("校准偏移（dB SPL = dBFS + 偏移）", s.offset.toString(), true)
        button("保存设置") { saveSettings() }
        button("用参考声级校准") { calibrate() }
        label("默认 02:30–06:00、阈值 45 dB。默认偏移 90 仅为占位值，未经校准不代表真实声压级；采用 100 ms RMS，无 A 计权，不能代替专业声级计。设置在下次开启值守时生效。", 13f)
        button("开启每日值守") { startMonitoring() }
        button("停止值守并保存录音") { stopService(Intent(this, MonitorService::class.java)) }
        button("打开电池优化设置") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        label("请先在此界面开启值守，再锁屏或充电。开启期间持续占用麦克风，时段外丢弃声音；通知中可随时停止。录音在监听时段结束时收尾。重启、强行停止或系统终止后需要重新开启。建议连接电源，并在系统电池设置中允许后台运行。", 13f)
        button("导出证据包（原始 WAV + CSV + SHA-256）") { chooseExport("zip") }
        button("仅导出声级记录 CSV") { chooseExport("csv") }
        button("刷新录音列表") { refreshRecords() }
        records = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(records)
    }
    private fun label(text: String, size: Float): TextView = TextView(this).apply { this.text = text; textSize = size; setTextColor(Color.rgb(25, 42, 58)); setPadding(0, 10, 0, 10); content.addView(this) }
    private fun field(title: String, value: String, number: Boolean = false): EditText {
        label(title, 14f)
        return EditText(this).apply { setText(value); setSingleLine(); if (number) inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED; content.addView(this) }
    }
    private fun button(title: String, action: () -> Unit) { content.addView(Button(this).apply { text = title; isAllCaps = false; setOnClickListener { action() } }) }
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
        s.save(this); toast("设置已保存，下次开启值守生效"); true
    } catch (e: Exception) { toast(e.message ?: "设置无效"); false }
    private fun startMonitoring() {
        if (exporting) { toast("请等待导出完成"); return }
        if (MonitorService.running) { toast("已经在值守中；修改设置后请停止并重新开启"); return }
        if (!saveSettings()) return
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (permissions.isNotEmpty()) { requestPermissions(permissions.toTypedArray(), 10); return }
        launchMonitor()
    }
    private fun launchMonitor() { try { startForegroundService(Intent(this, MonitorService::class.java)) } catch (e: Exception) { toast("无法启动：${e.message}") } }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launchMonitor()
            else toast("需要麦克风权限，请在系统应用设置中授权")
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
        val list = EvidenceStore(this).use { store -> if (!MonitorService.running) store.recover(); store.list() }
        if (list.isEmpty()) records.addView(TextView(this).apply { text = "暂无录音，触发后将在这里显示。" })
        list.forEach { e ->
            records.addView(Button(this).apply {
                text = "${e.name}\n${e.started}\n峰值 %.1f dB · %.1f 秒 · ${e.status}".format(e.peak, e.duration)
                isAllCaps = false
                setOnClickListener {
                    if (e.status == "recording") { toast("正在录音，停止值守后可回放"); return@setOnClickListener }
                    val uri = Uri.parse("content://$packageName.evidence/${e.name}")
                    try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/wav").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    catch (_: ActivityNotFoundException) { toast("请安装支持 WAV 的播放器，或导出后回放") }
                }
            })
        }
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
        if (requestCode != 20 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (MonitorService.running) { toast("值守已开启，请停止后重新导出"); return }
        exporting = true
        val kind = exportKind
        Thread {
            try {
                EvidenceStore(this).use { store ->
                    store.recover()
                    val stream = requireNotNull(contentResolver.openOutputStream(uri, "wt"))
                    if (kind == "csv") stream.bufferedWriter(Charsets.UTF_8).use { it.write(store.csv("triggers")) }
                    else ZipOutputStream(stream.buffered()).use { zip ->
                        fun entry(name: String, value: String) { zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
                        entry("recordings.csv", store.csv("recordings")); entry("triggers.csv", store.csv("triggers"))
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
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("exportKind", exportKind); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); handler.post(ticker); refreshRecords() }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
}
