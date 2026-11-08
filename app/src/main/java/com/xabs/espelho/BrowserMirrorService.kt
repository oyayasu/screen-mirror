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
import android.graphics.SurfaceTexture
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
            }, Handler(Looper.getMainLooper()))
            startVideo(capture)
            startAudio(capture)
            startHttp()
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

    private fun startVideo(capture: MediaProjection) {
        val metrics = resources.displayMetrics
        val maxDimension = max(metrics.widthPixels, metrics.heightPixels)
        // Escala para a capacidade usual de codificadores de tablets: não descarta quadros manualmente.
        val scale = minOf(1.0f, 1280f / maxDimension)
        val width = max(2, ((metrics.widthPixels * scale).roundToInt() and -2))
        val height = max(2, ((metrics.heightPixels * scale).roundToInt() and -2))
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder = codec
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
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
        videoSurface = surface
        codec.start()
        screen = capture.createVirtualDisplay(
            "Espelho HLS", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )
        streamStatus = "Vídeo H.264: " + width + "×" + height + " / alvo " + fps + " FPS"
        videoThread = Thread({
            val info = MediaCodec.BufferInfo()
            // MediaCodec implementations may report surface timestamps in different epochs.
            // Anchor video frames to the same monotonic clock used by AudioRecord.
            var videoEpochOffsetUs: Long? = null
            try {
                while (!closing.get()) {
                    val index = codec.dequeueOutputBuffer(info, 15_000)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            fun csd(name: String): ByteArray? {
                                val src = format.getByteBuffer(name) ?: return null
                                val copy = ByteArray(src.remaining())
                                src.get(copy)
                                return copy
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
                                    if (videoEpochOffsetUs == null) {
                                        videoEpochOffsetUs = System.nanoTime() / 1000L - info.presentationTimeUs
                                    }
                                    val synchronizedPtsUs = info.presentationTimeUs + (videoEpochOffsetUs ?: 0L)
                                    mux.addVideo(data, synchronizedPtsUs,
                                        info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
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
                if (!closing.get()) {
                    lastError = "Codificação de vídeo interrompida: " + (e.message ?: "erro")
                    capturePhase = "Codificador interrompido"
                    Log.e("EspelhoCapture", "Falha no codificador de vídeo", e)
                    stopSelf()
                }
            }
        }, "espelho-video").apply { isDaemon = true; start() }
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
                    route == root + "/live-status" -> {
                        val count = mux.segmentCount()
                        val latest = mux.latestSegmentId()
                        val json = "{\"ready\":" + (count >= 3) + ",\"segments\":" + count +
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

    private fun html(root: String): String = """
        <!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Espelho — ao vivo</title>
        <style>
        html,body{margin:0;background:#09090e;color:#eee;font:17px Arial,sans-serif}
        main{min-height:100vh;display:flex;justify-content:center;align-items:center;flex-direction:column;gap:12px}
        video{display:block;width:100vw;max-height:89vh;object-fit:contain;background:#09090e}
        button{border:0;border-radius:14px;padding:15px 25px;font-size:18px;background:#dcd7fa;color:#252238}
        #live{position:absolute;top:12px;left:12px;border-radius:16px;background:#aa3138;color:white;padding:6px 12px;font-size:14px;font-weight:bold;display:none}
        #hint{color:#aaa;padding:8px 16px;text-align:center}
        #sync{display:none;background:#37334b;color:white}
        #fallback{color:#cac4ff;padding:8px;text-align:center;font-size:14px}
        </style></head><body><main>
        <div id="live">AO VIVO</div>
        <video id="v" playsinline preload="none" autoplay></video>
        <button id="start" type="button">Iniciar ao vivo</button>
        <button id="sync" type="button">Voltar ao vivo</button>
        <small id="hint">Preparando vídeo e áudio ao vivo…</small>
        <a id="fallback" href="$root/live.m3u8">Abrir no player nativo da TV</a>
        </main><script>
        (function(){
          var video=document.getElementById('v');
          var start=document.getElementById('start');
          var resync=document.getElementById('sync');
          var hint=document.getElementById('hint');
          var live=document.getElementById('live');
          var ready=false, started=false, latest=-1, lastLatest=-1, stalePolls=0;
          var currentRange=-1, staleRangePolls=0;
          var sourceUrl='$root/live.m3u8';

          function setHint(message){hint.innerHTML=message;}
          function edge(){
            try{
              if(video.seekable && video.seekable.length>0) return video.seekable.end(video.seekable.length-1);
              if(video.buffered && video.buffered.length>0) return video.buffered.end(video.buffered.length-1);
            }catch(e){}
            return -1;
          }
          function goLive(){
            var end=edge();
            if(end>0){
              var target=Math.max(0,end-1.5);
              if(!isNaN(video.currentTime) && end-video.currentTime>3.5){
                try{video.currentTime=target;}catch(e){}
              }
            }
          }
          function playVideo(){
            try{
              var result=video.play();
              if(result && result.then){
                result.then(function(){
                  start.style.display='none'; live.style.display='block'; resync.style.display='inline-block';
                  setHint('Transmissão ao vivo');
                  goLive();
                },function(){
                  setHint('Toque em Iniciar ao vivo para liberar a reprodução com som.');
                  start.style.display='inline-block';
                });
              }else{
                start.style.display='none'; live.style.display='block'; resync.style.display='inline-block';
                goLive();
              }
            }catch(e){
              start.style.display='inline-block';
              setHint('O navegador da TV bloqueou o início automático.');
            }
          }
          function loadStream(){
            if(!ready) return;
            started=true;
            try{
              video.pause();
              video.removeAttribute('src');
              video.load();
              video.src=sourceUrl+'?session='+Date.now();
              video.load();
              playVideo();
            }catch(e){
              setHint('Não foi possível iniciar HLS no navegador desta TV.');
            }
          }
          function status(){
            var req=new XMLHttpRequest();
            req.onreadystatechange=function(){
              if(req.readyState!==4) return;
              if(req.status===200){
                try{
                  var data=JSON.parse(req.responseText);
                  ready=!!data.ready;
                  latest=data.latest;
                  if(ready && !started){
                    setHint('Transmissão pronta. Toque em Iniciar ao vivo.');
                    // Tenta iniciar normalmente, mas mantém o botão para TVs que exigem gesto.
                    loadStream();
                  }
                  if(started && ready){
                    goLive();
                    var end=edge();
                    if(end>=0 && end>currentRange+0.4){
                      currentRange=end; staleRangePolls=0;
                    }else if(latest!==lastLatest && end>=0){staleRangePolls++;}
                    if(staleRangePolls>=7){
                      staleRangePolls=0;
                      setHint('Reaproximando a transmissão ao vivo…');
                      loadStream();
                    }
                  }
                  lastLatest=latest;
                }catch(e){}
              } else if(started) {
                setHint('Conexão local indisponível. Verifique o tablet.');
              }
            };
            req.open('GET','$root/live-status?ts='+Date.now(),true);
            req.send(null);
          }
          start.onclick=function(){if(ready){if(!started)loadStream();else playVideo();}
            else setHint('Aguardando os primeiros segundos de transmissão no tablet…');};
          resync.onclick=function(){goLive();if(!video.seekable || !video.seekable.length)loadStream();};
          video.addEventListener('loadedmetadata',function(){goLive();});
          video.addEventListener('timeupdate',function(){goLive();});
          video.addEventListener('playing',function(){
            start.style.display='none';live.style.display='block';resync.style.display='inline-block';
          });
          video.addEventListener('ended',function(){setHint('Retomando ao vivo…');loadStream();});
          video.addEventListener('error',function(){
            setHint('O navegador não conseguiu reproduzir HLS. Tente o player nativo da TV.');
            start.style.display='inline-block';live.style.display='none';
          });
          setInterval(status,1600);
          setInterval(function(){if(started){goLive();}},1200);
          status();
        })();
        </script></body></html>
    """.trimIndent()

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
        currentUrl = null
        captureAuthorized = false
        capturePhase = if (lastError == null) "Encerrada" else "Falha encerrada"
        try { listener?.close() } catch (_: Exception) {}
        try { audioRecorder?.stop() } catch (_: Exception) {}
        try { screen?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { videoThread?.join(250) } catch (_: Exception) {}
        try { audioThread?.join(250) } catch (_: Exception) {}
        try { videoEncoder?.stop() } catch (_: Exception) {}
        try { videoEncoder?.release() } catch (_: Exception) {}
        try { audioEncoder?.stop() } catch (_: Exception) {}
        try { audioEncoder?.release() } catch (_: Exception) {}
        try { audioRecorder?.release() } catch (_: Exception) {}
        try { videoSurface?.release() } catch (_: Exception) {}
        clients.shutdownNow()
        super.onDestroy()
    }
}
