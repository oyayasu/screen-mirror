package com.xabs.espelho

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/** Alternativa experimental: captura JPEG acessível pelo navegador da TV, sem áudio. */
class BrowserMirrorService : Service() {
    companion object {
        const val ACTION_START = "com.xabs.espelho.START_BROWSER_MIRROR"
        const val ACTION_STOP = "com.xabs.espelho.STOP_BROWSER_MIRROR"
        const val EXTRA_PERMISSION_DATA = "permission_data"
        const val EXTRA_RESULT_CODE = "result_code"
        @Volatile var currentUrl: String? = null
            private set
        @Volatile var lastError: String? = null
            private set
    }

    private val running = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val clients = Executors.newFixedThreadPool(3)
    @Volatile private var frame: ByteArray? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var imageWorker: HandlerThread? = null
    private var server: ServerSocket? = null
    private var pin = ""
    private var lastFrameAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || !running.compareAndSet(false, true)) return START_NOT_STICKY
        currentUrl = null
        lastError = null
        try {
            startNotification()
            val data = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_PERMISSION_DATA, Intent::class.java)
            else intent.getParcelableExtra(EXTRA_PERMISSION_DATA)
            if (data == null || intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) != Activity.RESULT_OK)
                error("Permissão de captura não recebida.")
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = manager.getMediaProjection(Activity.RESULT_OK, data)
                ?: error("O Android não liberou a captura.")
            projection = mediaProjection
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(Looper.getMainLooper()))
            beginCapture(mediaProjection)
            beginServer()
        } catch (e: Exception) {
            lastError = e.message ?: "Erro ao iniciar."
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel("espelho_local", "Espelhamento local", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "espelho_local")
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle("Espelho")
            .setContentText("Transmitindo a tela na rede local")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(41, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(41, n)
    }

    private fun beginCapture(mp: MediaProjection) {
        val metrics = resources.displayMetrics
        val scale = minOf(1f, 960f / max(metrics.widthPixels, metrics.heightPixels).toFloat())
        val width = max(2, (metrics.widthPixels * scale).roundToInt() and -2)
        val height = max(2, (metrics.heightPixels * scale).roundToInt() and -2)
        val source = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = source
        val worker = HandlerThread("espelho-frames")
        worker.start()
        imageWorker = worker
        source.setOnImageAvailableListener({ producer ->
            val image = try { producer.acquireLatestImage() } catch (_: Exception) { null }
            if (image != null) {
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFrameAt >= 240 && !closing.get()) {
                        lastFrameAt = now
                        val p = image.planes[0]
                        val paddedWidth = image.width + (p.rowStride - p.pixelStride * image.width) / p.pixelStride
                        val bitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.copyPixelsFromBuffer(p.buffer)
                            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                            try {
                                val out = ByteArrayOutputStream(150_000)
                                cropped.compress(Bitmap.CompressFormat.JPEG, 66, out)
                                frame = out.toByteArray()
                            } finally { if (cropped !== bitmap) cropped.recycle() }
                        } finally { bitmap.recycle() }
                    }
                } catch (_: Exception) {
                    // Frames perdidos são normais durante mudanças de orientação.
                } finally { image.close() }
            }
        }, Handler(worker.looper))
        display = mp.createVirtualDisplay(
            "Espelho LAN", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, source.surface, null, null
        )
    }

    private fun beginServer() {
        val ip = wifiIp() ?: error("É necessário conectar o tablet ao Wi-Fi.")
        pin = (100000 + SecureRandom().nextInt(900000)).toString()
        val listener = ServerSocket(8765, 10, InetAddress.getByName(ip))
        server = listener
        currentUrl = "http://$ip:8765/$pin"
        Thread({
            while (!closing.get()) {
                try {
                    val client = listener.accept()
                    clients.execute { respond(client) }
                } catch (_: Exception) {
                    break
                }
            }
        }, "espelho-http").apply { isDaemon = true; start() }
    }

    private fun respond(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 3000
                val input = s.getInputStream().bufferedReader(Charsets.US_ASCII)
                val request = input.readLine() ?: return
                for (i in 0 until 40) { if (input.readLine().isNullOrEmpty()) break }
                val path = request.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
                val base = "/$pin"
                val output = s.getOutputStream()
                when (path) {
                    base, "$base/" -> send(output, 200, "text/html; charset=utf-8", html(base).toByteArray(Charsets.UTF_8))
                    "$base/frame" -> {
                        val current = frame
                        if (current == null) send(output, 503, "text/plain", "Aguardando imagem".toByteArray())
                        else send(output, 200, "image/jpeg", current)
                    }
                    else -> send(output, 404, "text/plain", "Link invalido".toByteArray())
                }
            }
        } catch (_: Exception) {}
    }

    private fun send(out: OutputStream, status: Int, mime: String, data: ByteArray) {
        val reason = if (status == 200) "OK" else if (status == 503) "Service Unavailable" else "Not Found"
        val size = data.size
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: $mime\r\nContent-Length: $size\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.flush()
    }

    private fun html(base: String): String = """
        <!doctype html><html lang="pt-br"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Espelho</title><style>
        html,body{margin:0;width:100%;height:100%;background:#080808;color:#eee;font:16px sans-serif}
        body{display:flex;align-items:center;justify-content:center}
        img{max-width:100vw;max-height:100vh;object-fit:contain}
        #hint{position:absolute;bottom:12px;text-align:center;color:#aaa}
        </style></head><body><img id="screen" alt="Aguardando imagem">
        <div id="hint">Espelho • transmissão local sem áudio</div><script>
        var screenEl=document.getElementById('screen'),hint=document.getElementById('hint');
        function reload(){
          var image=new Image();
          image.onload=function(){screenEl.src=image.src;hint.style.display='none';setTimeout(reload,240)};
          image.onerror=function(){setTimeout(reload,800)};
          image.src='$base/frame?t='+Date.now();
        }
        reload();
        </script></body></html>
    """.trimIndent()

    private fun wifiIp(): String? {
        val all = NetworkInterface.getNetworkInterfaces() ?: return null
        while (all.hasMoreElements()) {
            val adapter = all.nextElement()
            if (!adapter.isUp || adapter.isLoopback || !adapter.name.lowercase().startsWith("wlan")) continue
            val ips = adapter.inetAddresses
            while (ips.hasMoreElements()) {
                val ip = ips.nextElement()
                if (ip is Inet4Address && !ip.isLoopbackAddress) return ip.hostAddress
            }
        }
        return null
    }

    override fun onDestroy() {
        closing.set(true)
        currentUrl = null
        try { server?.close() } catch (_: Exception) {}
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { imageWorker?.quitSafely() } catch (_: Exception) {}
        clients.shutdownNow()
        super.onDestroy()
    }
}