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
import java.io.File
import java.time.Instant

/** Creates isolated fixtures; never deletes pre-existing user periods or recordings. */
class RecordDeletionInstrumentation : Instrumentation() {
    private val log=StringBuilder()
    private val fixtures=mutableSetOf<Long>()
    private val names=mutableMapOf<Long,List<String>>()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun note(text: String) { log.append(text).append('\n'); sendStatus(0,Bundle().apply { putString("stream","$text\n") }) }
    private fun waitFor(condition: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+10000
        while(!condition()) { check(SystemClock.elapsedRealtime()<end) { "Timed out" }; SystemClock.sleep(100) }
    }
    private fun find(view: View, description: String): View? {
        if(view.contentDescription?.toString()==description) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),description)?.let { return it }
        return null
    }
    private fun clickTab(view: View): Boolean {
        if(view is Button && view.text.toString()=="记录") { view.performClick(); return true }
        if(view is ViewGroup) for(i in 0 until view.childCount) if(clickTab(view.getChildAt(i))) return true
        return false
    }
    private fun dialogClick(text: String) {
        waitFor { uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.any { it.text?.toString()==text && it.isClickable }==true }
        val node=uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(text).first { it.text?.toString()==text && it.isClickable }
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun fixture(store: EvidenceStore, count: Int): Long {
        val time=System.currentTimeMillis()
        val logger=SessionLogger(store,MonitorSettings(),time)
        fixtures.add(logger.id)
        logger.add(time,100,50.0); logger.add(time+100,100,30.0); logger.close("complete")
        val files=(0 until count).map {
            val name="delete_fixture_${java.util.UUID.randomUUID()}.wav"
            store.begin(name,Instant.now().toString(),50.0,MonitorSettings(),logger.id)
            WavWriter(File(store.directory,name)).use { wav -> wav.write(ByteArray(3200) { 1 }) }
            store.trigger(name,50.0); store.finish(name,50.0,0.1,"complete")
            name
        }
        names[logger.id]=files
        return logger.id
    }
    private fun count(store: EvidenceStore, table: String, column: String, id: Long) = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE $column=?",arrayOf(id.toString())).use { c -> c.moveToFirst(); c.getInt(0) }
    private fun assertRemoved(store: EvidenceStore, id: Long) {
        check(count(store,"sessions","id",id)==0)
        for(table in listOf("recordings","levels","exceed_events")) check(count(store,table,"session_id",id)==0)
        for(name in names.getValue(id)) {
            check(!File(store.directory,name).exists())
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM triggers WHERE name=?",arrayOf(name)).use { c -> c.moveToFirst(); check(c.getInt(0)==0) }
        }
    }
    override fun onStart() {
        if(MonitorService.running) { finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","FAIL: stop monitoring or cancel reservation first") }); return }
        var success=false
        val store=EvidenceStore(targetContext)
        val existing=store.list().map { it.name }.toSet()
        val existingSessions=store.sessions().map { it.id }.toSet()
        val snapshot=File(targetContext.cacheDir,"delete-fixture-snapshot-${java.util.UUID.randomUUID()}.wav")
        try {
            val first=fixture(store,2); val other=fixture(store,1)
            val firstFile=names.getValue(first)[0]
            File(store.directory,firstFile).copyTo(snapshot)
            store.deleteRecording(firstFile)
            check(count(store,"sessions","id",first)==1 && count(store,"levels","session_id",first)>0 && count(store,"exceed_events","session_id",first)>0)
            check(store.report(setOf(first),false).recordings.any { it.evidence.name==firstFile && it.evidence.status=="deleted" })
            note("PASS: individual audio deletion preserves period, levels and exceedance statistics")
            store.deleteSession(first); assertRemoved(store,first)
            check(File(store.directory,names.getValue(other).single()).exists())
            check(snapshot.isFile && snapshot.length()==3244L)
            note("PASS: whole-period deletion removes audio, tombstones, triggers, levels and events; other periods and copies remain")

            val recovery=fixture(store,1)
            store.writableDatabase.execSQL("UPDATE sessions SET status='deleting' WHERE id=?",arrayOf(recovery))
            store.recover(); assertRemoved(store,recovery)
            note("PASS: recovery completes a previously marked whole-period deletion")

            val active=fixture(store,1)
            store.writableDatabase.execSQL("UPDATE sessions SET status='monitoring' WHERE id=?",arrayOf(active))
            check(runCatching { store.deleteSession(active) }.isFailure)
            check(File(store.directory,names.getValue(active).single()).exists())
            store.writableDatabase.execSQL("UPDATE sessions SET status='complete' WHERE id=?",arrayOf(active))
            store.writableDatabase.execSQL("UPDATE recordings SET status='recording' WHERE session_id=?",arrayOf(active))
            check(runCatching { store.deleteSession(active) }.isFailure)
            store.writableDatabase.execSQL("UPDATE recordings SET status='complete' WHERE session_id=?",arrayOf(active))
            note("PASS: active period or active recording cannot be deleted")

            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            runOnMainSync { check(clickTab(activity.window.decorView)); checkNotNull(find(activity.window.decorView,"删除整个监听时段 $other")).performClick() }
            dialogClick("取消")
            check(count(store,"sessions","id",other)==1)
            runOnMainSync { checkNotNull(find(activity.window.decorView,"删除整个监听时段 $other")).performClick() }
            dialogClick("删除整个时段")
            waitFor { count(store,"sessions","id",other)==0 }
            assertRemoved(store,other)
            note("PASS: grouped records page confirms whole-period deletion; Cancel preserves data, Confirm deletes only selected fixture")
            success=true
        } catch(e: Throwable) { note("FAIL: ${e.stackTraceToString()}") }
        finally {
            for(id in fixtures) {
                if(count(store,"sessions","id",id)>0) {
                    store.writableDatabase.execSQL("UPDATE sessions SET status='complete' WHERE id=?",arrayOf(id))
                    store.writableDatabase.execSQL("UPDATE recordings SET status='complete' WHERE session_id=?",arrayOf(id))
                    store.deleteSession(id)
                }
            }
            snapshot.delete()
            check(existing==store.list().map { it.name }.toSet())
            check(existingSessions==store.sessions().map { it.id }.toSet())
            store.close()
            File(targetContext.filesDir,"record-deletion-test.txt").writeText(log.toString())
            finish(if(success) Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply { putString("stream",log.toString()); putBoolean("success",success) })
        }
    }
}
