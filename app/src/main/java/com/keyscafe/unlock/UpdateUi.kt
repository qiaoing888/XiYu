package com.keyscafe.unlock

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Html
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

// GitHub Releases 的 Atom 订阅不走 REST API 限流，公共仓库可直接读取
private const val UPDATE_FEED_URL = "https://github.com/qiaoing888/XiYu/releases.atom"
private const val UPDATE_PREFS = "update_state"
private const val KEY_LAST_CHECK = "last_check"
private const val KEY_LATEST_TAG = "latest_tag"
private const val KEY_LATEST_URL = "latest_url"
private const val KEY_LATEST_NOTES = "latest_notes"
private const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
private const val MAX_NOTES_CHARS = 8000

private data class UpdateInfo(val tag: String, val url: String, val notesHtml: String) {
    val version: String get() = tag.trim().removePrefix("v").removePrefix("V")
}

/** 读取最近一次检查结果并刷新关于页的更新行。 */
internal fun MainActivity.renderCachedUpdateState() {
    val prefs = getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
    val current = BuildConfig.VERSION_NAME
    val latestVersion = prefs.getString(KEY_LATEST_TAG, "").orEmpty()
        .trim().removePrefix("v").removePrefix("V")

    if (latestVersion.isNotBlank() && compareVersions(latestVersion, current) > 0) {
        binding.textAboutUpdateSubtitle.text = getString(R.string.xy_about_update_new, current, latestVersion)
        binding.badgeAboutUpdate.text = getString(R.string.xy_about_update_badge_new, latestVersion)
    } else {
        binding.textAboutUpdateSubtitle.text = getString(R.string.xy_about_update_current, current)
        binding.badgeAboutUpdate.text = getString(R.string.xy_about_update_action)
    }
}

/** 打开设置页时静默检查；最多每 24 小时请求一次。 */
internal fun MainActivity.maybeAutoCheckForUpdates() {
    val last = getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
        .getLong(KEY_LAST_CHECK, 0L)
    if (System.currentTimeMillis() - last >= UPDATE_INTERVAL_MS) {
        checkForUpdates(manual = false)
    }
}

