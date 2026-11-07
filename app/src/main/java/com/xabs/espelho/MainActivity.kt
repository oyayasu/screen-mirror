package com.xabs.espelho

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
 * Versão 0.1: descoberta de TVs, NÃO um transmissor Miracast.
 * Os resultados diferenciam descoberta e conectividade; abrir o painel Cast
 * não estabelece automaticamente a conexão com a TV.
 */
class MainActivity : Activity() {
    private data class Device(
        val key: String,
        val name: String,
        val address: String?,
        val channel: String,
        val details: String
    )

    private val background = Color.rgb(248, 250, 252)
    private val ink = Color.rgb(29, 41, 57)
    private val secondary = Color.rgb(105, 117, 133)
    private val blue = Color.rgb(52, 91, 161)
    private val border = Color.rgb(226, 232, 239)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val jobs = Executors.newFixedThreadPool(18)
    private val scanId = AtomicInteger(0)
    private val devices = linkedMapOf<String, Device>()
    private val nsdListeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val favoriteSet by lazy {
        getSharedPreferences("favorites", MODE_PRIVATE)
    }

    private lateinit var status: TextView
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
            clipToPadding = false
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(32))
        }
        scroll.addView(column, ViewGroup.LayoutParams(-1, -2))
        setContentView(scroll)

        column.addView(text("Espelho", 32f, ink, true))
        column.addView(text("Descubra dispositivos próximos", 15f, secondary).apply {
            setPadding(0, dp(4), 0, dp(24))
        })

        val hero = panel()
        hero.addView(text("Buscar TVs", 20f, ink, true))
        hero.addView(text("Procura equipamentos na rede e pelo Wi-Fi Direct. A descoberta não garante que o espelhamento seja aceito.", 14f, secondary).apply {
            setPadding(0, dp(8), 0, dp(16))
        })
        hero.addView(button("Procurar dispositivos", true) { startSearch() })
        hero.addView(button("Procurar IPs da rede", false) { scanLocalNetwork() }.apply {
            val lp = LinearLayout.LayoutParams(-1, dp(48)); lp.topMargin = dp(8); layoutParams = lp
        })
        status = text("Pronto para procurar", 13f, secondary).apply {
            setPadding(0, dp(14), 0, 0)
        }
        hero.addView(status)
        column.addView(hero)

        val listHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(26), 0, dp(12))
        }
        listHeader.addView(text("Dispositivos", 19f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        counter = text("0 encontrados", 12f, secondary)
        listHeader.addView(counter)
        column.addView(listHeader)
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(deviceList)
        updateList()

        val manual = panel().apply {
            val lp = LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(24); layoutParams = lp
        }
        manual.addView(text("Adicionar por IP", 18f, ink, true))
        manual.addView(text("Útil quando a Samsung não responde à descoberta automática.", 13f, secondary).apply {
            setPadding(0, dp(6), 0, dp(10))
        })
        ipField = EditText(this).apply {
            hint = "Ex.: 192.168.1.50"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(secondary)
            background = shape(Color.WHITE, border, 12f)
            setPadding(dp(12), 0, dp(12), 0)
        }
        manual.addView(ipField, LinearLayout.LayoutParams(-1, dp(48)))
        manual.addView(button("Verificar endereço", false) { verifyIp() }.apply {
            val lp = LinearLayout.LayoutParams(-1, dp(48)); lp.topMargin = dp(10); layoutParams = lp
        })
        column.addView(manual)

        val connection = panel().apply {
            val lp = LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(18); layoutParams = lp
        }
        connection.addView(text("Espelhamento", 18f, ink, true))
        connection.addView(text("A conexão Miracast é gerenciada pelo sistema do tablet. Abra o seletor de transmissão para tentar conectar à TV.", 13f, secondary).apply {
            setPadding(0, dp(6), 0, dp(12))
        })
        connection.addView(button("Abrir espelhamento do Android", false) { openCastSettings() })
        column.addView(connection)

        column.addView(text("Versão 0.1 • sem anúncios • descoberta experimental", 12f, secondary).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(Color.WHITE, border, 18f)
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun button(label: String, primary: Boolean, click: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(if (primary) Color.WHITE else blue)
        background = shape(if (primary) blue else Color.rgb(239, 244, 251), null, 12f)
        setPadding(dp(10), 0, dp(10), 0)
        elevation = 0f
        setOnClickListener { click() }
        if (layoutParams == null) layoutParams = LinearLayout.LayoutParams(-1, dp(48))
    }

    private fun shape(fill: Int, stroke: Int?, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(px: Int): Int = (px * resources.displayMetrics.density + 0.5f).toInt()

    private fun setStatus(message: String) = runOnUiThread { status.text = message }

    private fun addDevice(device: Device) = runOnUiThread {
        // Evite duplicados no mesmo IP quando encontrados por SSDP, NSD e busca TCP.
        val existing = if (device.address != null) devices.values.firstOrNull { it.address == device.address } else null
        if (existing != null) {
            val merged = Device(existing.key,
                if (device.name.contains("Samsung", ignoreCase = true)) device.name else existing.name,
                existing.address, "${existing.channel} · ${device.channel}", existing.details)
            devices[existing.key] = merged
        } else {
            devices[device.key] = device
        }
        updateList()
    }

    private fun updateList() {
        deviceList.removeAllViews()
        counter.text = "${devices.size} encontrados"
        if (devices.isEmpty()) {
            deviceList.addView(text("Nenhum dispositivo encontrado ainda.", 14f, secondary).apply {
                setPadding(dp(8), dp(16), 0, dp(18))
            })
            return
        }
        devices.values.forEach { device ->
            val card = panel().apply {
                val lp = LinearLayout.LayoutParams(-1, -2)
                lp.bottomMargin = dp(10)
                layoutParams = lp
            }
            card.addView(text(device.name, 16f, ink, true))
            card.addView(text(listOfNotNull(device.address, device.channel).joinToString(" · "), 12f, secondary).apply {
                setPadding(0, dp(5), 0, 0)
            })
            card.addView(text(device.details, 12f, secondary).apply {
                setPadding(0, dp(6), 0, dp(10))
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val saved = isFavorite(device)
            actions.addView(button(if (saved) "Remover favorito" else "Favorito", false) {
                toggleFavorite(device)
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            val connect = button("Tentar espelhar", true) { openCastSettings() }
            val connectParams = LinearLayout.LayoutParams(0, dp(44), 1.2f)
            connectParams.marginStart = dp(8)
            actions.addView(connect, connectParams)
            card.addView(actions)
            deviceList.addView(card)
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

    private fun openCastSettings() {
        try {
            startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            catch (_: Exception) { setStatus("O Android não disponibilizou o painel de transmissão.") }
        }
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
