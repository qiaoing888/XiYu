package com.keyscafe.unlock

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.abs

internal fun MainActivity.showLexiconManagerDialog() {
val activity = this
    pullLexiconSnapshotFromRemote()

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16f), dp(10f), dp(16f), dp(8f))
    }

    // 顶部操作按钮栏 (+ 快捷短语 | 复制导出 | 粘贴导入)
    val actionRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val accentColor = ContextCompat.getColor(this, R.color.xy_accent)
    val accentSoftColor = ContextCompat.getColor(this, R.color.xy_accent_soft)
    val titleColor = ContextCompat.getColor(this, R.color.xy_title)
    val subtitleColor = ContextCompat.getColor(this, R.color.xy_subtitle)
    val trayColor = ContextCompat.getColor(this, R.color.xy_tray)
    val dividerColor = ContextCompat.getColor(this, R.color.xy_divider)
    val dangerBgColor = ContextCompat.getColor(this, R.color.xy_status_off_bg)
    val dangerTextColor = ContextCompat.getColor(this, R.color.xy_status_off_text)
    val okBgColor = ContextCompat.getColor(this, R.color.xy_status_on_bg)
    val okTextColor = ContextCompat.getColor(this, R.color.xy_status_on_text)

    fun buildActionChip(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(11f), dp(7f), dp(11f), dp(7f))
            setTextColor(if (primary) Color.WHITE else accentColor)
            background = makeRoundedBg(if (primary) accentColor else accentSoftColor, 10f)
            setOnClickListener { onClick() }
        }
    }

    // 分类筛选栏 (全部 | 自学习 | 快捷短语 | 坏词压制)
    var currentFilter = 0 // 0: 全部, 1: 自学习, 2: 快捷短语, 3: 坏词压制
    val filterChips = mutableListOf<TextView>()

    val filterScroll = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(10f)
        layoutParams = lp
    }
    val filterRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    filterScroll.addView(filterRow)

    // 滑动提示文案
    val swipeHintView = TextView(this).apply {
        text = getString(R.string.xy_lexicon_swipe_hint)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(subtitleColor)
        setPadding(dp(2f), dp(8f), dp(2f), dp(6f))
    }

    // 空状态视图
    val emptyView = TextView(this).apply {
        text = getString(R.string.xy_lexicon_empty_hint)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTextColor(subtitleColor)
        gravity = Gravity.CENTER
        setPadding(dp(16f), dp(36f), dp(16f), dp(36f))
        background = makeRoundedBg(trayColor, 12f)
        visibility = View.GONE
    }

    // 词条列表 ListView
    val displayedEntries = mutableListOf<XyCore.UserLexiconEntry>()
    lateinit var listAdapter: BaseAdapter

    val listView = ListView(this).apply {
        divider = GradientDrawable().apply {
            setColor(dividerColor)
            setSize(1, dp(0.8f).coerceAtLeast(1))
        }
        dividerHeight = dp(0.8f).coerceAtLeast(1)
        background = makeRoundedBg(trayColor, 12f)
        setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
        clipToPadding = false
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(330f)
        )
        layoutParams = lp
    }

    fun refreshFilterChipStyles(allEntries: List<XyCore.UserLexiconEntry>) {
        val learnedCnt = allEntries.count { (it.type == "P" || it.type == "C") && !it.isCustomPhrase }
        val customCnt = allEntries.count { (it.type == "P" || it.type == "C") && it.isCustomPhrase }
        val suppressedCnt = allEntries.count { it.isSuppressed }
        val labels = listOf(
            "${getString(R.string.xy_lexicon_filter_all)} (${allEntries.size})",
            "${getString(R.string.xy_lexicon_filter_learned)} ($learnedCnt)",
            "${getString(R.string.xy_lexicon_filter_custom)} ($customCnt)",
            "${getString(R.string.xy_lexicon_filter_suppressed)} ($suppressedCnt)"
        )
        for (i in filterChips.indices) {
            val selected = (i == currentFilter)
            filterChips[i].text = labels[i]
            filterChips[i].setTextColor(if (selected) accentColor else subtitleColor)
            filterChips[i].background = makeRoundedBg(
                if (selected) accentSoftColor else trayColor,
                8f,
                if (selected) accentColor else 0,
                if (selected) 1f else 0f
            )
        }
    }

    fun reloadDisplayedList() {
        val all = XyCore.lmListUserEntries()
        refreshFilterChipStyles(all)
        displayedEntries.clear()
        for (item in all) {
            val match = when (currentFilter) {
                1 -> (item.type == "P" || item.type == "C") && !item.isCustomPhrase
                2 -> (item.type == "P" || item.type == "C") && item.isCustomPhrase
                3 -> item.isSuppressed
                else -> true
            }
            if (match) displayedEntries.add(item)
        }
        listAdapter.notifyDataSetChanged()
        if (displayedEntries.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            listView.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            listView.visibility = View.VISIBLE
        }
        updateLexiconSummaryRow()
    }

    fun handleEntryRemoveOrUnsuppress(entry: XyCore.UserLexiconEntry) {
        if (entry.isSuppressed) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.xy_lexicon_unsuppress_title)
                .setMessage(getString(R.string.xy_lexicon_unsuppress_msg, entry.word))
                .setPositiveButton(R.string.xy_lexicon_unsuppress_confirm) { _, _ ->
                    if (!XyCore.lmRemoveUserEntry("S", "", entry.word, false)) {
                        Toast.makeText(this, "解除压制失败，请重试", Toast.LENGTH_SHORT).show()
                        reloadDisplayedList()
                        return@setPositiveButton
                    }
                    pushLocalLexiconToIme(overwrite = true)
                    reloadDisplayedList()
                    Toast.makeText(this, "已解除对「${entry.word}」的压制", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.xy_lm_clear_dialog_cancel) { _, _ ->
                    reloadDisplayedList()
                }
                .setOnCancelListener { reloadDisplayedList() }
                .show()
        } else {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22f), dp(10f), dp(22f), dp(4f))
            }
            val triggerDesc = if (entry.type == "C") "上文「${entry.key}」" else "编码「${entry.key}」"
            val msgView = TextView(this).apply {
                text = getString(R.string.xy_lexicon_delete_msg, triggerDesc)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(subtitleColor)
            }
            val suppressCheck = CheckBox(this).apply {
                text = getString(R.string.xy_lexicon_suppress_checkbox)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(titleColor)
                isChecked = !entry.isCustomPhrase
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(8f)
                layoutParams = lp
            }
            container.addView(msgView)
            container.addView(suppressCheck)

            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.xy_lexicon_delete_title, entry.word))
                .setView(container)
                .setPositiveButton(R.string.xy_lexicon_delete_confirm) { _, _ ->
                    val suppress = suppressCheck.isChecked
                    if (!XyCore.lmRemoveUserEntry(entry.type, entry.key, entry.word, suppress)) {
                        Toast.makeText(this, "移除失败，请重试", Toast.LENGTH_SHORT).show()
                        reloadDisplayedList()
                        return@setPositiveButton
                    }
                    pushLocalLexiconToIme(overwrite = true)
                    reloadDisplayedList()
                    val tip = if (suppress) {
                        "已剔除「${entry.word}」并开启防复发压制"
                    } else {
                        "已从个人词库移除「${entry.word}」"
                    }
                    Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.xy_lm_clear_dialog_cancel) { _, _ ->
                    reloadDisplayedList()
                }
                .setOnCancelListener { reloadDisplayedList() }
                .show()
        }
    }

    val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

    listAdapter = object : BaseAdapter() {
        override fun getCount(): Int = displayedEntries.size
        override fun getItem(position: Int): XyCore.UserLexiconEntry = displayedEntries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val entry = getItem(position)
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                translationX = 0f
                alpha = 1f
            }

            // 左侧类型徽章
            val badgeText: String
            val badgeBg: Int
            val badgeFg: Int
            when {
                entry.isSuppressed -> {
                    badgeText = "已压制"
                    badgeBg = dangerBgColor
                    badgeFg = dangerTextColor
                }
                entry.isCustomPhrase -> {
                    badgeText = if (entry.type == "C") "上文短语" else "快捷短语"
                    badgeBg = okBgColor
                    badgeFg = okTextColor
                }
                entry.type == "C" -> {
                    badgeText = "上文联想"
                    badgeBg = accentSoftColor
                    badgeFg = accentColor
                }
                else -> {
                    badgeText = "按键记忆"
                    badgeBg = accentSoftColor
                    badgeFg = accentColor
                }
            }

            val badgeView = TextView(activity).apply {
                text = badgeText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(badgeFg)
                background = makeRoundedBg(badgeBg, 6f)
                setPadding(dp(6f), dp(3f), dp(6f), dp(3f))
            }

            // 中间词条与触发键说明
            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginStart = dp(10f)
                lp.marginEnd = dp(8f)
                layoutParams = lp
            }

            val wordView = TextView(activity).apply {
                text = entry.word
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (entry.isSuppressed) dangerTextColor else titleColor)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }

            val detailText = when {
                entry.isSuppressed -> "已压制 · 禁止自学习"
                entry.isCustomPhrase && entry.type == "C" -> "上文 · ${entry.key} · 置顶"
                entry.isCustomPhrase -> "编码 · ${entry.key} · 置顶"
                entry.type == "C" -> "上文 · ${entry.key} · ${entry.frequency} 次"
                else -> "编码 · ${entry.key} · ${entry.frequency} 次"
            }

            val subView = TextView(activity).apply {
                text = detailText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(subtitleColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            textCol.addView(wordView)
            textCol.addView(subView)

            // 右侧操作按钮 (剔除 / 解除)
            val actionBtn = TextView(activity).apply {
                text = if (entry.isSuppressed) "解除压制" else "剔除"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (entry.isSuppressed) accentColor else dangerTextColor)
                background = makeRoundedBg(
                    if (entry.isSuppressed) accentSoftColor else dangerBgColor,
                    8f
                )
                setPadding(dp(9f), dp(5f), dp(9f), dp(5f))
                setOnClickListener { handleEntryRemoveOrUnsuppress(entry) }
            }

            row.addView(badgeView)
            row.addView(textCol)
            row.addView(actionBtn)

            // 左右滑动剔除手势支持
            var downX = 0f
            var downY = 0f
            var swiping = false
            row.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        swiping = false
                        // 必须消费 DOWN，否则后续 MOVE/UP 不会派发到本监听器
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        if (!swiping && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.3f) {
                            swiping = true
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        if (swiping) {
                            v.translationX = dx * 0.85f
                            v.alpha = (1f - abs(dx) / (v.width.coerceAtLeast(300) * 0.9f)).coerceIn(0.25f, 1f)
                            true
                        } else {
                            false
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (swiping) {
                            val dx = ev.rawX - downX
                            val threshold = (v.width.coerceAtLeast(240)) * 0.28f
                            if (ev.actionMasked == MotionEvent.ACTION_UP && abs(dx) >= threshold) {
                                v.animate().translationX(if (dx > 0) v.width.toFloat() else -v.width.toFloat())
                                    .alpha(0f)
                                    .setDuration(150)
                                    .withEndAction {
                                        v.translationX = 0f
                                        v.alpha = 1f
                                        handleEntryRemoveOrUnsuppress(entry)
                                    }
                                    .start()
                            } else {
                                v.animate().translationX(0f).alpha(1f).setDuration(150).start()
                            }
                            swiping = false
                            true
                        } else {
                            // DOWN 已被消费，未滑动时手动触发点击
                            if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                            false
                        }
                    }
                    else -> false
                }
            }

            return row
        }
    }
    listView.adapter = listAdapter

    // 初始化 4 个分类筛选标签
    for (idx in 0..3) {
        val chip = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (idx > 0) lp.marginStart = dp(6f)
            layoutParams = lp
            setOnClickListener {
                currentFilter = idx
                reloadDisplayedList()
            }
        }
        filterChips.add(chip)
        filterRow.addView(chip)
    }

    // 顶部三个功能按钮绑定
    val btnAdd = buildActionChip(getString(R.string.xy_lexicon_btn_add), true) {
        showAddCustomPhraseDialog {
            reloadDisplayedList()
        }
    }
    val btnExport = buildActionChip(getString(R.string.xy_lexicon_btn_export), false) {
        exportLexiconToClipboard()
    }.apply {
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginStart = dp(8f)
        layoutParams = lp
    }
    val btnImport = buildActionChip(getString(R.string.xy_lexicon_btn_import), false) {
        showImportLexiconDialog {
            reloadDisplayedList()
        }
    }.apply {
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginStart = dp(8f)
        layoutParams = lp
    }

    actionRow.addView(btnAdd)
    actionRow.addView(btnExport)
    actionRow.addView(btnImport)

    root.addView(actionRow)
    root.addView(filterScroll)
    root.addView(swipeHintView)
    root.addView(emptyView)
    root.addView(listView)

    activeLexiconRefreshCallback = { reloadDisplayedList() }
    reloadDisplayedList()

    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.xy_lexicon_dialog_title)
        .setView(root)
        .setPositiveButton(android.R.string.ok, null)
        .setOnDismissListener {
            activeLexiconRefreshCallback = null
        }
        .show()
}

