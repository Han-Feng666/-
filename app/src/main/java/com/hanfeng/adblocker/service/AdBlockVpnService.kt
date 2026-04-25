package com.HanFeng.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import com.HanFeng.R
import com.HanFeng.data.LogRepository
import com.HanFeng.data.RuleRepository
import com.HanFeng.data.StatsRepository
import com.HanFeng.data.WhitelistRepository
import com.HanFeng.dns.DnsMessageParser
import com.HanFeng.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class AdBlockVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetJob: Job? = null
    private val appNameCache = LinkedHashMap<String, String>(256, 0.75f, true)
    private val domainAppCache = LinkedHashMap<String, String>(256, 0.75f, true)
    private val sourcePortAppCache = LinkedHashMap<String, String>(256, 0.75f, true)
    private val dnsResponseCache = LinkedHashMap<String, CachedDnsResponse>(256, 0.75f, true)
    private val decisionLogCache = LinkedHashMap<String, Long>(256, 0.75f, true)
    private val upstreamServerStates = linkedMapOf<String, UpstreamServerState>()
    private val dnsServerCacheLock = Any()
    private val localDnsV4 = "10.99.0.2"
    private val localDnsV6 = "fd66:66::2"
    private val staleCacheGraceMillis = 60_000L
    private val dnsServerCacheTtlMillis = 15_000L
    private val blockedIpNetworks by lazy(LazyThreadSafetyMode.NONE) { loadBlockedIpNetworks() }
    private val upstreamFallbackDnsHosts = listOf(
        "223.5.5.5",
        "223.6.6.6",
        "114.114.114.114",
        "114.114.115.115",
        "101.226.4.6",
        "101.226.4.7",
        "117.50.10.10",
        "117.50.11.11",
        "180.76.76.76",
        "182.254.116.116",
        "119.29.29.29",
        "52.80.66.66",
        "45.90.28.0",
        "45.90.30.0",
        "9.9.9.9",
        "208.67.222.222",
        "208.67.220.220",
        "185.228.168.168",
        "185.228.169.168",
        "94.140.14.140",
        "94.140.15.140",
        "1.1.1.1",
        "1.0.0.1",
        "8.8.8.8",
        "8.8.4.4",
        "2001:4860:4860::8888",
        "2001:4860:4860::8844",
        "2606:4700:4700::1111",
        "2606:4700:4700::1001",
        "2620:fe::fe",
        "2620:fe::9",
        "2620:119:35::35",
        "2620:119:53::53",
        "2a10:50c0::ad1:ff",
        "2a10:50c0::ad2:ff"
    )
    private val upstreamFallbackDnsServers by lazy(LazyThreadSafetyMode.NONE) {
        upstreamFallbackDnsHosts.mapNotNull { host -> runCatching { InetAddress.getByName(host) }.getOrNull() }
    }
    @Volatile private var cachedDnsServers: CachedDnsServers? = null
    private val handledDnsHosts by lazy(LazyThreadSafetyMode.NONE) {
        setOf(localDnsV4, localDnsV6).mapNotNull { host ->
            runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
        }.toSet()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val shouldStaySticky = when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                false
            }
            ACTION_RELOAD -> {
                reloadVpn()
                true
            }
            else -> {
                if (vpnInterface == null || packetJob?.isActive != true) {
                    startVpn()
                } else {
                    LogRepository.append(this, "VPN start skipped: already running")
                }
                isRunning
            }
        }
        return if (shouldStaySticky && isRunning) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        stopVpn(stopService = false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isRunning) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startVpn() {
        if (vpnInterface != null && packetJob?.isActive == true) {
            isRunning = true
            return
        }
        val foregroundStarted = runCatching {
            createChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
        }.onFailure { error ->
            isRunning = false
            LogRepository.append(this, "VPN foreground start failed: ${error.message ?: error.javaClass.simpleName}")
            stopSelf()
        }.isSuccess
        if (!foregroundStarted) {
            return
        }
        vpnInterface = runCatching { buildInterface() }
            .onFailure { error ->
                LogRepository.append(this, "VPN establish failed: ${error.message ?: error.javaClass.simpleName}")
            }
            .getOrNull()
        if (vpnInterface == null) {
            isRunning = false
            clearRuntimeState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        isRunning = vpnInterface != null
        packetJob = scope.launch {
            runCatching { runPacketLoop() }
                .onFailure { error ->
                    LogRepository.append(this@AdBlockVpnService, "VPN loop crashed: ${error.message ?: error.javaClass.simpleName}")
                    stopVpn()
                }
        }
        LogRepository.append(this, "VPN started")
    }

    private fun reloadVpn() {
        LogRepository.append(this, "VPN reload requested")
        stopVpn(stopService = false)
        startVpn()
    }

    private fun stopVpn(stopService: Boolean = true) {
        isRunning = false
        packetJob?.cancel()
        packetJob = null
        vpnInterface?.close()
        vpnInterface = null
        clearRuntimeState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (stopService) stopSelf()
        LogRepository.append(this, "VPN stopped")
    }

    private fun clearRuntimeState() {
        synchronized(appNameCache) {
            appNameCache.clear()
        }
        synchronized(domainAppCache) {
            domainAppCache.clear()
        }
        synchronized(sourcePortAppCache) {
            sourcePortAppCache.clear()
        }
        synchronized(dnsResponseCache) {
            dnsResponseCache.clear()
        }
        synchronized(decisionLogCache) {
            decisionLogCache.clear()
        }
        synchronized(upstreamServerStates) {
            upstreamServerStates.clear()
        }
        synchronized(dnsServerCacheLock) {
            cachedDnsServers = null
        }
    }

    private fun buildInterface(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress("10.99.0.1", 24)
            .addAddress("fd66:66::1", 64)
            .addDnsServer(localDnsV4)
            .addDnsServer(localDnsV6)
            .addRoute(localDnsV4, 32)
            .addRoute(localDnsV6, 128)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.allowFamily(OsConstants.AF_INET)
            builder.allowFamily(OsConstants.AF_INET6)
        }

        blockedIpNetworks.forEach { network ->
            runCatching {
                builder.addRoute(network.routeAddress, network.prefixLength)
            }.onFailure {
                LogRepository.append(this, "Skip blocked route ${network.routeAddress}/${network.prefixLength}: ${it.message ?: it.javaClass.simpleName}")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setBlocking(true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WhitelistRepository.getPackages(this).forEach { packageName ->
                runCatching { builder.addDisallowedApplication(packageName) }
            }
        }

        return builder.establish()
    }

    private fun runPacketLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val descriptor = vpnInterface ?: return
        FileInputStream(descriptor.fileDescriptor).use { input ->
            FileOutputStream(descriptor.fileDescriptor).use { output ->
                val buffer = ByteArray(32767)
                while (scope.isActive && isRunning) {
                    val length = input.read(buffer)
                    if (length <= 0) continue
                    runCatching {
                        handlePacket(buffer, length, output)
                    }.onFailure { error ->
                        LogRepository.append(this, "Packet handling failed: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private fun handlePacket(packet: ByteArray, length: Int, output: FileOutputStream) {
        val info = PacketCodec.parse(packet, length) ?: return
        findBlockedIpNetwork(info.destinationAddress)?.let { network ->
            logDecisionOnce(
                key = "blocked-ip:${formatAddress(info.destinationAddress)}",
                message = "Dropped blocked IP route ${formatAddress(info.destinationAddress)}/${network.prefixLength}",
                minIntervalMillis = 60_000L
            )
            return
        }
        if (info.protocol != OsConstants.IPPROTO_UDP || info.destinationPort != 53) {
            return
        }
        if (!shouldHandleDns(info.destinationAddress)) {
            logDecisionOnce(
                key = "bypass-dns:${formatAddress(info.destinationAddress)}",
                message = "Bypassed DNS packet to ${formatAddress(info.destinationAddress)} because it is not the local VPN DNS target",
                minIntervalMillis = 60_000L
            )
            return
        }

        val question = DnsMessageParser.parseQuestion(info.payload) ?: return
        val matchedRule = RuleRepository.findMatchingRule(this, question.domain)
        val appName = resolveAppName(question.domain, info)
        val vendor = matchedRule?.vendor ?: RuleRepository.classifyVendorFromHints(this, question.domain, appName)
        val aggressiveNovelBlock = RuleRepository.shouldAggressivelyBlockForNovelApp(this, question.domain, appName, vendor)
        logDecisionOnce(
            key = "entered:${question.qType}:${question.domain.lowercase()}",
            message = "DNS query entered VPN domain=${question.domain} type=${question.qType} app=$appName target=${formatAddress(info.destinationAddress)}",
            minIntervalMillis = 15_000L
        )
        RuleRepository.reportUnknownVendorIfNeeded(this, vendor, question.domain, appName)
        StatsRepository.recordRequest(this, vendor, appName)

        if (RuleRepository.isBlocked(this, question.domain) || aggressiveNovelBlock) {
            val response = DnsMessageParser.buildSinkholeResponse(info.payload, question) ?: return
            output.write(PacketCodec.buildUdpResponse(info, response))
            StatsRepository.recordBlockedResponse(this, vendor, appName)
            logDecisionOnce(
                key = "blocked:${question.qType}:${question.domain.lowercase()}",
                message = if (aggressiveNovelBlock) {
                    "Blocked domain=${question.domain} type=${question.qType} app=$appName vendor=$vendor via=novel-app-strong-mode"
                } else {
                    "Blocked domain=${question.domain} type=${question.qType} app=$appName vendor=$vendor via=${formatAddress(info.destinationAddress)}"
                },
                minIntervalMillis = 10_000L
            )
            return
        }

        readCachedDnsResponse(question, info.payload)?.let { cachedResponse ->
            output.write(PacketCodec.buildUdpResponse(info, cachedResponse))
            logDecisionOnce(
                key = "allowed-cache:${question.qType}:${question.domain.lowercase()}",
                message = "Allowed domain=${question.domain} type=${question.qType} app=$appName via cached DNS response",
                minIntervalMillis = 15_000L
            )
            return
        }

        val upstreamResult = queryUpstreamDns(info.payload)
        val upstreamResponse = upstreamResult?.response
            ?: readStaleCachedDnsResponse(question, info.payload)?.also {
                logDecisionOnce(
                    key = "allowed-stale:${question.qType}:${question.domain.lowercase()}",
                    message = "Allowed domain=${question.domain} type=${question.qType} app=$appName via stale DNS cache after upstream failure",
                    minIntervalMillis = 15_000L
                )
            }
            ?: DnsMessageParser.buildServerFailureResponse(info.payload, question).also {
                logDecisionOnce(
                    key = "servfail:${question.qType}:${question.domain.lowercase()}",
                    message = "Replied SERVFAIL for domain=${question.domain} type=${question.qType} app=$appName after upstream DNS failure",
                    minIntervalMillis = 10_000L
                )
            }
        val blockedCnameTarget = DnsMessageParser.extractCnameTargets(upstreamResponse, question)
            .firstOrNull { cnameTarget ->
                RuleRepository.isBlocked(this, cnameTarget) ||
                    RuleRepository.shouldAggressivelyBlockForNovelApp(this, cnameTarget, appName, RuleRepository.classifyVendorFromHints(this, cnameTarget, appName))
            }
        if (blockedCnameTarget != null) {
            val cnameVendor = RuleRepository.classifyVendorFromHints(this, blockedCnameTarget, appName)
            val sinkholeResponse = DnsMessageParser.buildSinkholeResponse(info.payload, question) ?: return
            output.write(PacketCodec.buildUdpResponse(info, sinkholeResponse))
            StatsRepository.recordBlockedResponse(this, cnameVendor, appName)
            logDecisionOnce(
                key = "blocked-cname:${question.qType}:${question.domain.lowercase()}",
                message = "Blocked domain=${question.domain} type=${question.qType} app=$appName vendor=$cnameVendor via=cname->$blockedCnameTarget",
                minIntervalMillis = 10_000L
            )
            return
        }
        if (upstreamResult != null) {
            logDecisionOnce(
                key = "allowed-upstream:${question.qType}:${question.domain.lowercase()}",
                message = "Allowed domain=${question.domain} type=${question.qType} app=$appName via upstream DNS ${upstreamResult.server.hostAddress}",
                minIntervalMillis = 15_000L
            )
        }
        cacheDnsResponse(question, upstreamResponse)
        output.write(PacketCodec.buildUdpResponse(info, upstreamResponse))
    }

    private fun queryUpstreamDns(payload: ByteArray): UpstreamDnsResult? {
        resolveDnsServers().forEach { server ->
            repeat(2) { attempt ->
                runCatching {
                    DatagramSocket().use { socket ->
                        protect(socket)
                        socket.soTimeout = if (attempt == 0) 1200 else 1800
                        socket.connect(server, 53)
                        socket.send(DatagramPacket(payload, payload.size))
                        val receiveBuffer = ByteArray(4096)
                        val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                        socket.receive(packet)
                        markUpstreamSuccess(server)
                        return UpstreamDnsResult(server, packet.data.copyOf(packet.length))
                    }
                }.onFailure {
                    markUpstreamFailure(server)
                    if (attempt == 1) {
                        LogRepository.append(this, "Upstream DNS ${server.hostAddress} failed: ${it.message ?: it.javaClass.simpleName}")
                    }
                }
            }
        }
        return null
    }

    private fun logDecisionOnce(key: String, message: String, minIntervalMillis: Long) {
        val now = System.currentTimeMillis()
        synchronized(decisionLogCache) {
            val previous = decisionLogCache[key]
            if (previous != null && now - previous < minIntervalMillis) {
                return
            }
            decisionLogCache[key] = now
            while (decisionLogCache.size > 256) {
                val firstKey = decisionLogCache.entries.firstOrNull()?.key ?: break
                decisionLogCache.remove(firstKey)
            }
        }
        LogRepository.append(this, message)
    }

    private fun resolveDnsServers(): List<InetAddress> {
        val now = System.currentTimeMillis()
        cachedDnsServers?.takeIf { it.expiresAt > now }?.let { return it.servers }
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val linkProperties: LinkProperties? = connectivityManager.getLinkProperties(connectivityManager.activeNetwork)
        val dynamicServers = linkProperties?.dnsServers.orEmpty()
            .filterNot { handledDnsHosts.contains(it.hostAddress ?: "") }
        val candidates = (dynamicServers + upstreamFallbackDnsServers)
            .distinctBy { it.hostAddress ?: it.hostName }
        val sorted = candidates.sortedWith(
            compareBy<InetAddress> { currentUpstreamState(it).cooldownUntil > now }
                .thenBy { currentUpstreamState(it).failureCount }
                .thenByDescending { currentUpstreamState(it).lastSuccessAt }
                .thenBy { it.hostAddress ?: it.hostName }
        )
        synchronized(dnsServerCacheLock) {
            cachedDnsServers = CachedDnsServers(sorted, now + dnsServerCacheTtlMillis)
        }
        return sorted
    }

    private fun readCachedDnsResponse(question: com.HanFeng.model.DnsQuestion, queryPayload: ByteArray): ByteArray? {
        val now = System.currentTimeMillis()
        val key = DnsMessageParser.buildCacheKey(question)
        synchronized(dnsResponseCache) {
            dnsResponseCache.entries.removeIf { it.value.expiresAt + staleCacheGraceMillis <= now }
            val cached = dnsResponseCache[key] ?: return null
            if (cached.expiresAt <= now) return null
            return DnsMessageParser.restoreCachedResponseForQuery(cached.payload, queryPayload)
        }
    }

    private fun readStaleCachedDnsResponse(question: com.HanFeng.model.DnsQuestion, queryPayload: ByteArray): ByteArray? {
        val now = System.currentTimeMillis()
        val key = DnsMessageParser.buildCacheKey(question)
        synchronized(dnsResponseCache) {
            dnsResponseCache.entries.removeIf { it.value.expiresAt + staleCacheGraceMillis <= now }
            val cached = dnsResponseCache[key] ?: return null
            if (cached.expiresAt > now) return null
            return DnsMessageParser.restoreCachedResponseForQuery(cached.payload, queryPayload)
        }
    }

    private fun cacheDnsResponse(question: com.HanFeng.model.DnsQuestion, response: ByteArray) {
        val expiresAt = when {
            DnsMessageParser.isCacheableResponse(response, question) -> System.currentTimeMillis() + DnsMessageParser.extractCacheTtlMillis(response)
            DnsMessageParser.isNegativeCacheableResponse(response, question) -> System.currentTimeMillis() + DnsMessageParser.negativeCacheTtlMillis()
            else -> return
        }
        val normalized = DnsMessageParser.normalizeResponseForCache(response)
        synchronized(dnsResponseCache) {
            dnsResponseCache[DnsMessageParser.buildCacheKey(question)] = CachedDnsResponse(normalized, expiresAt)
            while (dnsResponseCache.size > 256) {
                val firstKey = dnsResponseCache.entries.firstOrNull()?.key ?: break
                dnsResponseCache.remove(firstKey)
            }
        }
    }

    private fun markUpstreamSuccess(server: InetAddress) {
        val key = server.hostAddress ?: server.hostName
        val now = System.currentTimeMillis()
        synchronized(upstreamServerStates) {
            upstreamServerStates[key] = UpstreamServerState(
                failureCount = 0,
                cooldownUntil = 0L,
                lastSuccessAt = now
            )
        }
    }

    private fun markUpstreamFailure(server: InetAddress) {
        val key = server.hostAddress ?: server.hostName
        val now = System.currentTimeMillis()
        synchronized(upstreamServerStates) {
            val current = upstreamServerStates[key] ?: UpstreamServerState()
            val failures = (current.failureCount + 1).coerceAtMost(6)
            val cooldown = now + (400L shl (failures - 1)).coerceAtMost(15_000L)
            upstreamServerStates[key] = current.copy(
                failureCount = failures,
                cooldownUntil = cooldown
            )
        }
    }

    private fun currentUpstreamState(server: InetAddress): UpstreamServerState {
        val key = server.hostAddress ?: server.hostName
        synchronized(upstreamServerStates) {
            return upstreamServerStates[key] ?: UpstreamServerState()
        }
    }

    private fun shouldHandleDns(address: ByteArray): Boolean {
        return handledDnsHosts.contains(formatAddress(address))
    }

    private fun loadBlockedIpNetworks(): List<BlockedIpNetwork> {
        return resources.openRawResource(R.raw.default_blocked_ip_ranges).bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val trimmed = line.substringBefore('#').trim()
                if (trimmed.isBlank()) return@mapNotNull null
                parseBlockedIpNetwork(trimmed)
            }.toList()
        }
    }

    private fun parseBlockedIpNetwork(raw: String): BlockedIpNetwork? {
        val addressPart = raw.substringBefore('/').trim()
        val prefixPart = raw.substringAfter('/', missingDelimiterValue = "").trim()
        val address = runCatching { InetAddress.getByName(addressPart) }.getOrNull() ?: return null
        val maxPrefixLength = address.address.size * 8
        val prefixLength = prefixPart.toIntOrNull() ?: maxPrefixLength
        if (prefixLength !in 0..maxPrefixLength) return null
        return BlockedIpNetwork(
            addressBytes = address.address,
            prefixLength = prefixLength,
            routeAddress = address.hostAddress ?: addressPart
        )
    }

    private fun findBlockedIpNetwork(address: ByteArray): BlockedIpNetwork? {
        return blockedIpNetworks.firstOrNull { network ->
            network.addressBytes.size == address.size && matchesPrefix(address, network.addressBytes, network.prefixLength)
        }
    }

    private fun matchesPrefix(address: ByteArray, networkAddress: ByteArray, prefixLength: Int): Boolean {
        val fullBytes = prefixLength / 8
        val remainingBits = prefixLength % 8
        for (index in 0 until fullBytes) {
            if (address[index] != networkAddress[index]) return false
        }
        if (remainingBits == 0) return true
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (address[fullBytes].toInt() and mask) == (networkAddress[fullBytes].toInt() and mask)
    }

    private fun formatAddress(bytes: ByteArray): String = InetAddress.getByAddress(bytes).hostAddress ?: ""

    private fun resolveAppName(domain: String, info: com.HanFeng.model.PacketInfo): String {
        readCachedAppName(info)?.let { return it }
        readCachedDomainApp(domain)?.let {
            cacheAppName(info, domain, it)
            return it
        }
        readCachedSourcePortApp(info)?.let {
            cacheAppName(info, domain, it)
            return it
        }
        val resolved = resolveAppNameByUid(info)
        if (resolved != null) {
            cacheAppName(info, domain, resolved)
            return resolved
        }
        return readCachedPortAppName(info) ?: "未知应用"
    }

    private fun resolveAppNameByUid(info: com.HanFeng.model.PacketInfo): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val protocol = if (info.protocol == OsConstants.IPPROTO_UDP) OsConstants.IPPROTO_UDP else OsConstants.IPPROTO_TCP
            val connectivityManager = getSystemService(ConnectivityManager::class.java)
            val local = InetSocketAddress(InetAddress.getByAddress(info.sourceAddress), info.sourcePort)
            val remote = InetSocketAddress(InetAddress.getByAddress(info.destinationAddress), info.destinationPort)
            val uid = connectivityManager.getConnectionOwnerUid(protocol, local, remote)
            if (uid <= 0) return@runCatching null
            buildAppLabel(uid)
        }.getOrElse {
            LogRepository.append(this, "Resolve app failed: ${it.message ?: it.javaClass.simpleName}")
            null
        }
    }

    private fun buildAppLabel(uid: Int): String? {
        val packageName = packageManager.getPackagesForUid(uid)?.firstOrNull()
            ?: packageManager.getNameForUid(uid)
            ?: return null
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        return if (label == packageName) packageName else "$label ($packageName)"
    }

    private fun readCachedAppName(info: com.HanFeng.model.PacketInfo): String? {
        synchronized(appNameCache) {
            return appNameCache[flowCacheKey(info)]
        }
    }

    private fun readCachedPortAppName(info: com.HanFeng.model.PacketInfo): String? {
        synchronized(appNameCache) {
            return appNameCache[portCacheKey(info)]
        }
    }

    private fun readCachedSourcePortApp(info: com.HanFeng.model.PacketInfo): String? {
        synchronized(sourcePortAppCache) {
            return sourcePortAppCache[sourcePortCacheKey(info)]
        }
    }

    private fun readCachedDomainApp(domain: String): String? {
        val normalized = domain.lowercase()
        synchronized(domainAppCache) {
            return domainAppCache[normalized] ?: secondLevelDomain(normalized)?.let(domainAppCache::get)
        }
    }

    private fun cacheAppName(info: com.HanFeng.model.PacketInfo, domain: String, appName: String) {
        synchronized(appNameCache) {
            appNameCache[flowCacheKey(info)] = appName
            appNameCache[portCacheKey(info)] = appName
            while (appNameCache.size > 512) {
                val firstKey = appNameCache.entries.firstOrNull()?.key ?: break
                appNameCache.remove(firstKey)
            }
        }
        synchronized(sourcePortAppCache) {
            sourcePortAppCache[sourcePortCacheKey(info)] = appName
            while (sourcePortAppCache.size > 512) {
                val firstKey = sourcePortAppCache.entries.firstOrNull()?.key ?: break
                sourcePortAppCache.remove(firstKey)
            }
        }
        val normalized = domain.lowercase()
        synchronized(domainAppCache) {
            domainAppCache[normalized] = appName
            secondLevelDomain(normalized)?.let { domainAppCache[it] = appName }
            while (domainAppCache.size > 512) {
                val firstKey = domainAppCache.entries.firstOrNull()?.key ?: break
                domainAppCache.remove(firstKey)
            }
        }
    }

    private fun secondLevelDomain(domain: String): String? {
        val parts = domain.split('.').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return parts.takeLast(2).joinToString(".")
    }

    private fun flowCacheKey(info: com.HanFeng.model.PacketInfo): String {
        return listOf(
            info.protocol.toString(),
            formatAddress(info.sourceAddress),
            info.sourcePort.toString(),
            formatAddress(info.destinationAddress),
            info.destinationPort.toString()
        ).joinToString(":" )
    }

    private fun portCacheKey(info: com.HanFeng.model.PacketInfo): String {
        return "${info.protocol}:${formatAddress(info.sourceAddress)}:${info.sourcePort}"
    }

    private fun sourcePortCacheKey(info: com.HanFeng.model.PacketInfo): String {
        return "${formatAddress(info.sourceAddress)}:${info.sourcePort}"
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("寒枫广告拦截运行中")
            .setContentText("本地 VPN DNS 拦截已启动")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "广告拦截服务", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "com.HanFeng.STOP"
        const val ACTION_RELOAD = "com.HanFeng.RELOAD"
        private const val CHANNEL_ID = "adblock_vpn"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning: Boolean = false
    }

    private data class CachedDnsResponse(
        val payload: ByteArray,
        val expiresAt: Long
    )

    private data class UpstreamServerState(
        val failureCount: Int = 0,
        val cooldownUntil: Long = 0L,
        val lastSuccessAt: Long = 0L
    )

    private data class CachedDnsServers(
        val servers: List<InetAddress>,
        val expiresAt: Long
    )

    private data class UpstreamDnsResult(
        val server: InetAddress,
        val response: ByteArray
    )

    private data class BlockedIpNetwork(
        val addressBytes: ByteArray,
        val prefixLength: Int,
        val routeAddress: String
    )
}
