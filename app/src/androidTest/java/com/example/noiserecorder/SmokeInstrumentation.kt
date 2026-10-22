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
        waitFor { MonitorService.running && MonitorService.currentDbfs > -120.0 }
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
            success = true
        } catch (e: Throwable) { note("FAIL: ${e.stackTraceToString()}") }
        finally {
            try { stopMonitor() } catch (_: Exception) { }
            original.save(targetContext)
            store.close()
            File(targetContext.filesDir, "smoke-report.txt").writeText(report.toString())
            finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", report.toString()); putBoolean("success", success) })
        }
    }
}
