package com.example.noiserecorder

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.time.LocalTime

/** Covers restored waiting versus an already-started window, using only a new silent test period. */
class ReservationRecoveryInstrumentation : Instrumentation() {
    private val log=StringBuilder()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun note(value: String) { log.append(value).append('\n'); sendStatus(0,Bundle().apply { putString("stream","$value\n") }) }
    private fun waitFor(condition: () -> Boolean) {
        val until=SystemClock.elapsedRealtime()+10000
        while(!condition()) { check(SystemClock.elapsedRealtime()<until) { "Timeout: ${MonitorService.status}" }; SystemClock.sleep(50) }
    }
    private fun stop() { DailyReservation.cancel(targetContext); targetContext.stopService(Intent(targetContext,MonitorService::class.java)); waitFor { !MonitorService.running } }
    override fun onStart() {
        if(MonitorService.running || DailyReservation.enabled(targetContext)) { finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","FAIL: cancel existing reservation before testing") }); return }
        val settings=MonitorSettings.load(targetContext)
        val store=EvidenceStore(targetContext)
        val initialIds=store.sessions().map { it.id }.toSet()
        val initialNames=store.list().map { it.name }.toSet()
        val notifications=targetContext.getSystemService(NotificationManager::class.java)
        var success=false
        try {
            val minute=LocalTime.now().let { it.hour*60+it.minute }
            MonitorSettings((minute+10)%1440,(minute+11)%1440,140.0,-20.0).save(targetContext)
            DailyReservation.enable(targetContext)
            ReservationRestoreReceiver().onReceive(targetContext,Intent(Intent.ACTION_BOOT_COMPLETED))
            check(DailyReservation.enabled(targetContext) && !MonitorService.running)
            waitFor { notifications.activeNotifications.any { it.id==1003 && it.notification.channelId=="reservation_waiting" } }
            note("PASS: reboot before window restores scheduled waiting and quiet notification without microphone")
            var activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitFor { MonitorService.running }
            check(!MonitorService.listening && MonitorService.activeMode==MonitorMode.DAILY)
            check(uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("恢复每日预约？")?.isEmpty()!=false)
            note("PASS: opening app before window resumes waiting directly without a restoration dialog")
            stop()
            runOnMainSync { activity.finish() }

            val now=LocalTime.now().let { it.hour*60+it.minute }
            MonitorSettings((now+1439)%1440,(now+10)%1440,140.0,-20.0).save(targetContext)
            DailyReservation.enable(targetContext)
            ReservationRestoreReceiver().onReceive(targetContext,Intent(Intent.ACTION_BOOT_COMPLETED))
            waitFor { notifications.activeNotifications.any { it.id==1003 && it.notification.channelId=="reservation_restore" } }
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitFor { uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("恢复每日预约？")?.isNotEmpty()==true }
            check(!MonitorService.running && !MonitorService.listening)
            note("PASS: reboot inside window prompts for manual activation and does not claim to be listening")
            val activate=uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("开启预约值守").first { it.isClickable }
            check(activate.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor { MonitorService.listening }
            check(MonitorService.running && DailyReservation.enabled(targetContext))
            note("PASS: explicit activation starts microphone monitoring in the active scheduled window")
            stop()
            ReservationRestoreReceiver().onReceive(targetContext,Intent(Intent.ACTION_BOOT_COMPLETED))
            waitFor { !DailyReservation.enabled(targetContext) && notifications.activeNotifications.none { it.id==1003 } }
            note("PASS: cancelling reservation prevents reboot restoration")
            success=true
        } catch(e: Throwable) { note("FAIL: ${e.stackTraceToString()}") }
        finally {
            stop(); settings.save(targetContext)
            for(session in store.sessions().filter { it.id !in initialIds }) store.deleteSession(session.id)
            check(initialNames==store.list().map { it.name }.toSet())
            check(initialIds==store.sessions().map { it.id }.toSet())
            store.close()
            File(targetContext.filesDir,"reservation-recovery-test.txt").writeText(log.toString())
            finish(if(success) Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply { putString("stream",log.toString()); putBoolean("success",success) })
        }
    }
}