internal fun MainActivity.showAddCustomPhraseDialog(onSaved: () -> Unit) {
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20f), dp(12f), dp(20f), dp(4f))
    }
    val trayColor = ContextCompat.getColor(this, R.color.xy_tray)
    val titleColor = ContextCompat.getColor(this, R.color.xy_title)
    val hintColor = ContextCompat.getColor(this, R.color.xy_section_en)

    val editCode = EditText(this).apply {
        hint = getString(R.string.xy_lexicon_add_code_hint)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setTextColor(titleColor)
        setHintTextColor(hintColor)
        background = makeRoundedBg(trayColor, 10f)
        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        maxLines = 1
        isSingleLine = true
    }

    val editPhrase = EditText(this).apply {
        hint = getString(R.string.xy_lexicon_add_phrase_hint)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setTextColor(titleColor)
        setHintTextColor(hintColor)
        background = makeRoundedBg(trayColor, 10f)
        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        maxLines = 3
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(10f)
        layoutParams = lp
    }

    val checkContext = CheckBox(this).apply {
        text = getString(R.string.xy_lexicon_add_context_mode)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(titleColor)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(8f)
        layoutParams = lp
    }

    val contextHint = TextView(this).apply {
        text = getString(R.string.xy_lexicon_add_context_hint)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(hintColor)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(4f)
        lp.marginStart = dp(24f)
        layoutParams = lp
    }

    container.addView(editCode)
    container.addView(editPhrase)
    container.addView(checkContext)
    container.addView(contextHint)

    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.xy_lexicon_add_title)
        .setView(container)
        .setPositiveButton(R.string.xy_lexicon_add_confirm, null)
        .setNegativeButton(R.string.xy_lm_clear_dialog_cancel, null)
        .create()
    dialog.setOnShowListener {
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val rawKey = editCode.text?.toString()?.trim().orEmpty()
            val phrase = editPhrase.text?.toString()?.trim().orEmpty()
            if (rawKey.isEmpty() || phrase.isEmpty()) {
                Toast.makeText(this, "触发键与短语内容不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val isCtx = checkContext.isChecked || rawKey.startsWith("@")
            val cleanKey = if (rawKey.startsWith("@")) rawKey.substring(1).trim() else rawKey
            if (cleanKey.isEmpty()) {
                Toast.makeText(this, "触发键不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!XyCore.lmAddCustomPhrase(cleanKey, phrase, isCtx)) {
                Toast.makeText(this, "保存失败，请检查触发键格式", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pushLocalLexiconToIme(overwrite = true)
            onSaved()
            Toast.makeText(this, "已保存快捷短语：「$cleanKey -> $phrase」", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
    }
    dialog.show()
}

internal fun MainActivity.exportLexiconToClipboard() {
    val entries = XyCore.lmListUserEntries()
    if (entries.isEmpty()) {
        Toast.makeText(this, R.string.xy_lexicon_export_empty, Toast.LENGTH_SHORT).show()
        return
    }
    val tsv = XyCore.lmExportUserMemoryTsvOrNull()
    if (tsv == null) {
        Toast.makeText(this, "导出失败，请重试", Toast.LENGTH_SHORT).show()
        return
    }
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("XyUserLexiconBackup", tsv))
    Toast.makeText(
        this,
        getString(R.string.xy_lexicon_export_success, entries.size),
        Toast.LENGTH_SHORT
    ).show()
}

internal fun MainActivity.showImportLexiconDialog(onImported: () -> Unit) {
val activity = this
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20f), dp(10f), dp(20f), dp(4f))
    }
    val trayColor = ContextCompat.getColor(this, R.color.xy_tray)
    val titleColor = ContextCompat.getColor(this, R.color.xy_title)
    val subtitleColor = ContextCompat.getColor(this, R.color.xy_subtitle)
    val hintColor = ContextCompat.getColor(this, R.color.xy_section_en)
    val accentColor = ContextCompat.getColor(this, R.color.xy_accent)
    val accentSoftColor = ContextCompat.getColor(this, R.color.xy_accent_soft)

    val tipView = TextView(this).apply {
        text = getString(R.string.xy_lexicon_import_hint)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        setTextColor(subtitleColor)
    }

    val editTsv = EditText(this).apply {
        hint = "在此粘贴词库备份"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(titleColor)
        setHintTextColor(hintColor)
        typeface = Typeface.MONOSPACE
        background = makeRoundedBg(trayColor, 10f)
        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        minLines = 5
        maxLines = 9
        gravity = Gravity.TOP or Gravity.START
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(8f)
        layoutParams = lp
    }

    val btnPaste = TextView(this).apply {
        text = getString(R.string.xy_lexicon_import_paste_clipboard)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accentColor)
        background = makeRoundedBg(accentSoftColor, 8f)
        setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(8f)
        layoutParams = lp
        setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            val clipText = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
            } else ""
            if (clipText.isNotBlank()) {
                editTsv.setText(clipText)
                editTsv.setSelection(clipText.length)
            } else {
                Toast.makeText(activity, "剪贴板为空", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val checkOverwrite = CheckBox(this).apply {
        text = getString(R.string.xy_lexicon_import_overwrite)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(titleColor)
        isChecked = false
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(6f)
        layoutParams = lp
    }

    // 若剪贴板已有备份内容，自动预填方便一键导入
    runCatching {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        val clipText = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        } else ""
        if (clipText.contains("XY_USER_LEXICON_TSV") || clipText.contains("\t")) {
            editTsv.setText(clipText)
        }
    }

    container.addView(tipView)
    container.addView(btnPaste)
    container.addView(editTsv)
    container.addView(checkOverwrite)

    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.xy_lexicon_import_title)
        .setView(container)
        .setPositiveButton(R.string.xy_lexicon_import_confirm) { _, _ ->
            val raw = editTsv.text?.toString().orEmpty()
            if (raw.isBlank()) {
                Toast.makeText(this, R.string.xy_lexicon_import_fail, Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            // 勾选覆盖：清空旧词库后导入（merge=false）；未勾选：合并（merge=true）
            val overwrite = !checkOverwrite.isChecked
            Thread({
                val imported = XyCore.lmImportUserMemoryTsv(raw, overwrite)
                runOnUiThread {
                    if (imported > 0) {
                        pushLocalLexiconToIme(overwrite = true)
                        onImported()
                        Toast.makeText(
                            this,
                            getString(R.string.xy_lexicon_import_success, imported),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(this, R.string.xy_lexicon_import_fail, Toast.LENGTH_SHORT).show()
                    }
                }
            }, "xy-lexicon-import").apply { isDaemon = true }.start()
        }
        .setNegativeButton(R.string.xy_lm_clear_dialog_cancel, null)
        .show()
}

