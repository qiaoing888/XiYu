package com.keyscafe.unlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.keyscafe.unlock.databinding.ActivityMainBinding

/**
 * 汐语控制面板
 */
class MainActivity : AppCompatActivity() {

    internal lateinit var binding: ActivityMainBinding
    private var programmaticChange = false
    private var unregisterState: (() -> Unit)? = null
    private var currentSandboxCalc: CalcResult? = null
    internal var lexiconExportReceiver: BroadcastReceiver? = null
    internal var activeLexiconRefreshCallback: (() -> Unit)? = null
    internal var updateCheckInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        XyCore.lmInit(filesDir?.absolutePath)

        initHeader()
        initSwitches()
        initAbout()
        initSandbox()
        registerLexiconExportReceiver()
        pullLexiconSnapshotFromRemote()
        checkSecurityEnvironment()

        unregisterState = Prefs.onStateChanged {
            runOnUiThread {
                showActivationState()
                syncSwitches()
                updateLexiconSummaryRow()
                // 服务绑定后才可能有广播令牌，补拉一次词库快照
                pullLexiconSnapshotFromRemote()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        showActivationState()
        syncSwitches()
        pullLexiconSnapshotFromRemote()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterState?.invoke()
        unregisterState = null
        lexiconExportReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        lexiconExportReceiver = null
    }

    private fun initHeader() {
        binding.badgeVersion.text = getString(R.string.xy_version_badge, BuildConfig.VERSION_NAME)
        binding.statusPill.setOnClickListener { showStatusDialog() }
        showActivationState()
    }

    private fun initSwitches() {
        bindSwitch(binding.switchVoiceGate, binding.rowVoiceGate, binding.boxVoiceGate, binding.iconVoiceGate, Prefs.VOICE_GATE)
        bindSwitch(binding.switchVoiceMelt, binding.rowVoiceMelt, binding.boxVoiceMelt, binding.iconVoiceMelt, Prefs.VOICE_MELT)
        bindSwitch(binding.switchOtpEnhance, binding.rowOtpEnhance, binding.boxOtpEnhance, binding.iconOtpEnhance, Prefs.OTP_ENHANCE)

        bindSwitch(binding.switchCalc, binding.rowCalc, binding.boxCalc, binding.iconCalc, Prefs.CALC_ENABLED)
        bindSwitch(binding.switchCalcReplace, binding.rowCalcReplace, binding.boxCalcReplace, binding.iconCalcReplace, Prefs.CALC_REPLACE_EXPR)
        bindSwitch(binding.switchCalcRmb, binding.rowCalcRmb, binding.boxCalcRmb, binding.iconCalcRmb, Prefs.CALC_RMB)
        bindSwitch(binding.switchCalcRadix, binding.rowCalcRadix, binding.boxCalcRadix, binding.iconCalcRadix, Prefs.CALC_RADIX) { enabled ->
            binding.layoutRadixRules.visibility = if (enabled) View.VISIBLE else View.GONE
        }

        // 1. 通用开关：智能记忆与重排 · 通用 (适配全拼/双拼/五笔/九键)
        bindSwitch(binding.switchLm, binding.rowLm, binding.boxLm, binding.iconLm, Prefs.LM_ENABLED) { enabled ->
            if (!programmaticChange) {
                Prefs.write(Prefs.LM_LEARNING, enabled)
                Prefs.write(Prefs.LM_NEXT_WORD, enabled)
            }
        }

        // 2. 全拼专属开关：全拼整句与词库 · 全拼 (双拼/五笔用户可单独关闭)
        bindSwitch(
            binding.switchLmQuanpin,
            binding.rowLmQuanpin,
            binding.boxLmQuanpin,
            binding.iconLmQuanpin,
            Prefs.LM_QUANPIN_MODE
        ) { quanpinEnabled ->
            XyCore.lmSetQuanpinMode(quanpinEnabled)
        }

        // 3. 个人词库管理入口
        binding.rowLmLexiconManager.setOnClickListener { showLexiconManagerDialog() }
        binding.layoutLexiconGuide.setOnClickListener { showLexiconManagerDialog() }

        binding.rowLmClearMemory.setOnClickListener { showClearMemoryDialog() }

        bindSwitch(binding.switchKeysCafe, binding.rowKeysCafe, binding.boxKeysCafe, binding.iconKeysCafe, Prefs.KEYSCAFE_BYPASS)
        bindSwitch(binding.switchLab, binding.rowLab, binding.boxLab, binding.iconLab, Prefs.LAB)

        updateDictEngineStatus()
        updateLexiconSummaryRow()
    }

    private fun bindSwitch(
        switch: MaterialSwitch,
        row: View,
        iconBox: FrameLayout,
        icon: ImageView,
        key: String,
        onChanged: ((Boolean) -> Unit)? = null
    ) {
        programmaticChange = true
        val initial = Prefs.read(key)
        switch.isChecked = initial
        updateIconHighlight(iconBox, icon, initial)
        onChanged?.invoke(initial)
        programmaticChange = false

        switch.setOnCheckedChangeListener { _, checked ->
            if (!programmaticChange) {
                Prefs.write(key, checked)
            }
            updateIconHighlight(iconBox, icon, checked)
            onChanged?.invoke(checked)
        }
        row.setOnClickListener { switch.toggle() }
    }

    private fun initAbout() {
        binding.textAboutAppSubtitle.text = getString(R.string.xy_about_app_desc, BuildConfig.VERSION_NAME)
        binding.rowAboutRepo.setOnClickListener { openExternalUrl(XY_REPO_URL) }
        binding.rowAboutLicenses.setOnClickListener { showLicensesDialog() }
        binding.rowAboutApp.setOnClickListener { showAboutDialog() }
        binding.rowAboutUpdate.setOnClickListener { checkForUpdates(manual = true) }
        renderCachedUpdateState()
        maybeAutoCheckForUpdates()
    }

    private fun updateIconHighlight(box: FrameLayout, icon: ImageView, active: Boolean) {
        val boxColor = ContextCompat.getColor(this, if (active) R.color.xy_accent_soft else R.color.xy_icon_box_off)
        val iconColor = ContextCompat.getColor(this, if (active) R.color.xy_accent else R.color.xy_icon_tint_off)
        box.backgroundTintList = ColorStateList.valueOf(boxColor)
        icon.imageTintList = ColorStateList.valueOf(iconColor)
    }

    private fun syncSwitches() {
        programmaticChange = true

        syncSingleSwitch(binding.switchVoiceGate, binding.boxVoiceGate, binding.iconVoiceGate, Prefs.VOICE_GATE)
        syncSingleSwitch(binding.switchVoiceMelt, binding.boxVoiceMelt, binding.iconVoiceMelt, Prefs.VOICE_MELT)
        syncSingleSwitch(binding.switchOtpEnhance, binding.boxOtpEnhance, binding.iconOtpEnhance, Prefs.OTP_ENHANCE)

        syncSingleSwitch(binding.switchCalc, binding.boxCalc, binding.iconCalc, Prefs.CALC_ENABLED)
        syncSingleSwitch(binding.switchCalcReplace, binding.boxCalcReplace, binding.iconCalcReplace, Prefs.CALC_REPLACE_EXPR)
        syncSingleSwitch(binding.switchCalcRmb, binding.boxCalcRmb, binding.iconCalcRmb, Prefs.CALC_RMB)

        val radix = Prefs.read(Prefs.CALC_RADIX)
        binding.switchCalcRadix.isChecked = radix
        updateIconHighlight(binding.boxCalcRadix, binding.iconCalcRadix, radix)
        binding.layoutRadixRules.visibility = if (radix) View.VISIBLE else View.GONE

        syncSingleSwitch(binding.switchLm, binding.boxLm, binding.iconLm, Prefs.LM_ENABLED)
        syncSingleSwitch(binding.switchLmQuanpin, binding.boxLmQuanpin, binding.iconLmQuanpin, Prefs.LM_QUANPIN_MODE)

        XyCore.lmSetLongPhraseAggressive(Prefs.read(Prefs.LM_LONG_PHRASE_AGGRESSIVE))
        XyCore.lmSetQuanpinMode(Prefs.read(Prefs.LM_QUANPIN_MODE))

        syncSingleSwitch(binding.switchKeysCafe, binding.boxKeysCafe, binding.iconKeysCafe, Prefs.KEYSCAFE_BYPASS)
        syncSingleSwitch(binding.switchLab, binding.boxLab, binding.iconLab, Prefs.LAB)
        updateDictEngineStatus()

        programmaticChange = false
    }

    private fun syncSingleSwitch(switch: MaterialSwitch, box: FrameLayout, icon: ImageView, key: String) {
        val checked = Prefs.read(key)
        switch.isChecked = checked
        updateIconHighlight(box, icon, checked)
    }

    // =========================================================================
    // 个人词库跨进程同步与管理 UI
    // =========================================================================

    private fun registerLexiconExportReceiver() {
        if (lexiconExportReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == LmCandidateHooks.ACTION_LEXICON_EXPORT_RESULT) {
                    val token = Prefs.broadcastToken()
                    if (token.isEmpty() || intent.getStringExtra(LmCandidateHooks.EXTRA_BROADCAST_TOKEN) != token) {
                        return
                    }
                    val tsv = intent.getStringExtra(LmCandidateHooks.EXTRA_LEXICON_TSV) ?: return
                    if (tsv.isNotBlank()) {
                        Thread({
                            XyCore.lmImportUserMemoryTsv(tsv, false)
                            XyCore.lmExportUserMemoryTsvOrNull()?.let {
                                Prefs.writeString(Prefs.LM_USER_LEXICON_TSV, it)
                            }
                            runOnUiThread {
                                updateLexiconSummaryRow()
                                activeLexiconRefreshCallback?.invoke()
                            }
                        }, "xy-lexicon-export-result").apply { isDaemon = true }.start()
                    }
                }
            }
        }
        val filter = IntentFilter(LmCandidateHooks.ACTION_LEXICON_EXPORT_RESULT)
        runCatching {
            // 发送方是三星键盘进程，不能用本应用的签名权限限制
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            lexiconExportReceiver = receiver
        }
    }

    internal fun pullLexiconSnapshotFromRemote() {
        val remoteTsv = Prefs.readString(Prefs.LM_USER_LEXICON_TSV, "")
        if (remoteTsv.isNotBlank()) {
            XyCore.lmImportUserMemoryTsv(remoteTsv, false)
        }
        updateLexiconSummaryRow()
        runCatching {
            // XposedService 尚未绑定成功时没有可写令牌，等绑定回调再拉取
            val token = Prefs.broadcastToken()
            if (token.isEmpty()) return@runCatching
            val reqIntent = Intent(LmCandidateHooks.ACTION_REQUEST_LEXICON_EXPORT).apply {
                putExtra(LmCandidateHooks.EXTRA_BROADCAST_TOKEN, token)
                setPackage("com.samsung.android.honeyboard")
            }
            sendBroadcast(reqIntent)
        }
    }

    internal fun pushLocalLexiconToIme(overwrite: Boolean = true) {
        val fullTsv = XyCore.lmExportUserMemoryTsvOrNull()
        if (fullTsv == null) {
            XLog.e("导出用户词库失败，取消覆盖推送", null)
            return
        }
        // 由 Prefs 统一写偏好并广播，避免重复通知
        Prefs.syncLexiconTsvToRemote(fullTsv, overwrite)
        updateLexiconSummaryRow()
    }

    internal fun updateLexiconSummaryRow() {
        val entries = XyCore.lmListUserEntries()
        val learnedCount = entries.count { (it.type == "P" || it.type == "C") && !it.isCustomPhrase }
        val customCount = entries.count { (it.type == "P" || it.type == "C") && it.isCustomPhrase }
        val suppressedCount = entries.count { it.isSuppressed }
        val totalCount = entries.size

        if (totalCount > 0) {
            binding.textLmLexiconSubtitle.text = getString(
                R.string.xy_lm_lexicon_mgr_desc_stats,
                learnedCount,
                customCount,
                suppressedCount
            )
            binding.badgeLmLexiconCount.text = getString(R.string.xy_lm_lexicon_mgr_count_badge, totalCount)
        } else {
            binding.textLmLexiconSubtitle.text = getString(R.string.xy_lm_lexicon_mgr_desc)
            binding.badgeLmLexiconCount.text = getString(R.string.xy_lm_lexicon_mgr_action)
        }
    }

    internal fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).toInt()
    }

    internal fun makeRoundedBg(fillColor: Int, cornerDp: Float, strokeColor: Int = 0, strokeWidthDp: Float = 0f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp).toFloat()
            setColor(fillColor)
            if (strokeWidthDp > 0f && strokeColor != 0) {
                setStroke(dp(strokeWidthDp).coerceAtLeast(1), strokeColor)
            }
        }
    }

    private fun updateDictEngineStatus() {
        val isReady = XyCore.isDictReady() && XyCore.isLmReady()

        val boxColor = ContextCompat.getColor(this, if (isReady) R.color.xy_status_on_bg else R.color.xy_icon_box_off)
        val iconColor = ContextCompat.getColor(this, if (isReady) R.color.xy_status_on_dot else R.color.xy_icon_tint_off)
        binding.boxDict.backgroundTintList = ColorStateList.valueOf(boxColor)
        binding.iconDict.imageTintList = ColorStateList.valueOf(iconColor)

        val dotColor = ContextCompat.getColor(this, if (isReady) R.color.xy_status_on_dot else R.color.xy_status_off_dot)
        val textColor = ContextCompat.getColor(this, if (isReady) R.color.xy_status_on_text else R.color.xy_status_off_text)
        val pillBg = ContextCompat.getColor(this, if (isReady) R.color.xy_status_on_bg else R.color.xy_status_off_bg)

        binding.dictStatusDot.backgroundTintList = ColorStateList.valueOf(dotColor)
        binding.dictStatusText.text = getString(if (isReady) R.string.xy_engine_ready else R.string.xy_engine_unready)
        binding.dictStatusText.setTextColor(textColor)
        binding.dictStatusPill.backgroundTintList = ColorStateList.valueOf(pillBg)

        binding.textDictSubtitle.text = getString(if (isReady) R.string.xy_dict_desc_dynamic else R.string.xy_dict_desc_offline)

        val rawInfo = if (isReady) XyCore.getDictInfo() else ""
        binding.textDictInfo.text = rawInfo
            .substringAfter(" · ", rawInfo)
            .ifBlank { getString(R.string.xy_engine_card_unloaded) }
    }

    private fun showActivationState() {
        val active = Prefs.isActive()
        val dotColor = ContextCompat.getColor(this, if (active) R.color.xy_status_on_dot else R.color.xy_status_off_dot)
        val textColor = ContextCompat.getColor(this, if (active) R.color.xy_status_on_text else R.color.xy_status_off_text)
        val pillBg = ContextCompat.getColor(this, if (active) R.color.xy_status_on_bg else R.color.xy_status_off_bg)

        binding.statusDot.backgroundTintList = ColorStateList.valueOf(dotColor)
        binding.statusText.text = getString(if (active) R.string.xy_status_active else R.string.xy_status_inactive)
        binding.statusText.setTextColor(textColor)
        binding.statusPill.backgroundTintList = ColorStateList.valueOf(pillBg)
    }

    private fun showStatusDialog() {
        val active = Prefs.isActive()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.xy_status_dialog_title)
            .setMessage(if (active) R.string.xy_status_active_msg else R.string.xy_status_inactive_msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showClearMemoryDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.xy_lm_clear_dialog_title)
            .setMessage(R.string.xy_lm_clear_dialog_message)
            .setPositiveButton(R.string.xy_lm_clear_dialog_confirm) { _, _ ->
                XyCore.lmClearUserMemory()
                Prefs.writeString(Prefs.LM_USER_LEXICON_TSV, "")
                Prefs.writeLong(Prefs.LM_CLEAR_TIMESTAMP, System.currentTimeMillis())
                runCatching {
                    val intent = Intent(LmCandidateHooks.ACTION_CLEAR_LM_MEMORY).apply {
                        setPackage("com.samsung.android.honeyboard")
                        putExtra(LmCandidateHooks.EXTRA_BROADCAST_TOKEN, Prefs.broadcastToken())
                    }
                    sendBroadcast(intent)
                }
                updateLexiconSummaryRow()
                Toast.makeText(this, R.string.xy_lm_clear_toast_success, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.xy_lm_clear_dialog_cancel, null)
            .show()
    }

    private fun initSandbox() {
        val setPreset = { text: String ->
            binding.editSandboxInput.setText(text)
            binding.editSandboxInput.setSelection(text.length)
        }

        binding.btnPreset1.setOnClickListener { setPreset("128*34=") }
        binding.btnPreset2.setOnClickListener { setPreset("3500大写=") }
        binding.btnPreset3.setOnClickListener { setPreset("0xFF=") }
        binding.btnPreset4.setOnClickListener { setPreset("255hex=") }

        binding.editSandboxInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                evaluateSandbox(s?.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.pillCandidate.setOnClickListener {
            val calc = currentSandboxCalc ?: return@setOnClickListener
            val replace = Prefs.read(Prefs.CALC_REPLACE_EXPR)
            if (replace) {
                binding.editSandboxInput.setText(calc.resultText)
                binding.editSandboxInput.setSelection(calc.resultText.length)
            } else {
                val updated = binding.editSandboxInput.text.toString() + calc.resultText
                binding.editSandboxInput.setText(updated)
                binding.editSandboxInput.setSelection(updated.length)
            }
        }
    }

    private fun evaluateSandbox(text: String?) {
        if (text.isNullOrBlank()) {
            binding.layoutCandidatePreview.visibility = View.GONE
            currentSandboxCalc = null
            return
        }

        val eval = MathEvaluator.evaluate(
            text,
            requireEqualSign = true,
            enableRmb = Prefs.read(Prefs.CALC_RMB),
            enableRadix = Prefs.read(Prefs.CALC_RADIX)
        )

        currentSandboxCalc = eval
        if (eval != null) {
            binding.pillCandidate.text = eval.displayText
            binding.layoutCandidatePreview.visibility = View.VISIBLE
        } else {
            binding.layoutCandidatePreview.visibility = View.GONE
        }
    }

    private fun checkSecurityEnvironment() {
        val status = XyCore.checkSecurityStatus()
        if (status < 0) {
            XLog.e("安全检查执行失败", null)
            return
        }
        if (status != 0) {
            val title = if (status == 1) "应用完整性校验未通过" else "检测到调试与注入环境"
            val message = if (status == 1) {
                "检测到底层签名与官方发行版本不匹配，可能存在未经授权的二次修改或恶意打包。为保护核心算法与数据安全，部分底层核心功能已被锁定。"
            } else {
                "检测到内存注入或动态调试框架（如 Frida）正在运行。为防止算法被恶意逆向与抓取，已启动安全保护机制。"
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("我知道了") { _, _ -> }
                .show()
        }
    }
}