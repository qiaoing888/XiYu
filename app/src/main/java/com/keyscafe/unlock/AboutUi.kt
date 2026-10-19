package com.keyscafe.unlock

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal const val XY_REPO_URL = "https://github.com/qiaoing888/XiYu"
private const val GPL_LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

private data class LicenseItem(
    val name: String,
    val license: String,
    val descRes: Int,
    val url: String? = null
)

/** 外部链接统一出口：交由系统浏览器打开，应用本身不申请网络权限。 */
internal fun MainActivity.openExternalUrl(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(this, R.string.xy_about_link_failed, Toast.LENGTH_SHORT).show()
    }
}

/**
 * 关于卡片弹窗：应用图标、版本、简介与仓库入口。
 * 排版沿用设置页的「顶部身份 + 下沉托盘 + 胶囊链接」层次。
 */
internal fun MainActivity.showAboutDialog() {
    val titleColor = ContextCompat.getColor(this, R.color.xy_title)
    val subtitleColor = ContextCompat.getColor(this, R.color.xy_subtitle)
    val sectionEnColor = ContextCompat.getColor(this, R.color.xy_section_en)
    val accentColor = ContextCompat.getColor(this, R.color.xy_accent)
    val accentSoftColor = ContextCompat.getColor(this, R.color.xy_accent_soft)
    val trayColor = ContextCompat.getColor(this, R.color.xy_tray)

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24f), dp(4f), dp(24f), dp(2f))
    }

    fun add(
        view: View,
        topDp: Float = 0f,
        width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    ) {
        val lp = LinearLayout.LayoutParams(width, height)
        lp.topMargin = if (topDp > 0f) dp(topDp) else 0
        container.addView(view, lp)
    }

    // 应用图标
    val iconBox = FrameLayout(this).apply {
        background = makeRoundedBg(accentSoftColor, 18f)
    }
    iconBox.addView(
        ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = FrameLayout.LayoutParams(dp(52f), dp(52f), Gravity.CENTER)
        }
    )
    add(iconBox, width = dp(64f), height = dp(64f))

    // 名称 + 版本徽章
    val nameRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    nameRow.addView(
        TextView(this).apply {
            text = getString(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(titleColor)
        }
    )
    nameRow.addView(
        TextView(this).apply {
            text = getString(R.string.xy_version_badge, BuildConfig.VERSION_NAME)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(accentColor)
            background = makeRoundedBg(accentSoftColor, 999f)
            setPadding(dp(7f), dp(2f), dp(7f), dp(2f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(7f) }
        }
    )
    add(nameRow, topDp = 14f, width = ViewGroup.LayoutParams.WRAP_CONTENT)

    add(
        TextView(this).apply {
            text = getString(R.string.xy_tagline)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(subtitleColor)
        },
        topDp = 3f,
        width = ViewGroup.LayoutParams.WRAP_CONTENT
    )

    // 简介托盘
    add(
        TextView(this).apply {
            text = getString(R.string.xy_about_dialog_msg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(subtitleColor)
            setLineSpacing(0f, 1.38f)
            background = makeRoundedBg(trayColor, 14f)
            setPadding(dp(13f), dp(11f), dp(13f), dp(11f))
        },
        topDp = 16f
    )

    // 仓库胶囊链接
    add(
        TextView(this).apply {
            text = getString(R.string.xy_about_dialog_repo)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(accentColor)
            background = makeRoundedBg(accentSoftColor, 999f)
            setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
            isClickable = true
            isFocusable = true
            setOnClickListener { openExternalUrl(XY_REPO_URL) }
        },
        topDp = 12f,
        width = ViewGroup.LayoutParams.WRAP_CONTENT
    )

    add(
        TextView(this).apply {
            text = getString(R.string.xy_about_dialog_license)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(sectionEnColor)
        },
        topDp = 10f,
        width = ViewGroup.LayoutParams.WRAP_CONTENT
    )

    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.xy_about_dialog_title)
        .setView(container)
        .setPositiveButton(R.string.xy_about_dialog_open_repo) { _, _ -> openExternalUrl(XY_REPO_URL) }
        .setNegativeButton(R.string.xy_about_dialog_close, null)
        .show()
}

/**
 * 开源许可弹窗：按「数据来源 / 代码组件 / 本项目」分组，
 * 每组一张下沉托盘，条目点击直接跳转到对应仓库。
 */
internal fun MainActivity.showLicensesDialog() {
    val titleColor = ContextCompat.getColor(this, R.color.xy_title)
    val subtitleColor = ContextCompat.getColor(this, R.color.xy_subtitle)
    val sectionEnColor = ContextCompat.getColor(this, R.color.xy_section_en)
    val accentColor = ContextCompat.getColor(this, R.color.xy_accent)
    val accentSoftColor = ContextCompat.getColor(this, R.color.xy_accent_soft)
    val dividerColor = ContextCompat.getColor(this, R.color.xy_divider)
    val ripple = selectableItemBackground()

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20f), dp(4f), dp(20f), dp(4f))
    }

    root.addView(
        TextView(this).apply {
            text = getString(R.string.xy_license_dialog_intro)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(subtitleColor)
            setLineSpacing(0f, 1.38f)
        },
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    fun addGroup(labelRes: Int, items: List<LicenseItem>) {
        root.addView(
            TextView(this).apply {
                text = getString(labelRes)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                letterSpacing = 0.12f
                setTextColor(sectionEnColor)
                setPadding(dp(4f), dp(14f), 0, dp(6f))
            }
        )

        val groupCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.xy_bg_tray)
        }

        items.forEachIndexed { index, item ->
            if (index > 0) {
                groupCard.addView(
                    View(this).apply { setBackgroundColor(dividerColor) },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1f)
                    ).apply {
                        marginStart = dp(13f)
                        marginEnd = dp(13f)
                    }
                )
            }

            val itemUrl = item.url
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13f), dp(11f), dp(13f), dp(11f))
                if (itemUrl != null) {
                    isClickable = true
                    isFocusable = true
                    foreground = ripple
                    setOnClickListener { openExternalUrl(itemUrl) }
                }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(
                TextView(this).apply {
                    text = item.name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(titleColor)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            topRow.addView(
                TextView(this).apply {
                    text = item.license
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(accentColor)
                    background = makeRoundedBg(accentSoftColor, 999f)
                    setPadding(dp(7f), dp(2f), dp(7f), dp(2f))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(8f) }
                }
            )
            row.addView(topRow)

            row.addView(
                TextView(this).apply {
                    text = getString(item.descRes)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(subtitleColor)
                    setLineSpacing(0f, 1.3f)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(3f) }
                }
            )

            item.url?.let { url ->
                row.addView(
                    TextView(this).apply {
                        text = url.removePrefix("https://")
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        setTypeface(Typeface.MONOSPACE)
                        setTextColor(accentColor)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(4f) }
                    }
                )
            }

            groupCard.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(
            groupCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    addGroup(
        R.string.xy_license_group_data,
        listOf(
            LicenseItem("rime-ice", "GPL-3.0", R.string.xy_license_data_ice, "https://github.com/iDvel/rime-ice"),
            LicenseItem("rime-wanxiang", "CC BY 4.0", R.string.xy_license_data_wanxiang, "https://github.com/amzxyz/rime-wanxiang"),
            LicenseItem("chinese-ime-lm", "GPL-3.0", R.string.xy_license_data_lm, "https://github.com/metasequoiaime/chinese-ime-lm")
        )
    )

    addGroup(
        R.string.xy_license_group_code,
        listOf(
            LicenseItem("Darts-clone", "BSD-2-Clause", R.string.xy_license_code_darts, "https://github.com/s-yata/darts-clone"),
            LicenseItem("libxposed API", "Apache-2.0", R.string.xy_license_code_xposed, "https://github.com/libxposed/api"),
            LicenseItem("AndroidX · Material", "Apache-2.0", R.string.xy_license_code_androidx, null),
            LicenseItem("Kotlin · Coroutines", "Apache-2.0", R.string.xy_license_code_kotlin, "https://kotlinlang.org")
        )
    )

    addGroup(
        R.string.xy_license_group_project,
        listOf(
            LicenseItem("汐语 XiYu", "GPL-3.0", R.string.xy_license_project_desc, XY_REPO_URL)
        )
    )

    root.addView(
        TextView(this).apply {
            text = getString(R.string.xy_license_full_text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(sectionEnColor)
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(14f), 0, 0)
        }
    )

    root.addView(
        TextView(this).apply {
            text = getString(R.string.xy_license_gpl_link)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentColor)
            setPadding(0, dp(8f), 0, dp(4f))
            isClickable = true
            isFocusable = true
            setOnClickListener { openExternalUrl(GPL_LICENSE_URL) }
        }
    )

    val scroll = ScrollView(this).apply {
        isFillViewport = false
        addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.xy_license_dialog_title)
        .setView(scroll)
        .setPositiveButton(R.string.xy_license_close, null)
        .show()
}

private fun MainActivity.selectableItemBackground(): Drawable? {
    val value = TypedValue()
    if (!theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) return null
    return ContextCompat.getDrawable(this, value.resourceId)
}
