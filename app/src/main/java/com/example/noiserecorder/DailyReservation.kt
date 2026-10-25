package com.example.noiserecorder

import android.Manifest
import android.app.Notification
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale
import java.time.ZonedDateTime

/** Persists user intent; boot restoration must not claim the microphone is already active. */
object DailyReservation {
    const val RESTORE="restore_daily_reservation"
    const val STOP_CHOICES="stop_daily_choices"
    const val CANCEL="com.example.noiserecorder.CANCEL_DAILY_RESERVATION"
    const val BOUNDARY="com.example.noiserecorder.DAILY_RESERVATION_BOUNDARY"
    private const val NOTIFICATION=1003
    fun enabled(context: Context) = context.getSharedPreferences("daily_reservation",Context.MODE_PRIVATE).getBoolean("enabled",false)
    fun enable(context: Context) {
        check(context.getSharedPreferences("daily_reservation",Context.MODE_PRIVATE).edit().putBoolean("enabled",true).commit())
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        context.getSystemService(AlarmManager::class.java).cancel(boundaryIntent(context))
    }
    fun cancel(context: Context) {
        check(context.getSharedPreferences("daily_reservation",Context.MODE_PRIVATE).edit().putBoolean("enabled",false).remove("skip_until").commit())
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        context.getSystemService(AlarmManager::class.java).cancel(boundaryIntent(context))
    }
    fun skipped(context: Context) = System.currentTimeMillis()<context.getSharedPreferences("daily_reservation",Context.MODE_PRIVATE).getLong("skip_until",0)
    fun resume(context: Context) { check(context.getSharedPreferences("daily_reservation",Context.MODE_PRIVATE).edit().remove("skip_until").commit()) }
    /** Suppress only this occurrence, including after a process/device restart. */
    fun skipCurrent(context: Context) {
        check(enabled(context) && inWindow(context)) { "当前不在预约时段内" }
        val settings=MonitorService.activeSettings ?: MonitorSettings.load(context)
        val now=ZonedDateTime.now()
        val end=AudioLogic.nextBoundary(now,settings.end)
        check(context.getSharedPreferences("daily_reservation",Context.MODE_PRIVATE).edit().putLong("skip_until",end.toInstant().toEpochMilli()).commit())
    }
    fun inWindow(context: Context): Boolean {
        val settings=MonitorService.activeSettings ?: MonitorSettings.load(context)
        val now=ZonedDateTime.now()
        return AudioLogic.inWindow(now.hour*60+now.minute,settings.start,settings.end)
    }
    private fun boundaryIntent(context: Context) = PendingIntent.getBroadcast(context,1005,Intent(context,ReservationRestoreReceiver::class.java).setAction(BOUNDARY),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun scheduleBoundary(context: Context, inside: Boolean) {
        val settings=MonitorSettings.load(context)
        val now=ZonedDateTime.now()
        val minute=if(inside) settings.end else settings.start
        val next=AudioLogic.nextBoundary(now,minute)
        val alarms=context.getSystemService(AlarmManager::class.java)
        val pending=boundaryIntent(context)
        if(Build.VERSION.SDK_INT<31 || alarms.canScheduleExactAlarms()) {
            try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next.toInstant().toEpochMilli(),pending); return }
            catch(_: SecurityException) { }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next.toInstant().toEpochMilli(),pending)
    }
    fun notifyRestore(context: Context) {
        if(!enabled(context) || MonitorService.running) return
        val window=inWindow(context)
        val inside=window && !skipped(context)
        scheduleBoundary(context,window)
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        val settings=MonitorSettings.load(context)
        val times=String.format(Locale.US,"%02d:%02d—%02d:%02d",settings.start/60,settings.start%60,settings.end/60,settings.end%60)
        val notifications=context.getSystemService(NotificationManager::class.java)
        val channel=if(inside) "reservation_restore" else "reservation_waiting"
        notifications.createNotificationChannel(NotificationChannel(channel,if(inside) "预约恢复" else "预约等待",if(inside) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(context,1003,Intent(context,MainActivity::class.java).putExtra(RESTORE,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel=PendingIntent.getBroadcast(context,1004,Intent(context,ReservationRestoreReceiver::class.java).setAction(CANCEL),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text=if(inside) "每日 $times · 点按开启值守，当前尚未采集声音" else if(window) "本次值守已停止 · 保留每日 $times 的后续预约" else "每日 $times · 已恢复预约等待，到时提醒开启值守"
        notifications.notify(NOTIFICATION,Notification.Builder(context,channel)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(if(inside) "预约时段已到 · 点击开启值守" else "每日预约已恢复 · 等待时段")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open)
            .setOngoing(true).addAction(Notification.Action.Builder(null,"取消预约",cancel).build()).build())
    }
}

class ReservationRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action==DailyReservation.CANCEL) {
            DailyReservation.cancel(context)
            if(MonitorService.activeMode==MonitorMode.DAILY) context.stopService(Intent(context,MonitorService::class.java))
        } else if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,DailyReservation.BOUNDARY)) {
            // Starting a microphone FGS from boot is restricted. A user-opened Activity resumes it.
            DailyReservation.notifyRestore(context)
        }
    }
}
