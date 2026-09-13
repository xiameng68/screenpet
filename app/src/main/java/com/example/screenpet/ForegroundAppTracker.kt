package com.example.screenpet

import android.app.usage.UsageStatsManager
import android.content.Context

object ForegroundAppTracker {

    @Volatile var currentPackage: String = ""
        private set

    private val appNames = mapOf(
        "com.tencent.mm"          to "微信",
        "com.tencent.mobileqq"    to "QQ",
        "com.ss.android.ugc.aweme" to "抖音",
        "com.xingin.xhs"          to "小红书",
        "tv.danmaku.bili"         to "B站",
        "com.zhihu.android"       to "知乎",
        "com.taobao.taobao"       to "淘宝",
        "com.sina.weibo"          to "微博"
    )

    fun onAppChanged(pkg: String) {
        currentPackage = pkg
    }

    fun poll(context: Context) {
        if (currentPackage.isNotEmpty()) return
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                    as UsageStatsManager
            val end = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, end - 60_000, end
            ) ?: return
            stats.maxByOrNull { it.lastTimeUsed }?.let {
                currentPackage = it.packageName
            }
        } catch (_: Exception) {}
    }

    fun sceneDescription(): String {
        val pkg = currentPackage
        if (pkg.isEmpty()) return "用户正在使用手机"
        val name = appNames[pkg] ?: pkg
        return "用户正在使用「$name」"
    }
}