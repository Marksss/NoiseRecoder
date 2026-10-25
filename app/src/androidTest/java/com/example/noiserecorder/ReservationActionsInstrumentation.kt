package com.example.noiserecorder

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import java.io.File
import java.time.Instant
import java.time.LocalTime

/** Exercises real capture and UI actions; deletes only newly created fixtures. */
class ReservationActionsInstrumentation : Instrumentation() {
    private val log=StringBuilder()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun note(value: String) { log.append(value).append('\n'); sendStatus(0,Bundle().apply { putString("stream","$value\n") }) }
    private fun waitFor(condition: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+15000
        while(!condition()) { check(SystemClock.elapsedRealtime()<end) { "Timeout: ${MonitorService.status}" }; SystemClock.sleep(30) }
    }
    private fun find(view: View, text: String): View? {
        if(view.contentDescription?.toString()==text || (view is Button && view.text.toString()==text)) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),text)?.let { return it }
        return null
    }
    private fun click(activity: Activity, text: String) = runOnMainSync { checkNotNull(find(activity.window.decorView,text)).apply { check(isEnabled); performClick() } }
    private fun dialogClick(text: String) {
        waitFor { uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.any { it.text?.toString()==text && it.isClickable }==true }
        check(uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(text).first { it.text?.toString()==text && it.isClickable }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun stop() {
        DailyReservation.cancel(targetContext)
        targetContext.stopService(Intent(targetContext,MonitorService::class.java))
        waitFor { !MonitorService.running }
    }
    private fun idle() {
        val busy=MainActivity::class.java.getDeclaredField("exporting").apply { isAccessible=true }
        waitFor { !busy.getBoolean(null) }
    }
    override fun onStart() {
        if(MonitorService.running || DailyReservation.enabled(targetContext)) { finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","FAIL: stop existing duty before test") }); return }
        val store=EvidenceStore(targetContext)
        val originalSettings=MonitorSettings.load(targetContext)
        val originalNames=store.list().map { it.name }.toSet()
        val originalIds=store.sessions().map { it.id }.toSet()
        val reports=File(targetContext.filesDir,"reports").listFiles()?.map { it.name }?.toSet() ?: emptySet()
        var success=false
        fun fixture(sessionId: Long?=null): Pair<Long,String> {
            val at=System.currentTimeMillis()-3600000
            val id=sessionId ?: SessionLogger(store,MonitorSettings(),at).let { it.add(at,100,50.0); it.close("complete"); it.id }
            val name="actions_fixture_${java.util.UUID.randomUUID()}.wav"
            store.begin(name,Instant.ofEpochMilli(at).toString(),50.0,MonitorSettings(),id)
            WavWriter(File(store.directory,name)).use { it.write(ByteArray(3200)) }
            store.finish(name,50.0,0.1,"complete")
            return id to name
        }
        try {
            var minute=LocalTime.now().let { it.hour*60+it.minute }
            MonitorSettings((minute+10)%1440,(minute+20)%1440,0.0,160.0).save(targetContext)
            val first=fixture(); val second=fixture(); val third=fixture(); val fourth=fixture()
            var activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            click(activity,"设置")
            for(key in listOf("start","end")) {
                val field=MainActivity::class.java.getDeclaredField(key).apply { isAccessible=true }.get(activity) as EditText
                val before=field.text.toString()
                check(field.keyListener==null && !field.isFocusable)
                runOnMainSync { field.performClick() }
                waitFor { uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/timePicker")?.isNotEmpty()==true }
                uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                waitFor { uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/timePicker")?.isEmpty()==true }
                check(field.text.toString()==before)
            }
            note("PASS: both time fields open native time pickers, reject keyboard editing, and preserve values on cancel")
            click(activity,"首页"); click(activity,"预约每日值守")
            waitFor { MonitorService.running }
            check(!MonitorService.listening)
            click(activity,"记录")
            // The row remains collapsed: reflect its expansion control through its accessibility label.
            runOnMainSync {
                val method=MainActivity::class.java.getDeclaredMethod("confirmDelete",List::class.java).apply { isAccessible=true }
                method.invoke(activity,listOf(first.second))
            }
            dialogClick("永久删除")
            waitFor { !File(store.directory,first.second).exists() }
            idle()
            click(activity,"删除整个监听时段 ${second.first}"); dialogClick("删除整个时段")
            waitFor { store.sessions().none { it.id==second.first } }
            idle()
            click(activity,"删除本地所有已生成报告"); dialogClick("取消")
            check(MonitorService.running && !MonitorService.listening && DailyReservation.enabled(targetContext))
            note("PASS: waiting reservation permits audio/period deletion and report cleanup dialog without cancelling reservation")
            stop()
            minute=LocalTime.now().let { it.hour*60+it.minute }
            MonitorSettings((minute+1439)%1440,(minute+10)%1440,0.0,160.0).save(targetContext)
            runOnMainSync { activity.finish() }
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            click(activity,"预约每日值守")
            waitFor { MonitorService.listening && store.list().any { it.status=="recording" } }
            val live=store.list().first { it.status=="recording" }
            val file=File(store.directory,live.name)
            val size=file.length()
            val finishedInLive=fixture(checkNotNull(live.sessionId))
            check(runCatching { store.deleteRecording(live.name) }.isFailure)
            check(runCatching { store.deleteRecording(finishedInLive.second) }.isFailure)
            check(runCatching { store.deleteSession(checkNotNull(live.sessionId)) }.isFailure)
            click(activity,"记录")
            runOnMainSync {
                check(!checkNotNull(find(activity.window.decorView,"删除整个监听时段 ${live.sessionId}")).isEnabled)
                check(!checkNotNull(find(activity.window.decorView,"删除录音 ${live.name}")).isEnabled)
                check(!checkNotNull(find(activity.window.decorView,"删除录音 ${finishedInLive.second}")).isEnabled)
                MainActivity::class.java.getDeclaredMethod("confirmDelete",List::class.java).apply { isAccessible=true }.invoke(activity,listOf(fourth.second))
            }
            dialogClick("永久删除"); waitFor { !File(store.directory,fourth.second).exists() }; idle()
            click(activity,"删除整个监听时段 ${third.first}"); dialogClick("删除整个时段")
            waitFor { store.sessions().none { it.id==third.first } && file.length()>size+3200 }
            idle()
            check(MonitorService.listening && store.sessions().single { it.id==live.sessionId }.status=="monitoring")
            click(activity,"删除本地所有已生成报告"); dialogClick("取消")
            note("PASS: active duty allows historical deletion while WAV grows; active period and all its audio are protected")
            click(activity,"首页"); click(activity,"停止本次预约值守"); dialogClick("仅停止本次")
            waitFor { !MonitorService.listening && store.sessions().single { it.id==live.sessionId }.status=="user_stop" }
            check(MonitorService.running && DailyReservation.enabled(targetContext) && DailyReservation.skipped(targetContext))
            check(store.list().single { it.name==live.name }.status=="user_stop")
            file.inputStream().use { check(String(it.readBytes().take(4).toByteArray())=="RIFF") }
            note("PASS: stopping this occurrence finalizes WAV/period and releases microphone while preserving daily reservation")
            targetContext.stopService(Intent(targetContext,MonitorService::class.java)); waitFor { !MonitorService.running }
            ReservationRestoreReceiver().onReceive(targetContext,Intent(Intent.ACTION_BOOT_COMPLETED))
            runOnMainSync { activity.finish() }
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitFor { MonitorService.running }
            check(!MonitorService.listening && DailyReservation.skipped(targetContext))
            note("PASS: occurrence skip survives service restart and simulated boot; reopening app restores waiting without capture")
            click(activity,"恢复预约监听"); waitFor { MonitorService.listening }
            check(!DailyReservation.skipped(targetContext))
            click(activity,"停止本次预约值守"); dialogClick("仅停止本次"); waitFor { !MonitorService.listening }
            targetContext.getSharedPreferences("daily_reservation",Activity.MODE_PRIVATE).edit().putLong("skip_until",System.currentTimeMillis()+1000).commit()
            waitFor { MonitorService.listening }
            note("PASS: manual resume activates capture; expiring the persisted skip also resumes daily scheduling automatically")
            click(activity,"停止本次预约值守"); dialogClick("永久取消预约")
            waitFor { !MonitorService.running }
            check(!DailyReservation.enabled(targetContext) && !DailyReservation.skipped(targetContext))
            note("PASS: permanent cancellation saves audio, releases microphone and clears daily reservation/skip")
            success=true
        } catch(e: Throwable) { note("FAIL: ${e.stackTraceToString()}") }
        finally {
            stop(); originalSettings.save(targetContext)
            for(session in store.sessions().filter { it.id !in originalIds }) store.deleteSession(session.id)
            check(originalNames==store.list().map { it.name }.toSet())
            check(originalIds==store.sessions().map { it.id }.toSet())
            check(reports==(File(targetContext.filesDir,"reports").listFiles()?.map { it.name }?.toSet() ?: emptySet<String>()))
            store.close()
            File(targetContext.filesDir,"reservation-actions-test.txt").writeText(log.toString())
            finish(if(success) Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply { putString("stream",log.toString()); putBoolean("success",success) })
        }
    }
}
