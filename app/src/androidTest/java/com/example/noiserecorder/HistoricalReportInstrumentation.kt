package com.example.noiserecorder

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import java.io.File
import java.time.Instant
import java.util.zip.ZipFile

/** Reports a closed fixture while real microphone recording continues. Restores all user state. */
class HistoricalReportInstrumentation : Instrumentation() {
    private val log=StringBuilder()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun note(text: String) { log.append(text).append('\n'); sendStatus(0,Bundle().apply { putString("stream","$text\n") }) }
    private fun waitFor(condition: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+30000
        while(!condition()) { check(SystemClock.elapsedRealtime()<end) { "Timeout: ${MonitorService.status}" }; SystemClock.sleep(20) }
    }
    private fun find(view: View, value: String): View? {
        if(view.contentDescription?.toString()==value || (view is Button && view.text.toString()==value)) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),value)?.let { return it }
        return null
    }
    override fun onStart() {
        if(MonitorService.running || DailyReservation.enabled(targetContext)) { finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","FAIL: stop existing monitoring before test") }); return }
        val store=EvidenceStore(targetContext)
        val originalSettings=MonitorSettings.load(targetContext)
        val initialNames=store.list().map { it.name }.toSet()
        val initialIds=store.sessions().map { it.id }.toSet()
        val preferences=targetContext.getSharedPreferences("reports",Activity.MODE_PRIVATE)
        val previousLatest=preferences.getString("latest",null)
        val export=File(targetContext.cacheDir,"history-report-test-${java.util.UUID.randomUUID()}.docx")
        var generated: File?=null
        var success=false
        try {
            val at=System.currentTimeMillis()-3600000
            val logger=SessionLogger(store,MonitorSettings(),at)
            logger.add(at,100,50.0); logger.add(at+100,100,30.0); logger.close("complete")
            val historyNames=(0..1).map { index ->
                val name="history_fixture_${java.util.UUID.randomUUID()}.wav"
                store.begin(name,Instant.ofEpochMilli(at).toString(),50.0,MonitorSettings(),logger.id)
                val data=ByteArray(1600000).also { java.util.Random(42L+index).nextBytes(it) }
                WavWriter(File(store.directory,name)).use { it.write(data) }
                store.finish(name,50.0,50.0,"complete")
                name
            }
            originalSettings.copy(threshold=0.0,offset=160.0).save(targetContext)
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            runOnMainSync { checkNotNull(find(activity.window.decorView,"立即开启值守")).performClick() }
            waitFor { MonitorService.listening && store.list().any { it.name !in initialNames && it.name !in historyNames } }
            val live=store.list().single { it.name !in initialNames && it.name !in historyNames }
            val liveFile=File(store.directory,live.name)
            runOnMainSync {
                checkNotNull(find(activity.window.decorView,"记录")).performClick()
                check(!checkNotNull(find(activity.window.decorView,"选择报告时段 ${live.sessionId}")).isEnabled)
                checkNotNull(find(activity.window.decorView,"选择报告时段 ${logger.id}")).performClick()
                checkNotNull(find(activity.window.decorView,"生成所选时段报告")).performClick()
            }
            note("PASS: current monitoring period is disabled; closed historical period remains selectable")
            val sizeBefore=liveFile.length()
            waitFor { uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("生成 Word 和 HTML")?.any { it.isClickable }==true }
            val confirm=uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("生成 Word 和 HTML").first { it.isClickable }
            check(confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor {
                check(MonitorService.running && MonitorService.listening) { "Monitoring stopped during historical report" }
                preferences.getString("latest",null)!=previousLatest
            }
            val root=File(targetContext.filesDir,"reports/${preferences.getString("latest",null)}")
            generated=root
            check(File(root,"READY").isFile)
            waitFor { liveFile.length()>sizeBefore+3200 }
            check(store.sessions().single { it.id==live.sessionId }.status=="monitoring")
            check(store.list().single { it.name==live.name }.status=="recording")
            val liveHeader=ByteArray(4)
            liveFile.inputStream().use { check(it.read(liveHeader)==4) }
            check(liveHeader.all { it.toInt()==0 }) { "Live WAV was repaired while still recording" }
            note("PASS: microphone and live WAV continue; live session/index remain active and header is untouched")
            val html=File(root,"report.html").readText()
            check(historyNames.all { html.contains(it) } && !html.contains(live.name))
            ZipFile(File(root,"report.docx")).use { zip ->
                val xml=zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().readText()
                check(historyNames.all { xml.contains(it) } && !xml.contains(live.name))
            }
            note("PASS: historical Word and HTML include both closed audio files and exclude live recording")

            val busy=MainActivity::class.java.getDeclaredField("exporting").apply { isAccessible=true }
            waitFor { !busy.getBoolean(null) }
            MainActivity::class.java.getDeclaredField("exportKind").apply { isAccessible=true }.set(activity,"docx")
            MainActivity::class.java.getDeclaredField("pendingReport").apply { isAccessible=true }.set(activity,root.name)
            val result=MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply { isAccessible=true }
            runOnMainSync { result.invoke(activity,21,Activity.RESULT_OK,Intent().setData(Uri.fromFile(export))) }
            waitFor { !busy.getBoolean(null) && export.isFile && export.length()==File(root,"report.docx").length() }
            ZipFile(export).use { check(it.getEntry("word/document.xml")!=null) }
            check(MonitorService.running && MonitorService.listening)
            note("PASS: generated Word export succeeds while microphone monitoring remains active")
            success=true
        } catch(e: Throwable) { note("FAIL: ${e.stackTraceToString()}") }
        finally {
            targetContext.stopService(Intent(targetContext,MonitorService::class.java))
            waitFor { !MonitorService.running }
            originalSettings.save(targetContext)
            generated?.let { root ->
                check(root.canonicalFile.parentFile==File(targetContext.filesDir,"reports").canonicalFile)
                if(preferences.getString("latest",null)==root.name) {
                    val edit=preferences.edit()
                    if(previousLatest==null) edit.remove("latest") else edit.putString("latest",previousLatest)
                    edit.commit()
                }
                root.deleteRecursively()
            }
            export.delete()
            for(session in store.sessions().filter { it.id !in initialIds }) store.deleteSession(session.id)
            check(initialNames==store.list().map { it.name }.toSet())
            check(initialIds==store.sessions().map { it.id }.toSet())
            store.close()
            if(success) note("PASS: original settings, reports, recording names and period IDs preserved; test fixtures removed")
            File(targetContext.filesDir,"historical-report-test.txt").writeText(log.toString())
            finish(if(success) Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply { putString("stream",log.toString()); putBoolean("success",success) })
        }
    }
}
