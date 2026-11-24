package com.example.noiserecorder

import android.app.Activity
import android.app.AlertDialog
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.util.Locale

/** In-app playback owns only a read-only source; dismissal releases decoder and callbacks. */
class RecordingPlayer(private val activity: Activity) {
    private val handler=Handler(Looper.getMainLooper())
    private var player: MediaPlayer?=null
    private var dialog: AlertDialog?=null
    private var ready=false
    private var dragging=false
    fun close() {
        handler.removeCallbacksAndMessages(null)
        ready=false
        player?.release(); player=null
        val current=dialog; dialog=null
        current?.dismiss()
    }
    fun show(file: File) {
        close()
        check(file.isFile && file.length()>44) { "录音文件不存在或没有有效音频" }
        val padding=(20*activity.resources.displayMetrics.density).toInt()
        val layout=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(padding,padding,padding,padding) }
        layout.addView(TextView(activity).apply { text=file.name; textSize=13f; setTextIsSelectable(true) })
        val clock=TextView(activity).apply { text="正在加载…"; textSize=16f; setPadding(0,padding/2,0,0) }
        val seek=SeekBar(activity).apply { isEnabled=false; contentDescription="播放进度" }
        val toggle=Button(activity).apply { text="正在加载…"; isEnabled=false; minHeight=(48*activity.resources.displayMetrics.density).toInt() }
        layout.addView(clock); layout.addView(seek)
        layout.addView(toggle,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
        val window=AlertDialog.Builder(activity).setTitle("播放录音").setView(layout).setNegativeButton("关闭") { _, _ -> close() }.create()
        dialog=window
        window.setOnDismissListener { close() }
        window.show()
        fun time(ms: Int)=String.format(Locale.US,"%02d:%02d",ms/60000,(ms/1000)%60)
        fun update() {
            val active=player ?: return
            if(!ready) return
            if(!dragging) seek.progress=active.currentPosition
            clock.text="${time(seek.progress)} / ${time(active.duration)}"
            toggle.text=if(active.isPlaying) "暂停" else "播放"
        }
        val progress=object:Runnable { override fun run() {
            if(!ready || player==null) return
            update(); handler.postDelayed(this,1000)
        } }
        try {
            val active=MediaPlayer()
            player=active
            active.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            active.setOnPreparedListener {
                if(player!==it) return@setOnPreparedListener
                ready=true; seek.max=it.duration; seek.isEnabled=true; toggle.isEnabled=true
                it.start(); handler.post(progress)
            }
            active.setOnCompletionListener { handler.removeCallbacks(progress); seek.progress=it.duration; clock.text="${time(it.duration)} / ${time(it.duration)}"; toggle.text="播放" }
            active.setOnErrorListener { _, _, _ ->
                ready=false; handler.removeCallbacksAndMessages(null)
                clock.text="无法播放此录音，文件可能损坏或格式不受支持"
                toggle.isEnabled=false; seek.isEnabled=false
                player?.release(); player=null
                true
            }
            toggle.setOnClickListener {
                val current=player
                if(ready && current!=null) {
                    if(current.isPlaying) current.pause() else { if(current.currentPosition>=current.duration) current.seekTo(0); current.start(); handler.removeCallbacks(progress); handler.post(progress) }
                    update()
                }
            }
            seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { dragging=true }
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) { if(fromUser && ready) clock.text="${time(value)} / ${time(player?.duration ?: 0)}" }
                override fun onStopTrackingTouch(bar: SeekBar) { if(ready) player?.seekTo(bar.progress); dragging=false }
            })
            // Local originals are read directly; no external player or URI handler is required.
            file.inputStream().use { active.setDataSource(it.fd) }
            active.prepareAsync()
        } catch(e: Exception) { close(); throw e }
    }
}