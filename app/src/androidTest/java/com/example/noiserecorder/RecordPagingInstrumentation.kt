package com.example.noiserecorder

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ScrollView

/** Uses existing periods read-only; exercises real bottom scrolling and preserves user data. */
class RecordPagingInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun waitFor(condition: () -> Boolean) {
        val until=SystemClock.elapsedRealtime()+10000
        while(!condition()) { check(SystemClock.elapsedRealtime()<until) { "Paging timeout" }; SystemClock.sleep(50) }
    }
    override fun onStart() {
        val log=StringBuilder()
        fun note(value: String) { log.append(value).append('\n') }
        try {
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            fun field(name: String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
            fun count()=field("renderedSessions").getInt(activity)
            val tab=MainActivity::class.java.getDeclaredMethod("selectTab",Int::class.javaPrimitiveType).apply { isAccessible=true }
            runOnMainSync { tab.invoke(activity,1) }
            val total=EvidenceStore(targetContext).use { it.sessions().size }
            check(total>15) { "Need more than 15 existing periods for this device check; got $total" }
            waitFor { !field("recordsQueryPending").getBoolean(activity) && count()>0 }
            check(count()==15)
            note("PASS: first page renders exactly 15 periods")
            val select=field("selectAllPeriods").get(activity) as android.widget.CheckBox
            val eligible=EvidenceStore(targetContext).use { it.sessions().filter { s -> s.status !in setOf("monitoring","deleting") }.map { s -> s.id }.toSet() }
            runOnMainSync { check(!select.isChecked); select.performClick(); check(select.isChecked) }
            check((field("selectedReportSessions").get(activity) as Set<*>)==eligible)
            note("PASS: select all includes eligible periods beyond the first page")
            @Suppress("UNCHECKED_CAST")
            val pages=field("pages").get(activity) as List<ScrollView>
            var expected=15
            while(expected<total) {
                expected=minOf(expected+15,total)
                runOnMainSync { pages[1].fullScroll(View.FOCUS_DOWN) }
                waitFor { count()>=expected }
                check(count()==expected) { "Loaded more than one group" }; SystemClock.sleep(250)
            }
            note("PASS: bottom scrolling appends 15 periods per group, including final partial group")
            runOnMainSync { pages[1].fullScroll(View.FOCUS_UP) }
            SystemClock.sleep(200)
            runOnMainSync { pages[1].fullScroll(View.FOCUS_DOWN) }
            SystemClock.sleep(300)
            check(count()==total)
            note("PASS: scrolling after the final group adds no duplicate periods")
            runOnMainSync { tab.invoke(activity,2); tab.invoke(activity,1) }
            check(count()==total)
            waitFor { !field("recordsQueryPending").getBoolean(activity) }
            note("PASS: switching tabs preserves the loaded range")
            val records=field("records").get(activity) as android.widget.LinearLayout
            val first=records.getChildAt(0)
            var elapsed=0L
            runOnMainSync {
                tab.invoke(activity,0)
                val before=SystemClock.elapsedRealtime()
                tab.invoke(activity,1)
                elapsed=SystemClock.elapsedRealtime()-before
            }
            waitFor { !field("recordsQueryPending").getBoolean(activity) }
            check(records.getChildAt(0)===first) { "Unchanged records rebuilt on tab switch" }
            check(elapsed<250) { "Tab callback blocked for $elapsed ms" }
            note("PASS: cached cards reused and tab callback returns in $elapsed ms")
            @Suppress("UNCHECKED_CAST")
            val boxes=field("recordSelectionBoxes").get(activity) as Map<Long,android.widget.CheckBox>
            check(boxes.all { (id,box) -> box.isChecked==(id in eligible) })
            runOnMainSync {
                boxes.values.first { it.isEnabled && it.isChecked }.performClick()
                check(!select.isChecked)
                select.performClick(); check(select.isChecked)
                select.performClick(); check(!select.isChecked)
            }
            check((field("selectedReportSessions").get(activity) as Set<*>).isEmpty())
            check((field("recordSelectionBoxes").get(activity) as Map<*,*>).values.all { !(it as android.widget.CheckBox).isChecked })
            note("PASS: newly loaded checkboxes match selection, and cancel all clears every period")
            finish(Activity.RESULT_OK,Bundle().apply { putString("stream",log.toString()) })
        } catch(e:Throwable) { finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream",log.toString()+"FAIL: "+e.stackTraceToString()) }) }
    }
}