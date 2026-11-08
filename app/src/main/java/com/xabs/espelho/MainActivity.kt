package com.xabs.espelho

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.media.projection.MediaProjectionManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Interface v0.4. Descoberta de rede e transmissão HLS continuam em serviços independentes.
 * Encontrar uma TV não significa que o dispositivo aceite Miracast.
 */
class MainActivity : Activity() {
    private data class Device(
        val key: String,
        val name: String,
        val address: String?,
        val channel: String,
        val details: String
    )

    private val background = Color.rgb(247, 247, 251)
    private val ink = Color.rgb(32, 32, 47)
    private val secondary = Color.rgb(119, 122, 140)
    private val accent = Color.rgb(117, 110, 189)
    private val accentSoft = Color.rgb(243, 241, 253)
    private val border = Color.rgb(233, 233, 242)
    private val white = Color.WHITE
    private val mainHandler = Handler(Looper.getMainLooper())
    private val jobs = Executors.newFixedThreadPool(18)
    private val scanId = AtomicInteger(0)
    private val devices = linkedMapOf<String, Device>()
    private val nsdListeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val favoriteSet by lazy {
        getSharedPreferences("favorites", MODE_PRIVATE)
    }

    private lateinit var status: TextView
    private lateinit var mirrorStatus: TextView
    private lateinit var streamBadge: TextView
    private lateinit var shareActions: LinearLayout
    private lateinit var streamAction: TextView
    private var audioPermissionRequested = false
    private val mirrorStatusUpdater = object : Runnable {
        override fun run() {
            if (::mirrorStatus.isInitialized) updateMirrorStatus()
            mainHandler.postDelayed(this, 1200L)
        }
    }
    private lateinit var counter: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var ipField: EditText
    private var p2p: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var nsd: NsdManager? = null
    private var wifi: WifiManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var receiverRegistered = false
    private var pendingSearch = false

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) {
                requestPeers()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        p2p = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        p2pChannel = p2p?.initialize(this, mainLooper, null)
        nsd = getSystemService(Context.NSD_SERVICE) as? NsdManager
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        registerReceiver(p2pReceiver, filter)
        receiverRegistered = true

        buildScreen()
        restoreFavorites()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(this@MainActivity.background)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(40))
        }
        val contentWidth = minOf(resources.displayMetrics.widthPixels - dp(12), dp(680))
        scroll.addView(content, FrameLayout.LayoutParams(
            contentWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ))
        setContentView(scroll)

        // Cabeçalho com o ícone vetorial original do Espelho.
        val heading = horizontal()
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.app_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Espelho"
        }
        heading.addView(logo, LinearLayout.LayoutParams(dp(57), dp(57)))
        val nameBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, 0, 0)
        }
        nameBlock.addView(text("Espelho", 29f, ink, true))
        nameBlock.addView(text("Transmissão de tela", 13f, secondary))
        heading.addView(nameBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val version = text("v0.4", 12f, accent, true).apply {
            gravity = Gravity.CENTER
            background = shape(accentSoft, null, 14f)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        heading.addView(version)
        content.addView(heading)
        content.addView(space(25))

        // Card principal: ênfase na tarefa de espelhamento, não em detalhes técnicos.
        val share = panel()
        val shareHeader = horizontal()
        shareHeader.addView(iconTile(R.drawable.ic_transmit), LinearLayout.LayoutParams(dp(52), dp(52)))
        val shareTitle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(1), 0, 0)
        }
        shareTitle.addView(text("Compartilhar tela", 21f, ink, true))
        shareTitle.addView(text("Vídeo e áudio pela rede local", 13f, secondary).apply {
            setPadding(0, dp(4), 0, 0)
        })
        shareHeader.addView(shareTitle)
        share.addView(shareHeader)
        share.addView(space(17))
        streamAction = button("Iniciar transmissão", true) { startBrowserMirroring() }
        share.addView(streamAction, LinearLayout.LayoutParams(-1, dp(54)))
        val streamBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(accentSoft, null, 18f)
            setPadding(dp(15), dp(14), dp(15), dp(14))
        }
        streamBadge = text("Pronto para transmitir", 12f, accent, true)
        streamBox.addView(streamBadge)
        mirrorStatus = text("Nenhuma transmissão ativa.", 13f, secondary).apply {
            setPadding(0, dp(6), 0, 0)
            setTextIsSelectable(true)
        }
        streamBox.addView(mirrorStatus)
        shareActions = horizontal().apply {
            val params = LinearLayout.LayoutParams(-1, -2)
            params.topMargin = dp(12)
            layoutParams = params
            visibility = View.GONE
        }
        shareActions.addView(button("Copiar link", false) { copyMirrorAddress() },
            LinearLayout.LayoutParams(0, dp(44), 1f))
        val stopParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(9) }
        shareActions.addView(button("Encerrar", false) { stopBrowserMirroring() }, stopParams)
        streamBox.addView(shareActions)
        val streamParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(15) }
        share.addView(streamBox, streamParams)
        content.addView(share)
        content.addView(space(27))

        // Busca: a lista nunca implica que a TV já foi conectada.
        val discoveryHeading = horizontal()
        discoveryHeading.addView(text("Dispositivos", 21f, ink, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        counter = text("0 encontrados", 12f, secondary)
        discoveryHeading.addView(counter)
        content.addView(discoveryHeading)
        content.addView(space(11))

        val searchPanel = panel()
        val searchHeader = horizontal()
        searchHeader.addView(iconTile(R.drawable.ic_search_devices), LinearLayout.LayoutParams(dp(43), dp(43)))
        searchHeader.addView(text("Encontrar TVs na rede", 15f, ink, true).apply {
            setPadding(dp(12), 0, 0, 0)
        })
        searchPanel.addView(searchHeader)
        val searchButtons = horizontal()
        searchButtons.addView(button("Buscar TVs", false) { startSearch() },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        searchButtons.addView(button("Buscar IPs", false) { scanLocalNetwork() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(9) })
        searchPanel.addView(searchButtons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        status = text("Pronto para procurar.", 12f, secondary).apply {
            setPadding(0, dp(11), 0, 0)
        }
        searchPanel.addView(status)
        content.addView(searchPanel)
        content.addView(space(10))

        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(deviceList)
        updateList()

        val manual = panel()
        manual.addView(text("Adicionar por IP", 16f, ink, true))
        manual.addView(text("Use o endereço da TV se ela não aparecer na busca.", 12f, secondary).apply {
            setPadding(0, dp(5), 0, dp(12))
        })
        ipField = EditText(this).apply {
            hint = "192.168.1.50"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(secondary)
            background = shape(Color.rgb(249, 249, 253), border, 15f)
            setPadding(dp(15), 0, dp(15), 0)
        }
        manual.addView(ipField, LinearLayout.LayoutParams(-1, dp(50)))
        val verify = button("Verificar endereço", false) { verifyIp() }
        manual.addView(verify, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        content.addView(manual)
        content.addView(text("Espelho 0.4  ·  Sem anúncios", 12f, secondary).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })
        updateMirrorStatus()
    }

    private fun horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun iconTile(icon: Int): FrameLayout = FrameLayout(this).apply {
        background = shape(accentSoft, null, 16f)
        addView(ImageView(this@MainActivity).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER))
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(white, border, 25f)
        setPadding(dp(19), dp(20), dp(19), dp(20))
        elevation = dp(1).toFloat()
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(
                if (bold) "sans-serif-medium" else "sans-serif",
                Typeface.NORMAL
            )
            if (size >= 20f) letterSpacing = -0.018f
        }

    private fun button(label: String, primary: Boolean, click: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (primary) white else accent)
            gravity = Gravity.CENTER
            setPadding(dp(13), 0, dp(13), 0)
            background = RippleDrawable(
                ColorStateList.valueOf(if (primary) Color.rgb(217, 211, 255) else Color.rgb(222, 218, 249)),
                shape(if (primary) accent else accentSoft, null, 16f),
                null
            )
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { click() }
            if (layoutParams == null) layoutParams = LinearLayout.LayoutParams(-1, dp(48))
        }

    private fun shape(fill: Int, stroke: Int?, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun dp(px: Int): Int = (px * resources.displayMetrics.density + 0.5f).toInt()

    private fun setStatus(message: String) = runOnUiThread { status.text = message }

    private fun addDevice(device: Device) = runOnUiThread {
        val existing = if (device.address != null)
            devices.values.firstOrNull { it.address == device.address } else null
        if (existing != null) {
            val merged = Device(
                existing.key,
                if (device.name.contains("Samsung", ignoreCase = true)) device.name else existing.name,
                existing.address, existing.channel + " · " + device.channel, existing.details
            )
            devices[existing.key] = merged
        } else {
            devices[device.key] = device
        }
        updateList()
    }

    private fun updateList() {
        deviceList.removeAllViews()
        counter.text = devices.size.toString() + " encontrados"
        if (devices.isEmpty()) {
            val empty = panel().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(18), dp(20), dp(18), dp(20))
            }
            empty.addView(iconTile(R.drawable.ic_television),
                LinearLayout.LayoutParams(dp(52), dp(52)))
            empty.addView(text("Nenhuma TV encontrada", 15f, ink, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(11), 0, 0)
            })
            empty.addView(text("Toque em Buscar TVs para começar.", 12f, secondary).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
            deviceList.addView(empty, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(11)
            })
            return
        }
        devices.values.forEach { device ->
            val card = panel()
            val row = horizontal()
            row.addView(iconTile(R.drawable.ic_television),
                LinearLayout.LayoutParams(dp(44), dp(44)))
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            info.addView(text(device.name, 16f, ink, true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            info.addView(text(
                listOfNotNull(device.address, device.channel).joinToString(" · "),
                12f, secondary
            ).apply { setPadding(0, dp(5), 0, 0) })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(row)
            card.addView(text(device.details, 12f, secondary).apply {
                setPadding(0, dp(11), 0, dp(13))
            })
            val actions = horizontal()
            actions.addView(button(if (isFavorite(device)) "Salvo" else "Salvar", false) {
                toggleFavorite(device)
            }, LinearLayout.LayoutParams(0, dp(43), 0.95f))
            actions.addView(button("Opções de conexão", false) {
                showConnectionOptions(device)
            }, LinearLayout.LayoutParams(0, dp(43), 1.4f).apply {
                marginStart = dp(9)
            })
            card.addView(actions)
            deviceList.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            })
        }
    }

    private fun favKey(d: Device) = d.address ?: d.key
    private fun isFavorite(d: Device): Boolean = favoriteSet.getStringSet("saved", emptySet())?.contains(favKey(d)) == true
    private fun toggleFavorite(d: Device) {
        val keys = (favoriteSet.getStringSet("saved", emptySet()) ?: emptySet()).toMutableSet()
        val names = favoriteSet.edit()
        if (!keys.add(favKey(d))) keys.remove(favKey(d))
        names.putStringSet("saved", keys)
        names.putString("name:${favKey(d)}", d.name)
        names.apply()
        updateList()
    }

    private fun restoreFavorites() {
        (favoriteSet.getStringSet("saved", emptySet()) ?: emptySet()).forEach { key ->
            val label = favoriteSet.getString("name:$key", "Dispositivo salvo") ?: "Dispositivo salvo"
            devices["favorite:$key"] = Device("favorite:$key", label,
                if (isLocalIpv4(key)) key else null, "Favorito", "Salvo anteriormente; conexão não verificada nesta sessão")
        }
        updateList()
    }

    private fun startSearch() {
        val generation = scanId.incrementAndGet()
        stopNsd()
        setStatus("Procurando via rede local e Wi-Fi Direct…")
        scanSsdp(generation)
        scanNsd(generation)
        val required = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(required) != PackageManager.PERMISSION_GRANTED) {
            pendingSearch = true
            requestPermissions(arrayOf(required), 101)
        } else {
            scanP2p()
        }
        mainHandler.postDelayed({ if (generation == scanId.get()) setStatus("Busca concluída. Você também pode pesquisar IPs ou informar um endereço.") }, 9500)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && pendingSearch) {
            pendingSearch = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                scanP2p()
            } else {
                setStatus("Busca na rede iniciada. Para Wi-Fi Direct, autorize a permissão solicitada.")
            }
        }
        if (requestCode == 6403) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                setStatus("Permissão de áudio negada. Só o vídeo poderá ser transmitido.")
            }
            startBrowserMirroring()
        }
    }

    @SuppressLint("MissingPermission")
    private fun scanP2p() {
        val manager = p2p ?: return
        val channel = p2pChannel ?: return
        try {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { requestPeers() }
                override fun onFailure(reason: Int) {
                    setStatus("Wi-Fi Direct indisponível (código $reason). Busca na rede continua.")
                }
            })
        } catch (e: SecurityException) { setStatus("Wi-Fi Direct requer autorização do Android.") }
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        val manager = p2p ?: return
        val channel = p2pChannel ?: return
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return
        try {
            manager.requestPeers(channel) { list ->
                list.deviceList.forEach { peer ->
                    val id = peer.deviceAddress ?: peer.deviceName
                    addDevice(Device("p2p:$id", peer.deviceName?.ifBlank { "Dispositivo Wi-Fi Direct" } ?: "Dispositivo Wi-Fi Direct",
                        null, "Wi-Fi Direct", "Identificado como dispositivo P2P; ainda não conectado"))
                }
            }
        } catch (_: SecurityException) { /* Android negou acesso à descoberta */ }
    }

    private fun scanSsdp(generation: Int) {
        jobs.execute {
            try {
                val lock = wifi?.createMulticastLock("espelho-ssdp")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                multicastLock = lock
                try {
                    MulticastSocket(0).use { socket ->
                        socket.soTimeout = 900
                        socket.timeToLive = 2
                        val target = InetAddress.getByName("239.255.255.250")
                        listOf("ssdp:all", "upnp:rootdevice").forEach { st ->
                            val request = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                            val bytes = request.toByteArray(Charsets.UTF_8)
                            socket.send(DatagramPacket(bytes, bytes.size, target, 1900))
                        }
                        val end = System.currentTimeMillis() + 6500
                        while (generation == scanId.get() && System.currentTimeMillis() < end) {
                            val responseBytes = ByteArray(8192)
                            val packet = DatagramPacket(responseBytes, responseBytes.size)
                            try {
                                socket.receive(packet)
                            } catch (_: java.net.SocketTimeoutException) { continue }
                            val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            val ip = packet.address.hostAddress ?: continue
                            if (!isLocalIpv4(ip)) continue
                            val server = header(message, "SERVER") ?: "Dispositivo de rede"
                            val location = header(message, "LOCATION")
                            val friendly = location?.let { readFriendlyName(it, ip) }
                            val deviceName = friendly ?: if (server.contains("samsung", true)) "Samsung TV (SSDP)" else "Dispositivo de rede"
                            addDevice(Device("ssdp:$ip", deviceName, ip, "SSDP", "Detectado por resposta UPnP; espelhamento não verificado"))
                        }
                    }
                } finally {
                    try { if (lock?.isHeld == true) lock.release() } catch (_: Exception) { }
                }
            } catch (_: Exception) { /* Roteadores podem filtrar SSDP; outras buscas continuam */ }
        }
    }

    private fun header(data: String, name: String): String? = data.lineSequence().firstOrNull {
        it.substringBefore(':').trim().equals(name, true)
    }?.substringAfter(':')?.trim()

    private fun readFriendlyName(location: String, sourceIp: String): String? {
        return try {
            val url = URL(location)
            // Somente consultar uma descrição informada por dispositivo da rede local.
            if (url.host != sourceIp || url.protocol != "http") return null
            val conn = url.openConnection().apply { connectTimeout = 450; readTimeout = 450 }
            val xml = conn.getInputStream().bufferedReader().use { it.readText().take(32000) }
            Regex("<friendlyName[^>]*>(.*?)</friendlyName>", RegexOption.IGNORE_CASE)
                .find(xml)?.groupValues?.get(1)?.replace("&amp;", "&")?.take(80)
        } catch (_: Exception) { null }
    }

    private fun scanNsd(generation: Int) {
        val manager = nsd ?: return
        // NSD resolve é apenas uma fonte de endereços; tipo de serviço != suporte a Miracast.
        val services = listOf("_samsungmsf._tcp.", "_googlecast._tcp.", "_airplay._tcp.")
        services.forEachIndexed { index, serviceType ->
            mainHandler.postDelayed({
                if (generation != scanId.get()) return@postDelayed
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(type: String) { }
                    override fun onStartDiscoveryFailed(type: String, code: Int) { stopSelf() }
                    override fun onStopDiscoveryFailed(type: String, code: Int) { }
                    override fun onDiscoveryStopped(type: String) { }
                    override fun onServiceLost(info: NsdServiceInfo) { }
                    override fun onServiceFound(info: NsdServiceInfo) {
                        val serviceName = info.serviceName.ifBlank { "Dispositivo de mídia" }
                        addDevice(Device("nsd:$serviceType:$serviceName", serviceName,
                            null, "mDNS ${serviceType.removeSuffix(".")}", "Serviço encontrado na rede; espelhamento não verificado"))
                        @Suppress("DEPRECATION")
                        manager.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { }
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                val ip = resolved.host?.hostAddress ?: return
                                if (!isLocalIpv4(ip)) return
                                addDevice(Device("nsd:$serviceType:$serviceName", serviceName,
                                    ip, "mDNS ${serviceType.removeSuffix(".")}", "Serviço de mídia encontrado; espelhamento não verificado"))
                            }
                        })
                    }
                    private fun stopSelf() {
                        try { manager.stopServiceDiscovery(this) } catch (_: Exception) { }
                        nsdListeners.remove(this)
                    }
                }
                try {
                    manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                    nsdListeners.add(listener)
                    mainHandler.postDelayed({
                        try { manager.stopServiceDiscovery(listener) } catch (_: Exception) { }
                        nsdListeners.remove(listener)
                    }, 2400)
                } catch (_: Exception) { }
            }, index * 2700L)
        }
    }

    private fun stopNsd() {
        nsdListeners.toList().forEach {
            try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) { }
        }
        nsdListeners.clear()
    }

    private fun scanLocalNetwork() {
        val generation = scanId.incrementAndGet()
        stopNsd()
        val local = privateIpv4OnWifi()
        if (local == null) {
            setStatus("Não consegui identificar uma rede Wi-Fi IPv4 privada. Use o IP manual.")
            return
        }
        val prefix = local.substringBeforeLast('.')
        setStatus("Procurando TVs na faixa $prefix.1–254…")
        val pending = AtomicInteger(254)
        (1..254).forEach { last ->
            jobs.execute {
                try {
                    if (generation != scanId.get()) return@execute
                    val ip = "$prefix.$last"
                    if (ip == local) return@execute
                    val openPort = listOf(8001, 8002, 55000).firstOrNull { port ->
                        try {
                            Socket().use { s -> s.connect(InetSocketAddress(ip, port), 400) }
                            true
                        } catch (_: Exception) { false }
                    }
                    if (openPort != null && generation == scanId.get()) {
                        addDevice(Device("tcp:$ip", "Possível TV Samsung", ip,
                            "Busca TCP", "Porta de controle $openPort aberta; não confirma marca nem espelhamento"))
                    }
                } finally {
                    if (pending.decrementAndGet() == 0 && generation == scanId.get()) {
                        setStatus("Busca de IPs concluída. ${devices.size} dispositivo(s) listado(s).")
                    }
                }
            }
        }
    }

    private fun privateIpv4OnWifi(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            interfaces.asSequence().flatMap { iface -> iface.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
    }

    private fun isLocalIpv4(value: String): Boolean = try {
        if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(value)) false
        else InetAddress.getByName(value).let { it is Inet4Address && it.isSiteLocalAddress }
    } catch (_: Exception) { false }

    private fun verifyIp() {
        val value = ipField.text.toString().trim()
        if (!isLocalIpv4(value)) {
            setStatus("Informe um endereço IPv4 privado válido, como 192.168.1.50.")
            return
        }
        setStatus("Verificando $value…")
        jobs.execute {
            val open = listOf(8001, 8002, 55000).firstOrNull { port ->
                try { Socket().use { it.connect(InetSocketAddress(value, port), 600) }; true }
                catch (_: Exception) { false }
            }
            if (open != null) {
                addDevice(Device("manual:$value", "Dispositivo no IP informado", value,
                    "IP manual", "Porta $open responde; espelhamento não verificado"))
                setStatus("Endereço $value respondeu na porta $open.")
            } else {
                addDevice(Device("manual:$value", "Dispositivo informado", value,
                    "IP manual", "Sem resposta nas portas de controle testadas; pode estar online"))
                setStatus("Sem resposta nas portas testadas em $value; isso não prova que a TV está desligada.")
            }
        }
    }

    private fun showConnectionOptions(device: Device) {
        AlertDialog.Builder(this)
            .setTitle(device.name)
            .setMessage(
                "Detectado por " + device.channel + ". O Espelho identificou este equipamento na rede, " +
                "mas não pode conectá-lo diretamente por Miracast. Escolha como deseja continuar."
            )
            .setPositiveButton("Abrir Smart View") { _, _ -> openSamsungSmartView() }
            .setNeutralButton("Usar navegador da TV") { _, _ -> startBrowserMirroring() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openSamsungSmartView() {
        val intent = Intent(Intent.ACTION_MAIN).setClassName(
            "com.samsung.android.smartmirroring",
            "com.samsung.android.smartmirroring.welcome.WelcomeIntroActivity"
        )
        try {
            startActivity(intent)
        } catch (_: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Abra o Smart View")
                .setMessage(
                    "Esta versão do tablet não permite que o Espelho abra o Smart View diretamente. " +
                    "Deslize duas vezes de cima para baixo e toque em Smart View. " +
                    "A busca do Smart View é independente da lista de TVs encontrada aqui."
                )
                .setPositiveButton("Entendi", null)
                .show()
        }
    }

    private fun startBrowserMirroring() {
        if (android.os.Build.VERSION.SDK_INT >= 29 && !audioPermissionRequested &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermissionRequested = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 6403)
            return
        }
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), 6402)
        } catch (_: Exception) {
            setStatus("O tablet não disponibilizou a permissão para captura da tela.")
        }
    }

    @Deprecated("Compatibilidade com Android 8-14")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 6402) return
        if (resultCode != RESULT_OK || data == null) {
            setStatus("Transmissão cancelada.")
            return
        }
        try {
            val service = Intent(this, BrowserMirrorService::class.java).apply {
                action = BrowserMirrorService.ACTION_START
                putExtra(BrowserMirrorService.EXTRA_PERMISSION_DATA, data)
                putExtra(BrowserMirrorService.EXTRA_RESULT_CODE, resultCode)
            }
            startForegroundService(service)
            setStatus("Iniciando transmissão local…")
        } catch (_: Exception) {
            setStatus("Não foi possível iniciar o serviço de transmissão.")
        }
    }

    private fun copyMirrorAddress() {
        val url = BrowserMirrorService.currentUrl
        if (url == null) {
            setStatus("Inicie a transmissão antes de copiar o endereço.")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Endereço Espelho", url))
        setStatus("Endereço copiado. Abra o navegador da TV e digite o link.")
    }

    private fun stopBrowserMirroring() {
        startService(Intent(this, BrowserMirrorService::class.java).apply {
            action = BrowserMirrorService.ACTION_STOP
        })
        setStatus("Transmissão encerrada.")
    }

    private fun updateMirrorStatus() {
        val url = BrowserMirrorService.currentUrl
        val error = BrowserMirrorService.lastError
        streamBadge.text = when {
            url != null -> "Transmissão ativa"
            error != null -> "Não foi possível transmitir"
            else -> "Pronto para transmitir"
        }
        streamBadge.setTextColor(if (error != null && url == null) Color.rgb(179, 73, 74) else accent)
        mirrorStatus.text = when {
            url != null ->
                "No navegador da TV, abra:\n" + url + "\n\n" +
                    BrowserMirrorService.streamStatus +
                    "\n\nA reprodução depende da compatibilidade da TV."
            error != null -> "Falha: " + error
            else -> "Aguardando início da transmissão."
        }
        shareActions.visibility = if (url != null) View.VISIBLE else View.GONE
        streamAction.isEnabled = url == null
        streamAction.alpha = if (url == null) 1f else 0.55f
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacks(mirrorStatusUpdater)
        mainHandler.post(mirrorStatusUpdater)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(mirrorStatusUpdater)
        super.onPause()
    }

    override fun onDestroy() {
        scanId.incrementAndGet()
        stopNsd()
        if (receiverRegistered) unregisterReceiver(p2pReceiver)
        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (_: Exception) { }
        jobs.shutdownNow()
        super.onDestroy()
    }
}
