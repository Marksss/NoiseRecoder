package com.example.noiserecorder

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

class MonitorService : Service() {
    companion object {
        const val STOP = "com.example.noiserecorder.STOP"
        const val EXTRA_MODE = "monitor_mode"
        @Volatile var running = false
        @Volatile var activeMode: MonitorMode? = null
            private set
        @Volatile var activeSettings: MonitorSettings? = null
            private set
        @Volatile var listening = false
        @Volatile var status = "未开启值守"
        @Volatile var currentDbfs = -120.0
        @Volatile var currentLevel = -30.0
    }
    private val alive = AtomicBoolean(false)
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { DailyReservation.cancel(this); alive.set(false); stopSelf(); return START_NOT_STICKY }
        if (alive.get()) return START_NOT_STICKY
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("monitor", "夜间监听", NotificationManager.IMPORTANCE_LOW))
            val mode = MonitorMode.fromValue(intent?.getStringExtra(EXTRA_MODE))
            activeMode=mode
            activeSettings=MonitorSettings.load(this)
            status = if(mode==MonitorMode.IMMEDIATE) "正在开启立即值守" else "正在启用每日预约"
            startForeground(1, notification(status), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            if(mode==MonitorMode.DAILY) DailyReservation.enable(this) else DailyReservation.cancel(this)
            wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NoiseRecorder:monitor").apply { acquire() }
            alive.set(true); running = true
            worker = Thread({ monitor(mode) }, "noise-audio").apply { start() }
        } catch (e: Exception) { status = "启动失败：${e.message}"; stopSelf() }
        return START_NOT_STICKY
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MonitorService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val daily=activeMode==MonitorMode.DAILY
        val choices=PendingIntent.getActivity(this,2,Intent(this,MainActivity::class.java).putExtra(DailyReservation.STOP_CHOICES,true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "monitor").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("夜间声证 · 值守 / 预约").setContentText(message).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, if(daily) "值守操作" else "停止值守", if(daily) choices else stop).build()).build()
    }
    private fun monitor(mode: MonitorMode) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val store = EvidenceStore(this)
        var recorder: AudioRecord? = null
        var wav: WavWriter? = null
        var peak = -120.0
        val tail = RecordingTail()
        var lastTrigger = Long.MIN_VALUE / 2
        var lastNotification = 0L
        var notificationText = ""
        var finishReason = "complete"
        var session: SessionLogger? = null
        fun closeRecording(reason: String) {
            val active = wav ?: return
            wav = null
            active.close()
            store.finish(active.file.name, peak, active.bytes / 32000.0, reason)
        }
        fun releaseMicrophone() {
            val active=recorder
            recorder=null
            listening=false
            try { active?.stop() } finally { active?.release() }
            currentDbfs=-120.0; currentLevel=-30.0
        }
        fun updateNotification() {
            val now=SystemClock.elapsedRealtime()
            if(status!=notificationText || now-lastNotification>=5000) {
                getSystemService(NotificationManager::class.java).notify(1,notification(status))
                notificationText=status; lastNotification=now
            }
        }
        try {
            store.recover()
            val settings = checkNotNull(activeSettings)
            val samples = ShortArray(1600) // 100 ms RMS window
            val ring = PreRoll()
            var lastStorageCheck = 0L
            var completedWindow = false
            fun insideWindow(): Boolean {
                val time=LocalTime.now()
                return mode.shouldListen(time.hour*60+time.minute,settings.start,settings.end) && (mode!=MonitorMode.DAILY || !DailyReservation.skipped(this))
            }
            while (alive.get()) {
                if(!insideWindow()) {
                    if(recorder!=null) {
                        val reason=if(DailyReservation.skipped(this)) "user_stop" else "window_end"
                        closeRecording(reason); session?.close(reason); session=null
                        releaseMicrophone(); ring.clear(); completedWindow=true
                    }
                    status=if(DailyReservation.skipped(this)) "本次值守已停止 · 保留每日预约，等待下次时段" else if(completedWindow) "本次预约已结束 · 已停止采集，等待下次每日时段" else "每日预约待命 · 等待已保存时段，未采集声音"
                    updateNotification()
                    SystemClock.sleep(500)
                    continue
                }
                if(recorder==null) {
                    val minimum = AudioRecord.getMinBufferSize(AudioLogic.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    check(minimum > 0) { "设备不支持 16 kHz 单声道录音" }
                    val created = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(AudioLogic.RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(maxOf(minimum * 2, 16000)).build()
                    recorder=created
                    check(created.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
                    created.startRecording()
                    check(created.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风无法开始采集" }
                    listening=true
                    ring.clear()
                }
                val activeRecorder=checkNotNull(recorder)
                val count = activeRecorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                check(count > 0) { "音频读取失败 ($count)，请检查麦克风权限/占用" }
                check(activeRecorder.activeRecordingConfiguration?.isClientSilenced != true) { "系统已静音麦克风（可能被通话或其他应用占用），请稍后重新开启" }
                val mono = SystemClock.elapsedRealtime()
                // Discard a read that straddles the scheduled end; next loop closes all resources.
                if(!insideWindow()) continue
                currentDbfs = AudioLogic.dbfs(samples, count)
                currentLevel = currentDbfs + settings.offset
                run {
                    val frameMs = count * 1000L / AudioLogic.RATE
                    val frameTime = System.currentTimeMillis() - frameMs
                    if (session == null) session = SessionLogger(store, settings, frameTime)
                    session!!.add(frameTime, frameMs, currentLevel)
                    val bytes = ByteArray(count * 2)
                    for (i in 0 until count) { bytes[i * 2] = samples[i].toByte(); bytes[i * 2 + 1] = (samples[i].toInt() shr 8).toByte() }
                    val triggered = currentLevel >= settings.threshold
                    // Keep WAV/ZIP sizes bounded without losing ongoing audio: split at 30 min.
                    val rollover = (wav?.bytes ?: 0L) >= 32000L * 1800
                    if (rollover) closeRecording("segment")
                    if (wav == null && (triggered || rollover)) {
                        check(store.directory.usableSpace > 32L * 1024 * 1024) { "存储空间不足 32 MB，已停止值守" }
                        val name = "noise_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))}_${java.util.UUID.randomUUID().toString().take(8)}.wav"
                        peak = currentLevel
                        store.begin(name, Instant.now().toString(), peak, settings, session!!.id)
                        wav = WavWriter(File(store.directory, name))
                        if (!rollover) wav!!.write(ring.snapshot())
                        if (!rollover) tail.extend(mono)
                        lastTrigger = Long.MIN_VALUE / 2
                    }
                    if (triggered && wav != null) {
                        tail.extend(mono)
                        // One event per second for sustained noise; every frame still extends the tail.
                        if (mono - lastTrigger >= 1000) { store.trigger(wav!!.file.name, currentLevel); lastTrigger = mono }
                    }
                    wav?.let { active -> active.write(bytes); peak = maxOf(peak, currentLevel) }
                    ring.add(bytes)
                    if (wav != null && tail.expired(mono)) closeRecording("complete")
                    val title=if(mode==MonitorMode.IMMEDIATE) "立即值守" else "预约时段监听"
                    status = if (wav == null) "$title · 等待声音触发" else "$title · 正在录音，最后触发后保留 60 秒"
                }
                if (mono - lastStorageCheck >= 5000) {
                    check(store.directory.usableSpace > 16L * 1024 * 1024) { "存储空间不足 16 MB，已停止值守并保存录音" }
                    lastStorageCheck=mono
                    wav?.let { store.finish(it.file.name, peak, it.bytes / 32000.0, "recording") }
                }
                updateNotification()
            }
            finishReason = "user_stop"
        } catch (e: Exception) { finishReason = "error"; status = "监听停止：${e.message}" }
        finally {
            try { closeRecording(finishReason) } catch (e: Exception) { status = "文件收尾失败：${e.message}" }
            try { session?.close(finishReason) } catch (e: Exception) { status = "声级记录收尾失败：${e.message}" }
            try { releaseMicrophone() } catch (_: Exception) { }
            store.close()
            alive.set(false); running = false; activeMode=null; activeSettings=null
            wake?.let { if (it.isHeld) it.release() }; wake = null
            if (finishReason == "user_stop") status = "已停止值守并取消预约，录音已保存"
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            if(mode==MonitorMode.DAILY) DailyReservation.notifyRestore(this)
        }
    }
    override fun onDestroy() {
        alive.set(false)
        // A blocking read completes every ~100 ms; the worker owns audio/file cleanup.
        if (worker == null) { wake?.let { if (it.isHeld) it.release() }; running = false; activeMode=null; activeSettings=null }
        super.onDestroy()
    }
}
