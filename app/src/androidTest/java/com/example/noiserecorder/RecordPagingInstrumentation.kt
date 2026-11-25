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
            check(count()==15)
            note("PASS: first page renders exactly 15 periods")
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
            note("PASS: switching tabs preserves the loaded range")
            finish(Activity.RESULT_OK,Bundle().apply { putString("stream",log.toString()) })
        } catch(e:Throwable) { finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream",log.toString()+"FAIL: "+e.stackTraceToString()) }) }
    }
}