internal fun MainActivity.checkForUpdates(manual: Boolean) {
    if (updateCheckInFlight) return
    updateCheckInFlight = true
    if (manual) {
        binding.badgeAboutUpdate.text = getString(R.string.xy_about_update_checking)
    }

    Thread({
        val result = runCatching { fetchLatestRelease() }
        runOnUiThread {
            updateCheckInFlight = false
            if (isFinishing || isDestroyed) return@runOnUiThread

            if (result.isFailure) {
                XLog.e("检查更新失败", result.exceptionOrNull())
                renderCachedUpdateState()
                if (manual) {
                    Toast.makeText(this, R.string.xy_about_update_failed, Toast.LENGTH_SHORT).show()
                }
                return@runOnUiThread
            }

            val info = result.getOrNull()
            XLog.i("检查更新：current=${BuildConfig.VERSION_NAME} latest=${info?.tag ?: "none"}")
            val editor = getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            if (info != null) {
                editor.putString(KEY_LATEST_TAG, info.tag)
                    .putString(KEY_LATEST_URL, info.url)
                    .putString(KEY_LATEST_NOTES, info.notesHtml)
            } else {
                editor.remove(KEY_LATEST_TAG)
                    .remove(KEY_LATEST_URL)
                    .remove(KEY_LATEST_NOTES)
            }
            editor.apply()

            renderCachedUpdateState()
            val current = BuildConfig.VERSION_NAME
            if (info != null && compareVersions(info.version, current) > 0) {
                showUpdateDialog(info)
            } else if (manual) {
                Toast.makeText(
                    this,
                    getString(R.string.xy_about_update_is_latest, current),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }, "xy-update-check").apply { isDaemon = true }.start()
}

/** 解析 Releases Atom 订阅中的第一条 entry；没有 release 时返回 null。 */
private fun fetchLatestRelease(): UpdateInfo? {
    val conn = (URL(UPDATE_FEED_URL).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8000
        readTimeout = 8000
        setRequestProperty("Accept", "application/atom+xml")
        setRequestProperty("User-Agent", "XiYu-Android/${BuildConfig.VERSION_NAME}")
        instanceFollowRedirects = true
    }
    try {
        if (conn.responseCode != HttpURLConnection.HTTP_OK) return null

        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(conn.inputStream, "UTF-8")

        var inEntry = false
        var tag = ""
        var url = ""
        var notes = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "entry" -> inEntry = true
                    "link" -> if (inEntry && parser.getAttributeValue(null, "rel") == "alternate") {
                        val href = parser.getAttributeValue(null, "href").orEmpty()
                        if (href.contains("/releases/tag/")) {
                            val rawTag = href.substringAfterLast("/releases/tag/")
                            tag = runCatching { URLDecoder.decode(rawTag, "UTF-8") }
                                .getOrDefault(rawTag)
                            url = href
                        }
                    }
                    "content" -> if (inEntry) {
                        notes = parser.nextText().orEmpty().take(MAX_NOTES_CHARS)
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "entry") {
                    if (tag.isNotBlank()) {
                        return UpdateInfo(tag, url, notes)
                    }
                    inEntry = false
                    notes = ""
                }
            }
            event = parser.next()
        }
        return null
    } finally {
        conn.disconnect()
    }
}

private fun MainActivity.showUpdateDialog(info: UpdateInfo) {
    val subtitleColor = ContextCompat.getColor(this, R.color.xy_subtitle)
    val sectionEnColor = ContextCompat.getColor(this, R.color.xy_section_en)
    val accentColor = ContextCompat.getColor(this, R.color.xy_accent)
    val trayColor = ContextCompat.getColor(this, R.color.xy_tray)

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22f), dp(6f), dp(22f), dp(4f))
    }

    fun versionPill(text: String, filled: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(if (filled) Color.WHITE else subtitleColor)
            background = makeRoundedBg(if (filled) accentColor else trayColor, 999f)
            setPadding(dp(9f), dp(3f), dp(9f), dp(3f))
        }
    }

    val versionRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    versionRow.addView(versionPill(getString(R.string.xy_update_dialog_current, BuildConfig.VERSION_NAME), false))
    versionRow.addView(
        TextView(this).apply {
            text = "→"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(accentColor)
            setPadding(dp(8f), 0, dp(8f), 0)
        }
    )
    versionRow.addView(versionPill(getString(R.string.xy_update_dialog_latest, info.version), true))
    container.addView(versionRow)

    container.addView(
        TextView(this).apply {
            text = getString(R.string.xy_update_dialog_notes_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.12f
            setTextColor(sectionEnColor)
            setPadding(dp(4f), dp(16f), 0, dp(6f))
        }
    )

    val notes = if (info.notesHtml.isBlank()) {
        getString(R.string.xy_update_dialog_empty_notes)
    } else {
        runCatching {
            Html.fromHtml(info.notesHtml, Html.FROM_HTML_MODE_LEGACY)
        }.getOrElse { info.notesHtml }
    }
    container.addView(
        TextView(this).apply {
            text = notes
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(subtitleColor)
            setLineSpacing(0f, 1.38f)
            maxLines = 12
            ellipsize = TextUtils.TruncateAt.END
            background = makeRoundedBg(trayColor, 14f)
            setPadding(dp(13f), dp(11f), dp(13f), dp(11f))
        }
    )

    container.addView(
        TextView(this).apply {
            text = getString(R.string.xy_update_dialog_notes_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(sectionEnColor)
            setPadding(dp(4f), dp(8f), 0, 0)
        }
    )

    MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.xy_update_dialog_title, info.version))
        .setView(container)
        .setPositiveButton(R.string.xy_update_dialog_download) { _, _ ->
            openExternalUrl(info.url.ifBlank { "$XY_REPO_URL/releases" })
        }
        .setNegativeButton(R.string.xy_update_dialog_later, null)
        .show()
}

/** 按数字段比较版本号，忽略前缀 v；2.1 与 2.1.0 视为相同。 */
internal fun compareVersions(a: String, b: String): Int {
    fun parts(value: String): List<Int> = value.trim()
        .removePrefix("v").removePrefix("V")
        .split(Regex("[^0-9]+"))
        .filter { it.isNotEmpty() }
        .map { it.toIntOrNull() ?: 0 }

    val pa = parts(a)
    val pb = parts(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrElse(i) { 0 }
        val y = pb.getOrElse(i) { 0 }
        if (x != y) return if (x > y) 1 else -1
    }
    return 0
}
