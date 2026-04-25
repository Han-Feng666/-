package com.HanFeng.data

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.HanFeng.model.DashboardStats
import com.HanFeng.model.RankingBundle
import com.HanFeng.model.RankingEntry
import com.HanFeng.model.RankingType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StatsRepository {
    private const val PREFS = "stats_repo"
    private const val KEY_TOTAL_BLOCKED = "total_blocked"
    private const val KEY_REQUEST_TOTAL = "request_total"
    private const val KEY_RESPONSE_TOTAL = "response_total"
    private const val KEY_TODAY_DATE = "today_date"
    private const val KEY_TODAY_BLOCKED = "today_blocked"
    private const val KEY_VENDOR_BLOCKED = "vendor_blocked"
    private const val KEY_VENDOR_REQUEST = "vendor_request"
    private const val KEY_VENDOR_RESPONSE = "vendor_response"
    private const val KEY_APP_BLOCKED = "app_blocked"
    private const val KEY_APP_REQUEST = "app_request"
    private const val KEY_APP_RESPONSE = "app_response"
    private const val MAX_RANKING_ENTRIES = 300
    private val gson = Gson()
    private val dayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val updatesInternal = MutableLiveData(0L)

    val updates: LiveData<Long> = updatesInternal

    fun recordRequest(context: Context, vendor: String, appName: String) {
        val prefs = prefs(context)
        ensureToday(prefs)
        prefs.edit().putInt(KEY_REQUEST_TOTAL, prefs.getInt(KEY_REQUEST_TOTAL, 0) + 1).apply()
        incrementMap(context, KEY_VENDOR_REQUEST, vendor)
        incrementMap(context, KEY_APP_REQUEST, appName)
        notifyUpdated()
    }

    fun recordBlockedResponse(context: Context, vendor: String, appName: String) {
        val prefs = prefs(context)
        ensureToday(prefs)
        prefs.edit()
            .putInt(KEY_TODAY_BLOCKED, prefs.getInt(KEY_TODAY_BLOCKED, 0) + 1)
            .putInt(KEY_TOTAL_BLOCKED, prefs.getInt(KEY_TOTAL_BLOCKED, 0) + 1)
            .putInt(KEY_RESPONSE_TOTAL, prefs.getInt(KEY_RESPONSE_TOTAL, 0) + 1)
            .apply()
        incrementMap(context, KEY_VENDOR_BLOCKED, vendor)
        incrementMap(context, KEY_VENDOR_RESPONSE, vendor)
        incrementMap(context, KEY_APP_BLOCKED, appName)
        incrementMap(context, KEY_APP_RESPONSE, appName)
        notifyUpdated()
    }

    fun getDashboard(context: Context): DashboardStats {
        val prefs = prefs(context)
        ensureToday(prefs)
        return DashboardStats(
            todayBlocked = prefs.getInt(KEY_TODAY_BLOCKED, 0),
            totalBlocked = prefs.getInt(KEY_TOTAL_BLOCKED, 0),
            requestTotal = prefs.getInt(KEY_REQUEST_TOTAL, 0),
            responseTotal = prefs.getInt(KEY_RESPONSE_TOTAL, 0)
        )
    }

    fun getRankings(context: Context): RankingBundle {
        return RankingBundle(
            vendorBlocked = ranking(context, KEY_VENDOR_BLOCKED),
            vendorRequest = ranking(context, KEY_VENDOR_REQUEST),
            vendorResponse = ranking(context, KEY_VENDOR_RESPONSE),
            appBlocked = ranking(context, KEY_APP_BLOCKED),
            appRequest = ranking(context, KEY_APP_REQUEST),
            appResponse = ranking(context, KEY_APP_RESPONSE)
        )
    }

    fun getRanking(context: Context, type: RankingType): List<RankingEntry> {
        val key = when (type) {
            RankingType.VENDOR_BLOCKED -> KEY_VENDOR_BLOCKED
            RankingType.VENDOR_REQUEST -> KEY_VENDOR_REQUEST
            RankingType.VENDOR_RESPONSE -> KEY_VENDOR_RESPONSE
            RankingType.APP_BLOCKED -> KEY_APP_BLOCKED
            RankingType.APP_REQUEST -> KEY_APP_REQUEST
            RankingType.APP_RESPONSE -> KEY_APP_RESPONSE
        }
        return ranking(context, key)
    }

    private fun ranking(context: Context, key: String): List<RankingEntry> {
        return readMap(context, key)
            .entries
            .sortedWith(
                compareBy<Map.Entry<String, Int>> { isFallbackName(it.key) }
                    .thenByDescending { it.value }
                    .thenBy { it.key }
            )
            .map { RankingEntry(it.key, it.value) }
    }

    private fun incrementMap(context: Context, key: String, name: String) {
        val map = readMap(context, key).toMutableMap()
        val finalName = name.ifBlank { "未知来源" }
        map[finalName] = (map[finalName] ?: 0) + 1
        val trimmed = map.entries
            .sortedWith(
                compareBy<Map.Entry<String, Int>> { isFallbackName(it.key) }
                    .thenByDescending { it.value }
                    .thenBy { it.key }
            )
            .take(MAX_RANKING_ENTRIES)
            .associate { it.key to it.value }
        prefs(context).edit().putString(key, gson.toJson(trimmed)).apply()
    }

    private fun readMap(context: Context, key: String): Map<String, Int> {
        val type = object : TypeToken<Map<String, Int>>() {}.type
        return gson.fromJson(prefs(context).getString(key, "{}"), type) ?: emptyMap()
    }

    private fun isFallbackName(name: String): Boolean {
        return name.startsWith("其它") || name.contains("未知") || name == "未识别厂商"
    }

    private fun ensureToday(prefs: android.content.SharedPreferences) {
        val today = dayFormatter.format(Date())
        if (prefs.getString(KEY_TODAY_DATE, null) != today) {
            prefs.edit().putString(KEY_TODAY_DATE, today).putInt(KEY_TODAY_BLOCKED, 0).apply()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun notifyUpdated() {
        updatesInternal.postValue(System.currentTimeMillis())
    }
}
