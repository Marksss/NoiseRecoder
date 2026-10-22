package com.example.noiserecorder

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/** User-started, temporary loopback preview. No LAN binding, uploads, or external resources. */
class ReportPreviewService : Service() {
    companion object { @Volatile var url: String? = null; @Volatile var error: String? = null }
    private var server: ServerSocket? = null
    private val clients = Executors.newFixedThreadPool(3)
    private val timer = Handler(Looper.getMainLooper())
    private val expire = Runnable { stopSelf() }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        server?.close(); url=null; error=null
        try {
            val base=File(filesDir,"reports").canonicalFile
            val name=requireNotNull(intent?.getStringExtra("report"))
            val root=File(base,name).canonicalFile
            require(root.parentFile == base && File(root,"READY").isFile)
            val nm=getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("reports","本地报告预览",NotificationManager.IMPORTANCE_LOW))
            val stop=PendingIntent.getService(this,3,Intent(this,ReportPreviewService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
            val open=PendingIntent.getActivity(this,4,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
            val notification=Notification.Builder(this,"reports").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("本地报告预览").setContentText("仅本机浏览器可访问，10 分钟后自动关闭").setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"关闭预览",stop).build()).build()
            startForeground(2,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            val token=UUID.randomUUID().toString()
            val listener=ServerSocket(0,8,InetAddress.getByName("127.0.0.1")); server=listener
            url="http://127.0.0.1:${listener.localPort}/$token/report.html"
            Thread({
                while(!listener.isClosed) try {
                    val client=listener.accept()
                    try { clients.execute { respond(client,root,token) } } catch (_: Exception) { client.close() }
                } catch (_: Exception) { break }
            },"report-loopback").start()
            timer.removeCallbacks(expire); timer.postDelayed(expire,10*60*1000L)
        } catch(e:Exception) { error=e.message ?: "无法启动预览"; stopSelf() }
        return START_NOT_STICKY
    }
    private fun respond(socket: Socket, root: File, token: String) {
        socket.use { s ->
            try {
                s.soTimeout=10000
                val reader=s.getInputStream().bufferedReader(Charsets.US_ASCII)
                fun line(): String? { val b=StringBuilder(); while(b.length < 8192) { val ch=reader.read(); if(ch<0) return if(b.isEmpty()) null else b.toString(); if(ch==10) return b.toString().trimEnd('\r'); b.append(ch.toChar()) }; error("Header too long") }
                val parts=(line() ?: return).split(' ')
                val headers=mutableMapOf<String,String>()
                var headerCount=0
                while(true) { val h=line() ?: break; if(h.isEmpty()) break; require(++headerCount<=40); val colon=h.indexOf(':'); if(colon>0) headers[h.substring(0,colon).lowercase(java.util.Locale.ROOT)]=h.substring(colon+1).trim() }
                val output=s.getOutputStream().buffered()
                fun response(code:String,extra:String="",length:Long=0) { output.write(("HTTP/1.1 $code\r\nContent-Length: $length\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n$extra\r\n").toByteArray(Charsets.US_ASCII)) }
                if(parts.size<2 || parts[0] !in setOf("GET","HEAD") || !parts[1].startsWith("/$token/")) { response("404 Not Found"); output.flush(); return }
                val relative=parts[1].substring(token.length+2).substringBefore('?')
                if(relative != "report.html" && !Regex("audio/[A-Za-z0-9_.-]+\\.wav").matches(relative)) { response("404 Not Found"); output.flush(); return }
                val file=File(root,relative).canonicalFile
                if(!file.toPath().startsWith(root.toPath()) || !file.isFile) { response("404 Not Found"); output.flush(); return }
                val size=file.length(); var start=0L; var end=size-1; var partial=false
                headers["range"]?.let { range ->
                    val match=Regex("bytes=(\\d*)-(\\d*)").matchEntire(range)
                    if(match == null || size==0L || match.groupValues.drop(1).all { it.isEmpty() }) { response("416 Range Not Satisfiable","Content-Range: bytes */$size\r\n"); output.flush(); return }
                    val first=match.groupValues[1]; val last=match.groupValues[2]
                    if(first.isEmpty()) { val suffix=last.toLongOrNull() ?: 0; start=maxOf(0,size-suffix) }
                    else { start=first.toLongOrNull() ?: size; if(last.isNotEmpty()) end=minOf(end,last.toLongOrNull() ?: -1) }
                    if(start !in 0 until size || end<start) { response("416 Range Not Satisfiable","Content-Range: bytes */$size\r\n"); output.flush(); return }
                    partial=true
                }
                val type=if(relative.endsWith(".wav")) "audio/wav" else "text/html; charset=utf-8"
                val extra="Content-Type: $type\r\nAccept-Ranges: bytes\r\nContent-Security-Policy: default-src 'none'; img-src data:; media-src 'self' data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\r\n" + if(partial) "Content-Range: bytes $start-$end/$size\r\n" else ""
                response(if(partial) "206 Partial Content" else "200 OK",extra,end-start+1)
                if(parts[0]=="GET") RandomAccessFile(file,"r").use { input -> input.seek(start); val buffer=ByteArray(65536); var remaining=end-start+1; while(remaining>0) { val n=input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt()); if(n<0) break; output.write(buffer,0,n); remaining-=n } }
                output.flush()
            } catch (_: Exception) { }
        }
    }
    override fun onDestroy() { timer.removeCallbacks(expire); server?.close(); clients.shutdownNow(); url=null; stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
}
