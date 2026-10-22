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
        @Volatile var running = false
        @Volatile var status = "未开启值守"
        @Volatile var currentDbfs = -120.0
        @Volatile var currentLevel = -30.0
    }
    private val alive = AtomicBoolean(false)
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { alive.set(false); stopSelf(); return START_NOT_STICKY }
        if (alive.get()) return START_NOT_STICKY
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("monitor", "夜间监听", NotificationManager.IMPORTANCE_LOW))
            startForeground(1, notification("正在开启麦克风值守"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NoiseRecorder:monitor").apply { acquire() }
            alive.set(true); running = true
            worker = Thread({ monitor() }, "noise-audio").apply { start() }
        } catch (e: Exception) { status = "启动失败：${e.message}"; stopSelf() }
        return START_NOT_STICKY
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MonitorService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "monitor").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("夜间声证 · 值守中").setContentText(message).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止值守", stop).build()).build()
    }
    private fun monitor() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val store = EvidenceStore(this)
        var recorder: AudioRecord? = null
        var wav: WavWriter? = null
        var peak = -120.0
        val tail = RecordingTail()
        var lastTrigger = Long.MIN_VALUE / 2
        var lastNotification = 0L
        var finishReason = "complete"
        var session: SessionLogger? = null
        fun closeRecording(reason: String) {
            val active = wav ?: return
            wav = null
            active.close()
            store.finish(active.file.name, peak, active.bytes / 32000.0, reason)
        }
        try {
            store.recover()
            val settings = MonitorSettings.load(this)
            val minimum = AudioRecord.getMinBufferSize(AudioLogic.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "设备不支持 16 kHz 单声道录音" }
            recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(AudioLogic.RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, 16000)).build()
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风无法开始采集" }
            val samples = ShortArray(1600) // 100 ms RMS window
            val ring = PreRoll()
            var wasInside = false
            while (alive.get()) {
                val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                check(count > 0) { "音频读取失败 ($count)，请检查麦克风权限/占用" }
                check(recorder.activeRecordingConfiguration?.isClientSilenced != true) { "系统已静音麦克风（可能被通话或其他应用占用），请稍后重新开启" }
                val mono = SystemClock.elapsedRealtime()
                val time = LocalTime.now()
                val inside = AudioLogic.inWindow(time.hour * 60 + time.minute, settings.start, settings.end)
                currentDbfs = AudioLogic.dbfs(samples, count)
                currentLevel = currentDbfs + settings.offset
                if (!inside) {
                    if (wasInside) { closeRecording("window_end"); session?.close("window_end"); session = null; ring.clear() }
                    status = "定时待命 · 非监听时段不保存声音"
                } else {
                    if (!wasInside) ring.clear()
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
                    status = if (wav == null) "监听中 · 等待声音触发" else "正在录音 · 最后一次超阈值后保留 60 秒"
                }
                wasInside = inside
                if (mono - lastNotification >= 5000) {
                    check(store.directory.usableSpace > 16L * 1024 * 1024) { "存储空间不足 16 MB，已停止值守并保存录音" }
                    getSystemService(NotificationManager::class.java).notify(1, notification(status)); lastNotification = mono
                    wav?.let { store.finish(it.file.name, peak, it.bytes / 32000.0, "recording") }
                }
            }
            finishReason = "user_stop"
        } catch (e: Exception) { finishReason = "error"; status = "监听停止：${e.message}" }
        finally {
            try { closeRecording(finishReason) } catch (e: Exception) { status = "文件收尾失败：${e.message}" }
            try { session?.close(finishReason) } catch (e: Exception) { status = "声级记录收尾失败：${e.message}" }
            try { recorder?.stop() } catch (_: Exception) { }
            recorder?.release(); store.close()
            alive.set(false); running = false
            wake?.let { if (it.isHeld) it.release() }; wake = null
            if (finishReason == "user_stop") status = "已停止值守，录音已保存"
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    override fun onDestroy() {
        alive.set(false)
        // A blocking read completes every ~100 ms; the worker owns audio/file cleanup.
        if (worker == null) { wake?.let { if (it.isHeld) it.release() }; running = false }
        super.onDestroy()
    }
}
