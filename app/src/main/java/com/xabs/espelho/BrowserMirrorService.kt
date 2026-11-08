package com.xabs.espelho

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.graphics.Point
import android.view.Surface
import android.os.SystemClock
import android.util.Log
import android.view.Display
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Transmissão A/V HLS sobre Wi-Fi local, sem conta, servidor externo ou JPEG polling.
 * HLS pode introduzir alguns segundos de latência; o navegador/firmware da TV precisa aceitar MPEG-TS HLS.
 */
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
        @Volatile var streamStatus: String = ""
            private set
        const val PORT = 8765
        const val FIXED_ACCESS_CODE = "750795"
        @Volatile var captureAuthorized: Boolean = false
            private set
        @Volatile var encodedFrames: Long = 0
            private set
        @Volatile var completedSegments: Int = 0
            private set
        @Volatile var lastVideoFrameElapsedMs: Long = 0
            private set
        @Volatile var capturePhase: String = "Parado"
            private set
    }

    private val started = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rotationWorker = Executors.newSingleThreadExecutor()
    private val videoChangeLock = Any()
    private var videoDrainFlag: AtomicBoolean? = null
    @Volatile private var videoWidth = 0
    @Volatile private var videoHeight = 0
    private val displayManager by lazy { getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) pollDisplaySize()
        }
    }
    private val orientationWatcher = object : Runnable {
        override fun run() {
            if (!closing.get()) {
                pollDisplaySize()
                mainHandler.postDelayed(this, 1200L)
            }
        }
    }
    private val mux = LiveTsMuxer()
    private val clients = Executors.newFixedThreadPool(5)
    private var projection: MediaProjection? = null
    private var screen: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var videoSurface: android.view.Surface? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecorder: AudioRecord? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var listener: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pin = FIXED_ACCESS_CODE

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || !started.compareAndSet(false, true)) return START_NOT_STICKY
        currentUrl = null
        lastError = null
        captureAuthorized = false
        encodedFrames = 0
        completedSegments = 0
        lastVideoFrameElapsedMs = 0
        capturePhase = "Iniciando serviço de captura"
        streamStatus = "Iniciando vídeo e áudio…"
        try {
            foregroundNotification()
            val token = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_PERMISSION_DATA, Intent::class.java)
            else intent.getParcelableExtra(EXTRA_PERMISSION_DATA)
            if (token == null || intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) != Activity.RESULT_OK) {
                error("Permissão de captura não recebida.")
            }
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val capture = manager.getMediaProjection(Activity.RESULT_OK, token)
                ?: error("O Android não liberou a captura.")
            projection = capture
            captureAuthorized = true
            capturePhase = "Permissão recebida; preparando tela"
            capture.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    // Android 14+ notifies the accurate captured content size.
                    if (Build.VERSION.SDK_INT >= 34) scheduleVideoResize(width, height)
                }
            }, mainHandler)
            startVideo(capture)
            startAudio(capture)
            startHttp()
            displayManager.registerDisplayListener(displayListener, mainHandler)
            mainHandler.postDelayed(orientationWatcher, 1200L)
            if (encodedFrames == 0L) capturePhase = "Aguardando primeiros quadros"
        } catch (e: Exception) {
            lastError = e.message ?: "Não foi possível iniciar a transmissão."
            capturePhase = "Falha ao iniciar captura"
            Log.e("EspelhoCapture", "Falha ao iniciar MediaProjection", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun foregroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel("espelho_local", "Espelhamento local", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, "espelho_local")
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle("Espelho")
            .setContentText("Transmitindo vídeo e áudio para sua rede local")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(41, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun fullScreenPixels(): Pair<Int, Int> {
        val point = Point()
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(point)
        if (point.x > 0 && point.y > 0) return point.x to point.y
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun videoDimensions(rawWidth: Int, rawHeight: Int): Pair<Int, Int> {
        if (rawWidth <= 0 || rawHeight <= 0) return 0 to 0
        val scale = minOf(1.0, 1280.0 / max(rawWidth, rawHeight))
        val width = max(2, ((rawWidth * scale).roundToInt() and -2))
        val height = max(2, ((rawHeight * scale).roundToInt() and -2))
        return width to height
    }

    private fun makeEncoder(width: Int, height: Int): Triple<MediaCodec, Surface, Int> {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            val refresh = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f
            val desired = refresh.roundToInt().coerceIn(24, 120)
            val capabilities = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            val fps = listOf(desired, 90, 60, 50, 30, 24).distinct()
                .filter { it <= desired }
                .firstOrNull { capabilities.areSizeAndRateSupported(width, height, it.toDouble()) } ?: 24
            val config = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_BIT_RATE, if (fps >= 50) 5_500_000 else 4_000_000)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(config, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            try {
                codec.start()
            } catch (e: Exception) {
                surface.release()
                throw e
            }
            return Triple(codec, surface, fps)
        } catch (e: Exception) {
            try { codec.release() } catch (_: Exception) {}
            throw e
        }
    }

    private fun startDrain(codec: MediaCodec) {
        val running = AtomicBoolean(true)
        videoDrainFlag = running
        videoThread = Thread({
            val info = MediaCodec.BufferInfo()
            var epochOffsetUs: Long? = null
            try {
                while (!closing.get() && running.get()) {
                    val index = codec.dequeueOutputBuffer(info, 15_000)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            fun csd(name: String): ByteArray? {
                                val src = format.getByteBuffer(name) ?: return null
                                return ByteArray(src.remaining()).also { src.get(it) }
                            }
                            mux.configureVideo(csd("csd-0"), csd("csd-1"))
                        }
                        index >= 0 -> {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val data = ByteArray(info.size)
                                val source = codec.getOutputBuffer(index)
                                if (source != null) {
                                    source.position(info.offset)
                                    source.limit(info.offset + info.size)
                                    source.get(data)
                                    if (epochOffsetUs == null) {
                                        epochOffsetUs = System.nanoTime() / 1000L - info.presentationTimeUs
                                    }
                                    val pts = info.presentationTimeUs + (epochOffsetUs ?: 0L)
                                    mux.addVideo(data, pts, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                                    encodedFrames++
                                    lastVideoFrameElapsedMs = SystemClock.elapsedRealtime()
                                    completedSegments = mux.segmentCount()
                                    capturePhase = if (completedSegments > 0)
                                        "Vídeo sendo codificado e segmentado" else
                                        "Vídeo capturado; preparando transmissão"
                                }
                            }
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!closing.get() && running.get()) {
                    lastError = "Codificação de vídeo interrompida: " + (e.message ?: "erro")
                    capturePhase = "Codificador interrompido"
                    Log.e("EspelhoCapture", "Falha no codificador de vídeo", e)
                    stopSelf()
                }
            }
        }, "espelho-video").apply { isDaemon = true; start() }
    }

    private fun startVideo(capture: MediaProjection) {
        val size = fullScreenPixels()
        val (width, height) = videoDimensions(size.first, size.second)
        val (codec, surface, fps) = makeEncoder(width, height)
        videoEncoder = codec
        videoSurface = surface
        videoWidth = width
        videoHeight = height
        screen = capture.createVirtualDisplay(
            "Espelho HLS", width, height, resources.configuration.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )
        streamStatus = "Vídeo H.264: " + width + "×" + height + " / alvo " + fps + " FPS"
        startDrain(codec)
    }

    private fun pollDisplaySize() {
        if (closing.get()) return
        val size = fullScreenPixels()
        scheduleVideoResize(size.first, size.second)
    }

    private fun scheduleVideoResize(rawWidth: Int, rawHeight: Int) {
        if (rawWidth <= 0 || rawHeight <= 0 || closing.get()) return
        val (width, height) = videoDimensions(rawWidth, rawHeight)
        if (width == videoWidth && height == videoHeight) return
        try {
            rotationWorker.execute {
                synchronized(videoChangeLock) {
                    if (closing.get() || width == videoWidth && height == videoHeight) return@synchronized
                    val virtualDisplay = screen ?: return@synchronized
                    try {
                        capturePhase = "Adaptando imagem à rotação da tela…"
                        // MediaProjection permits only ONE createVirtualDisplay per consent token.
                        // Resize that display and exchange the encoder surface instead of recreating it.
                        virtualDisplay.setSurface(null)
                        videoDrainFlag?.set(false)
                        videoThread?.join(750L)
                        try { videoEncoder?.stop() } catch (_: Exception) {}
                        try { videoEncoder?.release() } catch (_: Exception) {}
                        try { videoSurface?.release() } catch (_: Exception) {}
                        videoEncoder = null
                        videoSurface = null
                        mux.videoFormatChanged()
                        val (nextCodec, nextSurface, fps) = makeEncoder(width, height)
                        videoEncoder = nextCodec
                        videoSurface = nextSurface
                        virtualDisplay.resize(width, height, resources.configuration.densityDpi)
                        virtualDisplay.setSurface(nextSurface)
                        videoWidth = width
                        videoHeight = height
                        streamStatus = "Vídeo H.264: " + width + "×" + height + " / alvo " + fps + " FPS"
                        startDrain(nextCodec)
                        capturePhase = "Rotação aplicada; retomando o vídeo"
                    } catch (e: Exception) {
                        lastError = "Falha ao adaptar orientação: " + (e.message ?: "erro")
                        Log.e("EspelhoCapture", "Falha ao trocar superfície de captura", e)
                        stopSelf()
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private fun startAudio(capture: MediaProjection) {
        if (Build.VERSION.SDK_INT < 29) {
            streamStatus += "\nÁudio interno: exige Android 10 ou superior."
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            streamStatus += "\nÁudio interno indisponível: falta permissão."
            return
        }
        try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val audioCapture = AudioPlaybackCaptureConfiguration.Builder(capture)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val recorder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(audioCapture)
                .setBufferSizeInBytes(32_768)
                .build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) error("Não foi possível abrir a captura de áudio.")
            audioRecorder = recorder
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder = codec
            val encodeFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            }
            codec.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            recorder.startRecording()
            streamStatus += "\nÁudio AAC: ativado (somente apps que autorizam captura)"
            audioThread = Thread({
                val pcm = ByteArray(4096)
                val info = MediaCodec.BufferInfo()
                var bytesSent = 0L
                val startUs = System.nanoTime() / 1000L
                try {
                    while (!closing.get()) {
                        val n = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                        if (n <= 0) continue
                        val inputIndex = codec.dequeueInputBuffer(20_000)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex)
                            if (buffer != null) {
                                buffer.clear()
                                val amount = minOf(n, buffer.remaining())
                                buffer.put(pcm, 0, amount)
                                val pts = startUs + (bytesSent / 4L) * 1_000_000L / 48_000L
                                codec.queueInputBuffer(inputIndex, 0, amount, pts, 0)
                                bytesSent += amount
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                            }
                        }
                        while (!closing.get()) {
                            val index = codec.dequeueOutputBuffer(info, 0)
                            if (index < 0) break
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val data = ByteArray(info.size)
                                val out = codec.getOutputBuffer(index)
                                if (out != null) {
                                    out.position(info.offset)
                                    out.limit(info.offset + info.size)
                                    out.get(data)
                                    mux.addAudio(data, info.presentationTimeUs)
                                }
                            }
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                } catch (e: Exception) {
                    if (!closing.get()) streamStatus += "\nErro no áudio: " + (e.message ?: "falha")
                }
            }, "espelho-audio").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            streamStatus += "\nÁudio interno indisponível: " + (e.message ?: "falha ao iniciar")
        }
    }

    private fun startHttp() {
        val address = wifiIp() ?: error("Conecte o tablet ao Wi-Fi.")
        val socket = ServerSocket(PORT, 20, InetAddress.getByName(address))
        listener = socket
        currentUrl = "http://" + address + ":" + PORT + "/" + pin
        acceptThread = Thread({
            while (!closing.get()) {
                try {
                    val client = socket.accept()
                    clients.execute { handle(client) }
                } catch (_: Exception) {
                    break
                }
            }
        }, "espelho-http").apply { isDaemon = true; start() }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { client ->
                client.soTimeout = 3500
                val input = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                val request = input.readLine() ?: return
                for (i in 0 until 40) if (input.readLine().isNullOrEmpty()) break
                val route = request.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
                val root = "/" + pin
                val output = client.getOutputStream()
                when {
                    route == root || route == root + "/" ->
                        respond(output, 200, "text/html; charset=utf-8", html(root).toByteArray(Charsets.UTF_8))
                    route == root + "/live.m3u8" ->
                        respond(output, 200, "application/vnd.apple.mpegurl", mux.playlist(root).toByteArray(Charsets.UTF_8))
                    route == root + "/player.js" ->
                        respond(output, 200, "application/javascript; charset=utf-8", assets.open("player.js").use { it.readBytes() })
                    route == root + "/hls.min.js" ->
                        respond(output, 200, "application/javascript; charset=utf-8", assets.open("hls.min.js").use { it.readBytes() })
                    route == root + "/live-status" -> {
                        val count = mux.segmentCount()
                        val latest = mux.latestSegmentId()
                        val json = "{\"ready\":" + (count >= 2) +
                            ",\"segments\":" + count +
                            ",\"frames\":" + encodedFrames +
                            ",\"latest\":" + latest + "}"
                        respond(output, 200, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
                    }
                    route.startsWith(root + "/segment-") && route.endsWith(".ts") -> {
                        val id = route.removePrefix(root + "/segment-").removeSuffix(".ts").toIntOrNull()
                        val bytes = id?.let { mux.getSegment(it) }
                        if (bytes == null) respond(output, 404, "text/plain", "Segmento indisponível.".toByteArray())
                        else respond(output, 200, "video/mp2t", bytes)
                    }
                    else -> respond(output, 404, "text/plain", "Link inválido.".toByteArray(Charsets.UTF_8))
                }
            }
        } catch (_: Exception) {
            // A TV pode interromper uma requisição sem encerrar a sessão.
        }
    }

    private fun respond(output: OutputStream, status: Int, type: String, data: ByteArray) {
        val reason = if (status == 200) "OK" else "Not Found"
        val header = "HTTP/1.1 " + status + " " + reason + "\r\n" +
            "Content-Type: " + type + "\r\nContent-Length: " + data.size +
            "\r\nCache-Control: no-store, no-cache, must-revalidate\r\nPragma: no-cache\r\nExpires: 0\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(data)
        output.flush()
    }

    /** Player entregue por arquivos locais no APK, sem dependência de CDN na TV. */
    private fun html(root: String): String =
        assets.open("player.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
            .replace("__ROOT__", root)

    private fun wifiIp(): String? {
        val it = NetworkInterface.getNetworkInterfaces() ?: return null
        while (it.hasMoreElements()) {
            val adapter = it.nextElement()
            if (!adapter.isUp || adapter.isLoopback || !adapter.name.lowercase().startsWith("wlan")) continue
            val addresses = adapter.inetAddresses
            while (addresses.hasMoreElements()) {
                val ip = addresses.nextElement()
                if (ip is Inet4Address && !ip.isLoopbackAddress) return ip.hostAddress
            }
        }
        return null
    }

    override fun onDestroy() {
        closing.set(true)
        mainHandler.removeCallbacks(orientationWatcher)
        try { displayManager.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
        rotationWorker.shutdownNow()
        currentUrl = null
        captureAuthorized = false
        capturePhase = if (lastError == null) "Encerrada" else "Falha encerrada"
        try { listener?.close() } catch (_: Exception) {}
        try { audioRecorder?.stop() } catch (_: Exception) {}
        try { screen?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        synchronized(videoChangeLock) {
            videoDrainFlag?.set(false)
            try { videoThread?.join(750) } catch (_: Exception) {}
            try { videoEncoder?.stop() } catch (_: Exception) {}
            try { videoEncoder?.release() } catch (_: Exception) {}
        }
        try { audioThread?.join(250) } catch (_: Exception) {}
        try { audioEncoder?.stop() } catch (_: Exception) {}
        try { audioEncoder?.release() } catch (_: Exception) {}
        try { audioRecorder?.release() } catch (_: Exception) {}
        try { videoSurface?.release() } catch (_: Exception) {}
        clients.shutdownNow()
        super.onDestroy()
    }
}
