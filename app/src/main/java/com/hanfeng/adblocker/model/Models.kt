package com.HanFeng.model

import android.graphics.drawable.Drawable

data class BlockRule(
    val id: String,
    val domain: String,
    val vendor: String,
    val source: RuleSource,
    val dnsTypes: Set<Int>? = null
)

enum class RuleSource(val label: String) {
    MANUAL("手动"),
    IMPORTED("导入"),
    REFERENCE("参考"),
    UNSUPPORTED("暂不支持")
}

data class DashboardStats(
    val todayBlocked: Int,
    val totalBlocked: Int,
    val requestTotal: Int,
    val responseTotal: Int
)

data class RankingEntry(
    val name: String,
    val value: Int
)

enum class RankingType {
    VENDOR_BLOCKED,
    VENDOR_REQUEST,
    VENDOR_RESPONSE,
    APP_BLOCKED,
    APP_REQUEST,
    APP_RESPONSE
}

data class RankingBundle(
    val vendorBlocked: List<RankingEntry>,
    val vendorRequest: List<RankingEntry>,
    val vendorResponse: List<RankingEntry>,
    val appBlocked: List<RankingEntry>,
    val appRequest: List<RankingEntry>,
    val appResponse: List<RankingEntry>
)

data class InstalledApp(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val whitelisted: Boolean
)

data class DnsQuestion(
    val domain: String,
    val qType: Int
)

data class PacketInfo(
    val version: Int,
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val protocol: Int,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray
)

sealed interface RuleListItem {
    data class Group(val vendor: String, val count: Int, val expanded: Boolean) : RuleListItem
    data class Domain(val rule: BlockRule, val selected: Boolean, val selectionMode: Boolean) : RuleListItem
}
