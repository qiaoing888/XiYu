package com.keyscafe.unlock

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal const val GBOARD_SLOT_COUNT = 6

internal fun calculateCandidateSlotWeights(
    textWidths: List<Float>,
    availableWidth: Int,
    slotCount: Int
): List<Float> {
    if (textWidths.isEmpty() || availableWidth <= 0 || slotCount <= 0) return emptyList()
    val slotWidth = availableWidth.toFloat() / slotCount
    if (slotWidth <= 0f) return emptyList()
    return textWidths.map { width ->
        val slots = ceil(width / slotWidth).toInt().coerceIn(1, slotCount)
        slots.toFloat() / slotCount
    }
}

internal fun countFirstRowCandidates(weights: List<Float>): Int {
    if (weights.isEmpty()) return 0
    var sum = 0f
    var count = 0
    for (w in weights) {
        if (sum + w > 1.001f) break
        sum += w
        count++
    }
    return count.coerceAtLeast(1)
}

internal fun shouldShowHorizontalRightDivider(
    position: Int,
    totalCount: Int,
    firstRowCount: Int
): Boolean {
    if (totalCount <= 1 || position < 0 || position >= totalCount - 1) return false
    if (firstRowCount > 0 && position == firstRowCount - 1) return false
    return true
}

/**
 * 候选栏与展开面板布局调整：
 * 1. 顶栏与展开面板按 6 列网格计算宽度，单字固定一行 6 个，多字词按实际测量宽度向上取整占格
 * 2. 禁用原生末尾空白占位项，候选不足一屏时靠左排列
 * 3. 同步首屏候选个数至 eb0.a.m(firstRowCount)，保持展开面板起始索引一致
 * 4. 在相邻候选项之间绘制竖线分隔符，首屏末项（紧贴展开按钮处）不画竖线
 */
object CandidateLayoutHooks {

    @Volatile
    private var currentFirstRowCount: Int = GBOARD_SLOT_COUNT
    private var weightsMethod: Method? = null
    private var setSlotCountMethod: Method? = null
    private var setFirstRowCountMethod: Method? = null
    private var candidateRepoMethod: Method? = null
    private var repoSetFirstRowCountMethod: Method? = null
    private var widthProviderMethod: Method? = null
    private var availableWidthMethod: Method? = null
    private var measureTextMethod: Method? = null
    private var holderBindingMethod: Method? = null
    private var adapterItemCountMethod: Method? = null
    private var bindingGetRootMethod: Method? = null
    private var bindingMainLabelField: Field? = null
    private var expandRowWidthMethod: Method? = null
    private var expandHolderLayoutMethod: Method? = null

