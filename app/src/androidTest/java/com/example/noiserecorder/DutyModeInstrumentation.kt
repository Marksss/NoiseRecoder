package com.example.noiserecorder

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import java.io.File
import java.time.LocalTime
import java.util.Locale

/** Focused real-device test: uses only newly created test recordings; restores saved settings. */
class DutyModeInstrumentation : Instrumentation() {
    private val log=StringBuilder()
    private var activity: Activity?=null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun note(text: String) { log.append(text).append('\n'); sendStatus(0,Bundle().apply { putString("stream","$text\n") }) }
    private fun waitFor(timeout: Long=5000, condition: () -> Boolean) {
        val until=SystemClock.elapsedRealtime()+timeout
        while(!condition()) { check(SystemClock.elapsedRealtime()<until) { "Timeout: ${MonitorService.status}" }; SystemClock.sleep(100) }
    }
    private fun stop() { targetContext.stopService(Intent(targetContext,MonitorService::class.java)); waitFor { !MonitorService.running } }
    private fun findButton(view: View, text: String): Button? {
        if(view is Button && view.text.toString()==text) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) findButton(view.getChildAt(i),text)?.let { return it }
        return null
    }
    private fun launch(settings: MonitorSettings, title: String) {
        stop()
        activity?.let { runOnMainSync { it.finish() } }
        settings.save(targetContext)
        activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        runOnMainSync { checkNotNull(findButton(activity!!.window.decorView,title)).performClick() }
        waitFor { MonitorService.running }
    }
    override fun onStart() {
        if(MonitorService.running) {
            note("FAIL: stop existing monitoring before running this test")
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream",log.toString()); putBoolean("success",false) })
            return
        }
        val original=MonitorSettings.load(targetContext)
        val store=EvidenceStore(targetContext)
        val existing=store.list().map { it.name }.toSet()
        val existingSessions=store.sessions().map { it.id }.toSet()
        var success=false
        try {
            val now=LocalTime.now().let { it.hour*60+it.minute }
            launch(MonitorSettings((now+10)%1440,(now+11)%1440,0.0,160.0),"立即开启值守")
            waitFor { MonitorService.listening && store.list().any { it.name !in existing } }
            uiAutomation.executeShellCommand("input keyevent 223").close()
            SystemClock.sleep(3000)
            check(MonitorService.running && MonitorService.listening)
            stop()
            check(!MonitorService.listening)
            val immediate=store.list().single { it.name !in existing }
            check(immediate.status=="user_stop" && immediate.duration>=3.0)
            note("PASS: immediate button bypasses schedule, records under lock, manual stop finalizes WAV")
            uiAutomation.executeShellCommand("input keyevent 224").close()
            uiAutomation.executeShellCommand("wm dismiss-keyguard").close()

            // Leave time to assert the waiting state before the minute boundary.
            while(LocalTime.now().second>=55) SystemClock.sleep(100)
            val minute=LocalTime.now().let { it.hour*60+it.minute }
            val start=(minute+1)%1440; val end=(minute+2)%1440
            launch(MonitorSettings(start,end,0.0,160.0),"预约每日值守")
            SystemClock.sleep(1500)
            check(!MonitorService.listening && store.list().count { it.name !in existing }==1)
            check(store.sessions().count { it.id !in existingSessions }==1)
            runOnMainSync { check((findButton(activity!!.window.decorView,"预约每日值守") as CheckBox).isChecked) }
            note("PASS: checking daily reservation arms schedule without microphone, WAV or session before start")
            note(String.format(Locale.US,"Waiting for real device window %02d:%02d-%02d:%02d",start/60,start%60,end/60,end%60))
            uiAutomation.executeShellCommand("input keyevent 223").close()
            waitFor(65000) { MonitorService.listening && store.list().count { it.name !in existing }==2 }
            note("PASS: daily schedule automatically starts microphone and recording while locked")
            waitFor(65000) { !MonitorService.listening }
            waitFor { store.list().any { it.name !in existing && it.status=="window_end" } }
            check(MonitorService.running) { "Daily reservation must remain armed for next day" }
            val scheduled=store.list().single { it.name !in existing && it.status=="window_end" }
            check(scheduled.duration in 57.0..61.0)
            val header=File(store.directory,scheduled.name).readBytes()
            check(String(header.copyOfRange(0,4))=="RIFF")
            val size=java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(40)
            check(size+44==header.size)
            check(store.sessions().filter { it.id !in existingSessions }.all { it.status in setOf("user_stop","window_end") })
            note("PASS: scheduled end stops microphone, finalizes WAV/session and waits for next daily window")
            runOnMainSync {
                val reservation=findButton(activity!!.window.decorView,"预约每日值守") as CheckBox
                check(reservation.isChecked)
                reservation.performClick()
            }
            waitFor { !MonitorService.running }
            SystemClock.sleep(700)
            check(!MonitorService.running && !MonitorService.listening)
            runOnMainSync { check(!(findButton(activity!!.window.decorView,"预约每日值守") as CheckBox).isChecked) }
            note("PASS: unchecking daily reservation cancels service and clears checked state")
            success=true
        } catch(e: Throwable) { note("FAIL: ${e.stackTraceToString()}") }
        finally {
            try { stop() } catch(_: Throwable) { }
            original.save(targetContext)
            // Remove only this run's fixtures after the worker has finalized them.
            if(!MonitorService.running) {
                val addedSessions=store.sessions().filter { it.id !in existingSessions }.map { it.id }
                store.list().filter { it.name !in existing }.forEach { row ->
                    store.deleteRecording(row.name)
                    store.writableDatabase.delete("triggers","name=?",arrayOf(row.name))
                    store.writableDatabase.delete("recordings","name=?",arrayOf(row.name))
                }
                for(id in addedSessions) {
                    val key=arrayOf(id.toString())
                    store.writableDatabase.delete("levels","session_id=?",key)
                    store.writableDatabase.delete("exceed_events","session_id=?",key)
                    store.writableDatabase.delete("sessions","id=?",key)
                }
            }
            check(existing.all { name -> store.list().any { it.name==name } })
            store.close()
            uiAutomation.executeShellCommand("input keyevent 224").close()
            uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
            File(targetContext.filesDir,"duty-mode-test.txt").writeText(log.toString())
            finish(if(success) Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply { putString("stream",log.toString()); putBoolean("success",success) })
        }
    }
}
