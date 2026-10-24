package com.example.noiserecorder

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import java.time.LocalTime
import java.util.zip.ZipFile

/** Dedicated-device integration test. Uses real microphone, restores settings and stops service. */
class SmokeInstrumentation : Instrumentation() {
    private val report = StringBuilder()
    private var activity: Activity? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun waitFor(timeout: Long = 5000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) { check(SystemClock.elapsedRealtime() < end) { "Timeout: ${MonitorService.status}" }; SystemClock.sleep(100) }
    }
    private fun note(message: String) {
        report.append(message).append('\n')
        sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    }
    private fun stopMonitor() {
        targetContext.stopService(Intent(targetContext, MonitorService::class.java))
        waitFor { !MonitorService.running }
    }
    private fun launch(settings: MonitorSettings) {
        stopMonitor()
        activity?.let { runOnMainSync { it.finish() } }
        settings.save(targetContext)
        activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        runOnMainSync { targetContext.startForegroundService(Intent(targetContext, MonitorService::class.java)) }
        val inside=LocalTime.now().let { AudioLogic.inWindow(it.hour*60+it.minute,settings.start,settings.end) }
        waitFor { MonitorService.running && (if(inside) MonitorService.listening && MonitorService.currentDbfs > -120.0 else !MonitorService.listening) }
        SystemClock.sleep(1000)
    }
    override fun onStart() {
        val original = MonitorSettings.load(targetContext)
        var success = false
        val store = EvidenceStore(targetContext)
        val initial = store.list().map { it.name }.toSet()
        try {
            val now = LocalTime.now().let { it.hour * 60 + it.minute }
            launch(MonitorSettings((now + 10) % 1440, (now + 11) % 1440, 0.0, 160.0))
            SystemClock.sleep(3000)
            check(!MonitorService.listening) { "Microphone active outside schedule" }
            check(store.list().none { it.name !in initial }) { "Outside-window recording created" }
            note("PASS: outside schedule does not save audio")
            val begin = (now + 1439) % 1440
            val end = (now + 10) % 1440
            launch(MonitorSettings(begin, end, 140.0, -20.0))
            SystemClock.sleep(6000)
            check(store.list().none { it.name !in initial }) { "Sub-threshold recording created" }
            check(MonitorService.currentDbfs > -110.0) { "Microphone appears silent" }
            note("PASS: real microphone active, sub-threshold audio does not create files")
            // A large test-only offset guarantees exceedance, independent of acoustic conditions.
            launch(MonitorSettings(begin, end, 0.0, 160.0))
            waitFor { store.list().any { it.name !in initial } }
            uiAutomation.executeShellCommand("input keyevent 223").close()
            val deadline = SystemClock.elapsedRealtime() + 70000
            while (SystemClock.elapsedRealtime() < deadline) {
                check(MonitorService.running) { "Service stopped under lock screen: ${MonitorService.status}" }
                SystemClock.sleep(1000)
            }
            val active = store.list().single { it.name !in initial }
            check(active.status == "recording" && active.duration > 65.0) { "Continuous threshold did not extend beyond 60 seconds" }
            check(runCatching { store.deleteRecording(active.name) }.isFailure) { "Active recording was deletable" }
            note("PASS: lock-screen foreground recording and continuous extension beyond 65 seconds")
            stopMonitor()
            val result = store.list().single { it.name !in initial }
            check(result.status == "user_stop" && result.duration > 65.0)
            val file = File(store.directory, result.name)
            val header = ByteArray(44)
            java.io.DataInputStream(file.inputStream()).use { it.readFully(header) }
            check(String(header.copyOfRange(0, 4)) == "RIFF")
            val length = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(40).toLong()
            check(length + 44 == file.length())
            check(store.csv("triggers").contains(result.name))
            note("PASS: finalized WAV header, recording peak/index and trigger CSV")
            uiAutomation.executeShellCommand("input keyevent 224").close()
            uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
            val zipFile = File(targetContext.cacheDir, "smoke-export-${java.util.UUID.randomUUID()}.zip")
            val callback = MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            runOnMainSync { callback.invoke(activity, 20, Activity.RESULT_OK, Intent().setData(Uri.fromFile(zipFile))) }
            waitFor(15000) { try { ZipFile(zipFile).use { it.getEntry("SHA256SUMS.txt") != null } } catch (_: Exception) { false } }
            ZipFile(zipFile).use { zip ->
                check(zip.getEntry("recordings.csv") != null && zip.getEntry("triggers.csv") != null)
                val hashText = zip.getInputStream(zip.getEntry("SHA256SUMS.txt")).bufferedReader().readText()
                val digest = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(zip.getEntry("audio/${file.name}")).use { input -> val bytes = ByteArray(8192); var n = input.read(bytes); while (n >= 0) { digest.update(bytes, 0, n); n = input.read(bytes) } }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                check(hashText.contains("$hash  audio/${file.name}"))
                check(file.exists())
            }
            note("PASS: actual ZIP exporter preserves original WAV and SHA-256 matches exported bytes")
            val uri = Uri.parse("content://${targetContext.packageName}.evidence/${file.name}")
            targetContext.contentResolver.openFileDescriptor(uri, "r").use { check(it != null) }
            var writeRejected = false
            try { targetContext.contentResolver.openFileDescriptor(uri, "rw")?.close() } catch (_: Exception) { writeRejected = true }
            check(writeRejected)
            note("PASS: evidence provider allows reading and rejects writing")
            reportAndDeletion(store,result,initial)
            success = true
        } catch (e: Throwable) { note("FAIL: ${e.stackTraceToString()}") }
        finally {
            try { stopMonitor() } catch (_: Exception) { }
            targetContext.stopService(Intent(targetContext,ReportPreviewService::class.java))
            original.save(targetContext)
            // Synthetic arithmetic fixtures must never appear as real monitored periods.
            store.sessions().filter { it.status=="test_fixture" }.forEach { fixture ->
                val key=arrayOf(fixture.id.toString())
                val recordings=store.readableDatabase.rawQuery("SELECT COUNT(*) FROM recordings WHERE session_id=?",key).use { c -> c.moveToFirst(); c.getLong(0) }
                if(recordings==0L) {
                    val db=store.writableDatabase
                    db.beginTransaction()
                    try { db.delete("levels","session_id=?",key); db.delete("exceed_events","session_id=?",key); db.delete("sessions","id=?",key); db.setTransactionSuccessful() }
                    finally { db.endTransaction() }
                }
            }
            store.close()
            File(targetContext.filesDir, "smoke-report.txt").writeText(report.toString())
            finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", report.toString()); putBoolean("success", success) })
        }
    }
    private fun reportAndDeletion(store: EvidenceStore, recording: Evidence, initial: Set<String>) {
        val id=store.readableDatabase.rawQuery("SELECT session_id FROM recordings WHERE name=?",arrayOf(recording.name)).use { c -> check(c.moveToFirst() && !c.isNull(0)); c.getLong(0) }
        val data=store.report(setOf(id),false)
        check(data.sessions.size==1 && data.eventCount==1 && data.observedMs>65000 && data.exceededMs==data.observedMs)
        check(data.sessions.single().samples.size>=65)
        note("PASS: real monitoring sessions, 1-second levels and one continuous exceedance episode")
        // Known 100 ms samples verify split events and avoid treating per-second triggers as events.
        val time=System.currentTimeMillis()
        val fixture=SessionLogger(store,MonitorSettings(threshold=45.0),time)
        val levels=List(10){40.0}+List(12){50.0}+List(5){40.0}+List(3){60.0}+List(10){40.0}
        levels.forEachIndexed { i,level -> fixture.add(time+i*100,100,level) }
        fixture.close("test_fixture")
        val known=store.report(setOf(fixture.id),false)
        check(known.eventCount==2 && known.observedMs==4000L && known.exceededMs==1500L)
        check(known.sessions.single().events.map { it.duration }==listOf(1200L,300L))
        note("PASS: two separated exceedance events, exact durations and below-threshold monitoring")
        val root=File(targetContext.filesDir,"reports/report_${java.util.UUID.randomUUID()}")
        ReportBuilder(data,store.directory).generate(root)
        check(File(root,"READY").isFile)
        ZipFile(File(root,"report.docx")).use { zip ->
            check(zip.getEntry("word/embeddings/audio1.bin")!=null)
            val xml=zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().readText()
            check(xml.contains("共记录 1 次连续超限") && xml.contains(recording.name))
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(java.io.ByteArrayInputStream(xml.toByteArray()))
        }
        val html=File(root,"report.html").readText()
        check(html.contains("共记录 1 次连续超限") && html.contains("audio/${recording.name}") && html.contains("data:image/png;base64,"))
        val standalone=File(targetContext.cacheDir,"report-standalone.html")
        standalone.outputStream().use { ReportBuilder.exportHtml(root,it) }
        val standaloneText=standalone.readText()
        check(standaloneText.contains("src=\"data:audio/wav;base64,") && !standaloneText.contains("src=\"audio/"))
        val payload=standaloneText.substringAfter("src=\"data:audio/wav;base64,").substringBefore('"')
        check(java.util.Base64.getDecoder().decode(payload).contentEquals(File(store.directory,recording.name).readBytes()))
        val legacyRoot=File(targetContext.cacheDir,"legacy-report-${java.util.UUID.randomUUID()}")
        val legacy=NoiseReport(emptyList(),listOf(ReportRecording(Evidence("legacy.wav","2020-01-01T00:00:00Z",50.0,60.0,"deleted"),null,45.0,90.0)))
        ReportBuilder(legacy,store.directory).generate(legacyRoot)
        check(File(legacyRoot,"report.html").readText().contains("无数据，无法统计"))
        check(!File(legacyRoot,"report.html").readText().contains("共记录 0 次"))
        legacyRoot.deleteRecursively()
        check(targetContext.getSharedPreferences("reports",0).edit().putString("latest",root.name).commit())
        note("PASS: DOCX embeds WAV; HTML statistics match; standalone HTML preserves audio bytes")
        runOnMainSync { targetContext.startForegroundService(Intent(targetContext,ReportPreviewService::class.java).putExtra("report",root.name)) }
        waitFor { ReportPreviewService.url!=null }
        val uri=java.net.URI(requireNotNull(ReportPreviewService.url))
        fun get(path:String,range:String=""):ByteArray = java.net.Socket(uri.host,uri.port).use { socket ->
            socket.soTimeout=10000
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n$range\r\n".toByteArray(Charsets.US_ASCII))
            socket.getInputStream().readBytes()
        }
        val response=get(uri.path).toString(Charsets.UTF_8)
        check(response.startsWith("HTTP/1.1 200") && response.contains("环境声音监测报告"))
        val audioPath=uri.path.substringBeforeLast('/')+"/audio/${recording.name}"
        val range=get(audioPath,"Range: bytes=0-43\r\n").toString(Charsets.ISO_8859_1)
        check(range.startsWith("HTTP/1.1 206") && range.contains("RIFF"))
        check(get("/invalid/report.html").toString(Charsets.US_ASCII).startsWith("HTTP/1.1 404"))
        targetContext.stopService(Intent(targetContext,ReportPreviewService::class.java))
        note("PASS: loopback browser HTML, seekable WAV range requests and token isolation")
        store.deleteRecording(recording.name)
        check(!File(store.directory,recording.name).exists() && store.list().none { it.name==recording.name })
        val deleted=store.report(setOf(id),false)
        check(deleted.eventCount==1 && deleted.recordings.single().evidence.status=="deleted")
        check(initial.all { old -> store.list().any { it.name==old } })
        check(File(root,"audio/${recording.name}").isFile)
        val after=File(targetContext.filesDir,"reports/report_${java.util.UUID.randomUUID()}")
        ReportBuilder(deleted,store.directory).generate(after)
        check(!File(after,"report.html").readText().contains("<audio"))
        check(File(after,"report.html").readText().contains("原始录音已删除"))
        note("PASS: deletion removes only new test WAV; analytics, existing originals and report snapshots survive")
    }
}