    private class GboardDividerDrawable(
        mainLabel: TextView,
        var showRightDivider: Boolean
    ) : Drawable() {
        private val labelRef = WeakReference(mainLabel)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        override fun draw(canvas: Canvas) {
            if (!showRightDivider) return
            val label = labelRef.get() ?: return
            if (label.text.isNullOrBlank()) return
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return

            val density = label.resources.displayMetrics.density
            val strokeWidth = max(1f, density * 1f)
            val dividerHeight = min(b.height() * 0.38f, 14f * density)
            val cy = b.exactCenterY()
            val x = b.right - strokeWidth * 0.5f

            val textColor = label.currentTextColor
            paint.strokeWidth = strokeWidth
            paint.color = (textColor and 0x00FFFFFF) or (0x2E shl 24)
            canvas.drawLine(x, cy - dividerHeight * 0.5f, x, cy + dividerHeight * 0.5f, paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    fun install(module: XposedModule, classLoader: ClassLoader) {
        hookColumnPolicy(module, classLoader)
        hookHorizontalBarLayout(module, classLoader)
        hookHorizontalItemBind(module, classLoader)
        hookExpandedPanelLayout(module, classLoader)
        hookHideSogouLogo(module, classLoader)
    }

    private fun hookColumnPolicy(module: XposedModule, classLoader: ClassLoader) {
        val policyClass = Reflect.findClassIfExists("nb0.c", classLoader) ?: return

        policyClass.declaredMethods.firstOrNull {
            it.name == "c" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == java.lang.Boolean.TYPE &&
                it.parameterTypes[1] == java.lang.Boolean.TYPE &&
                it.returnType == java.lang.Integer.TYPE
        }?.let { m ->
            XLog.safe("CandidateLayoutHooks.nb0_c_c") {
                module.hook(m).intercept { chain ->
                    if (!LmCandidateHooks.isChineseLanguageActive(classLoader)) {
                        return@intercept chain.proceed()
                    }
                    GBOARD_SLOT_COUNT
                }
            }
        }

        policyClass.declaredMethods.firstOrNull {
            it.name == "h" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == java.lang.Boolean.TYPE &&
                it.returnType == java.lang.Integer.TYPE
        }?.let { m ->
            XLog.safe("CandidateLayoutHooks.nb0_c_h") {
                module.hook(m).intercept { chain ->
                    if (!LmCandidateHooks.isChineseLanguageActive(classLoader)) {
                        return@intercept chain.proceed()
                    }
                    GBOARD_SLOT_COUNT
                }
            }
        }
    }

    private fun hookHorizontalBarLayout(module: XposedModule, classLoader: ClassLoader) {
        val target = Reflect.findClassIfExists("ob0.z0", classLoader) ?: return
        val layoutMethod = target.declaredMethods.firstOrNull {
            it.name == "m0" &&
                it.parameterTypes.size == 3 &&
                java.util.List::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                it.parameterTypes[1] == java.lang.Boolean.TYPE &&
                it.parameterTypes[2] == java.lang.Boolean.TYPE
        } ?: return

        weightsMethod = runCatching { target.getMethod("p") }.getOrNull()
        setSlotCountMethod = runCatching { target.getMethod("K", java.lang.Integer.TYPE) }.getOrNull()
        setFirstRowCountMethod = runCatching { target.getMethod("J", java.lang.Integer.TYPE) }.getOrNull()
        candidateRepoMethod = runCatching { target.getMethod("i") }.getOrNull()
        widthProviderMethod = runCatching { target.getMethod("u") }.getOrNull()

        XLog.safe("CandidateLayoutHooks.m0") {
            module.hook(layoutMethod).intercept { chain ->
                val controller = chain.thisObject ?: return@intercept chain.proceed()
                @Suppress("UNCHECKED_CAST")
                val candidates = chain.getArg(0) as? List<Any>
                if (candidates.isNullOrEmpty()) {
                    return@intercept chain.proceed()
                }

                val isChinese = LmCandidateHooks.isChineseLanguageActive(classLoader)
                if (!isChinese) {
                    return@intercept chain.proceed()
                }

                // 过滤表情与颜文字
                if (!ConfigStore.lmEmoticon && candidates is MutableList<*>) {
                    @Suppress("UNCHECKED_CAST")
                    (candidates as MutableList<Any>).removeAll { cand ->
                        val text = LmCandidateHooks.getCandidateTextForLayout(cand)
                        isEmoticonOrKaomoji(text)
                    }
                }

                val hasChinese = candidates.any { cand ->
                    val text = LmCandidateHooks.getCandidateTextForLayout(cand)
                    text != null && containsChinese(text)
                }
                if (!hasChinese) {
                    return@intercept chain.proceed()
                }

                // 锁定 6 格基准槽位（阻止 ob0.z0.R 将单字槽位翻倍为 12/14），
                // 并以 arg1=false 调用原生 m0 以禁止向候选列表末尾塞入空白虚拟 dn.b("") 占位项
                runCatching { setSlotCountMethod?.invoke(controller, GBOARD_SLOT_COUNT) }
                val result = chain.proceed(arrayOf(candidates, false, false))

                runCatching { applyLeftAlignedWeights(controller, candidates) }
                    .onFailure { XLog.e("候选宽度左对齐失败", it) }
                result
            }
        }
    }

    private fun hookHorizontalItemBind(module: XposedModule, classLoader: ClassLoader) {
        val adapterClass = Reflect.findClassIfExists("ob0.w0", classLoader) ?: return
        val holderClass = Reflect.findClassIfExists("ob0.w0\$a", classLoader) ?: return
        val bindMethodWithPosition = adapterClass.declaredMethods.firstOrNull {
            it.name == "o" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == holderClass &&
                it.parameterTypes[1] == java.lang.Integer.TYPE
        } ?: return

        holderBindingMethod = runCatching { holderClass.getMethod("h") }.getOrNull()
        adapterItemCountMethod = runCatching { adapterClass.getMethod("getItemCount") }.getOrNull()

        XLog.safe("CandidateLayoutHooks.itemBind") {
            module.hook(bindMethodWithPosition).intercept { chain ->
                val result = chain.proceed()
                if (!LmCandidateHooks.isChineseLanguageActive(classLoader)) {
                    return@intercept result
                }
                val adapter = chain.thisObject
                val holder = chain.getArg(0)
                val position = chain.getArg(1) as? Int ?: 0
                if (adapter != null && holder != null) {
                    runCatching {
                        val totalCount = (adapterItemCountMethod?.invoke(adapter) as? Int) ?: 0
                        val showDivider = shouldShowHorizontalRightDivider(
                            position = position,
                            totalCount = totalCount,
                            firstRowCount = currentFirstRowCount
                        )
                        styleCandidateHolder(holder, showRightDivider = showDivider)
                    }.onFailure { XLog.e("候选项样式绑定失败", it) }
                }
                result
            }
        }
    }

    private fun hookExpandedPanelLayout(module: XposedModule, classLoader: ClassLoader) {
        val expandAdapterClass = Reflect.findClassIfExists("ob0.l0", classLoader) ?: return
        val expandHolderClass = Reflect.findClassIfExists("ob0.l0\$a", classLoader)

        expandRowWidthMethod = runCatching {
            expandAdapterClass.getDeclaredMethod("w", java.lang.Integer.TYPE).apply { isAccessible = true }
        }.getOrNull()

        // Hook ob0.l0.x(String): 展开面板候选项宽度按 6 列网格向上取整对齐
        expandAdapterClass.declaredMethods.firstOrNull {
            it.name == "x" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java &&
                it.returnType == java.lang.Integer.TYPE
        }?.let { measureMethod ->
            measureMethod.isAccessible = true
            XLog.safe("CandidateLayoutHooks.l0_x") {
                module.hook(measureMethod).intercept { chain ->
                    val measuredPx = (chain.proceed() as? Int) ?: return@intercept 0
                    val adapter = chain.thisObject ?: return@intercept measuredPx
                    val text = chain.getArg(0) as? String ?: return@intercept measuredPx
                    if (countChineseCharacters(text) <= 0) return@intercept measuredPx

                    val rowWidth = runCatching {
                        expandRowWidthMethod?.invoke(adapter, 0) as? Int
                    }.getOrNull() ?: return@intercept measuredPx
                    if (rowWidth <= 0) return@intercept measuredPx

                    val colWidth = rowWidth / GBOARD_SLOT_COUNT
                    if (colWidth <= 0) return@intercept measuredPx
                    val cols = ceil(measuredPx.toFloat() / colWidth).toInt().coerceIn(1, GBOARD_SLOT_COUNT)
                    max(measuredPx, cols * colWidth - 2)
                }
            }
        }

        // Hook ob0.l0.I(ob0.l0$a, int): 为展开面板每行候选项挂载竖线分隔符 `|`
        if (expandHolderClass != null) {
            expandHolderLayoutMethod = runCatching { expandHolderClass.getMethod("k") }.getOrNull()
            expandAdapterClass.declaredMethods.firstOrNull {
                it.name == "I" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == expandHolderClass &&
                    it.parameterTypes[1] == java.lang.Integer.TYPE
            }?.let { bindRowMethod ->
                XLog.safe("CandidateLayoutHooks.l0_I") {
                    module.hook(bindRowMethod).intercept { chain ->
                        val result = chain.proceed()
                        val rowHolder = chain.getArg(0) ?: return@intercept result
                        if (LmCandidateHooks.isChineseLanguageActive(classLoader)) {
                            runCatching { styleExpandedRow(rowHolder) }
                        }
                        result
                    }
                }
            }
        }
    }

    private fun styleCandidateHolder(holder: Any, showRightDivider: Boolean) {
        val binding = holderBindingMethod?.invoke(holder) ?: return
        if (bindingGetRootMethod == null) {
            bindingGetRootMethod = runCatching { binding.javaClass.getMethod("getRoot") }.getOrNull()
        }
        val root = bindingGetRootMethod?.invoke(binding) as? View ?: return
        if (bindingMainLabelField == null) {
            bindingMainLabelField = runCatching { binding.javaClass.getField("mainLabel") }.getOrNull()
        }
        val mainLabel = runCatching { bindingMainLabelField?.get(binding) as? TextView }.getOrNull() ?: return

        if (showRightDivider) {
            mainLabel.apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }
        }
        attachOrUpdateDivider(root, mainLabel, showRightDivider)
    }

    private fun styleExpandedRow(rowHolder: Any) {
        val rowLayout = expandHolderLayoutMethod?.invoke(rowHolder) as? ViewGroup ?: return
        val childCount = rowLayout.childCount
        for (i in 0 until childCount) {
            val child = rowLayout.getChildAt(i) ?: continue
            val label = findFirstTextView(child) ?: continue
            label.gravity = Gravity.CENTER
            label.textAlignment = View.TEXT_ALIGNMENT_CENTER
            attachOrUpdateDivider(child, label, showRightDivider = i < childCount - 1)
        }
    }

    private fun findFirstTextView(view: View): TextView? {
        if (view is TextView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findFirstTextView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun attachOrUpdateDivider(root: View, mainLabel: TextView, showRightDivider: Boolean) {
        val existing = root.foreground
        if (existing is GboardDividerDrawable) {
            existing.showRightDivider = showRightDivider
            existing.invalidateSelf()
        } else if (showRightDivider) {
            root.foreground = GboardDividerDrawable(mainLabel, showRightDivider = true)
        }
    }

    private fun applyLeftAlignedWeights(controller: Any, candidates: List<Any>) {
        @Suppress("UNCHECKED_CAST")
        val weights = (weightsMethod?.invoke(controller) as? MutableList<Float>) ?: return
        val widthProvider = widthProviderMethod?.invoke(controller) ?: return

        if (availableWidthMethod == null) {
            availableWidthMethod = runCatching { widthProvider.javaClass.getMethod("d") }.getOrNull()
        }
        if (measureTextMethod == null) {
            measureTextMethod = runCatching {
                widthProvider.javaClass.getMethod("e", String::class.java)
            }.getOrNull()
        }
        val availableWidth = (availableWidthMethod?.invoke(widthProvider) as? Int) ?: return
        val measureText = measureTextMethod ?: return
        val texts = candidates.map { LmCandidateHooks.getCandidateTextForLayout(it) ?: return }
        if (weights.size != candidates.size) return

        val widths = texts.map { text ->
            (measureText.invoke(widthProvider, text) as? Number)?.toFloat() ?: return
        }
        val normalized = calculateCandidateSlotWeights(
            textWidths = widths,
            availableWidth = availableWidth,
            slotCount = GBOARD_SLOT_COUNT
        )
        if (normalized.size != weights.size) return

        weights.clear()
        weights.addAll(normalized)

        val firstRowCount = countFirstRowCandidates(normalized)
        currentFirstRowCount = firstRowCount
        runCatching { setFirstRowCountMethod?.invoke(controller, firstRowCount) }
        runCatching {
            val repo = candidateRepoMethod?.invoke(controller) ?: return@runCatching
            if (repoSetFirstRowCountMethod == null) {
                repoSetFirstRowCountMethod = repo.javaClass.getMethod("m", java.lang.Integer.TYPE)
            }
            repoSetFirstRowCountMethod?.invoke(repo, firstRowCount)
        }
    }

    // 隐藏候选展开按钮下方的搜狗角标
    private fun hookHideSogouLogo(module: XposedModule, classLoader: ClassLoader) {
        val bindingCls = Reflect.findClassIfExists(
            "com.samsung.android.honeyboard.textboard.databinding.CandidateContainerBindingImpl",
            classLoader
        ) ?: return

        Reflect.methodInHierarchy(bindingCls, "executeBindings")?.let { m ->
            XLog.safe("CandidateLayoutHooks.executeBindings") {
                module.hook(m).intercept { chain ->
                    val result = chain.proceed()
                    chain.thisObject?.let { binding ->
                        (Reflect.field(binding, "expandBtnLayoutSogouBg") as? View)?.let { view ->
                            view.visibility = View.GONE
                            view.background = null
                        }
                    }
                    result
                }
            }
        }

        bindingCls.declaredConstructors.forEach { ctor ->
            XLog.safe("CandidateLayoutHooks.bindingInit") {
                module.hook(ctor).intercept { chain ->
                    val result = chain.proceed()
                    chain.thisObject?.let { binding ->
                        (Reflect.field(binding, "expandBtnLayoutSogouBg") as? View)?.let { view ->
                            view.visibility = View.GONE
                            view.background = null
                        }
                    }
                    result
                }
            }
        }
    }
}
