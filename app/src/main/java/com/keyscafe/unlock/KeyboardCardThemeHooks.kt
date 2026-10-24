package com.keyscafe.unlock

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import io.github.libxposed.api.XposedModule

/**
 * 键盘卡片浮层主题
 * 将键盘根布局裁剪为顶部 20dp 圆角，并叠加日夜自适应边缘微光。
 */
object KeyboardCardThemeHooks {

    private val KB_PKG get() = XyCore.get(14)
    private val IME_SERVICE_CLASS get() = XyCore.get(19)

    private val mainHandler = Handler(Looper.getMainLooper())

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val serviceCls = Reflect.findClassIfExists(IME_SERVICE_CLASS, classLoader)
        val targetCls = serviceCls ?: InputMethodService::class.java

        Reflect.methodInHierarchy(targetCls, "onWindowShown")?.let { onWindowShown ->
            XLog.safe("KeyboardCardThemeHooks.onWindowShown") {
                module.hook(onWindowShown).intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? InputMethodService)?.let { service ->
                        postApply(service)
                    }
                    result
                }
            }
        }
        XLog.i("KeyboardCardThemeHooks: 就绪")
    }

    private fun postApply(service: InputMethodService) {
        mainHandler.post {
            applyCardTheme(service)
        }
    }

    private fun applyCardTheme(service: InputMethodService) {
        val win = service.window?.window ?: return
        val decor = win.decorView

        // 窗口根容器背景透明，露出圆角外层
        win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        decor.setBackgroundColor(Color.TRANSPARENT)

        val cardView = findCardRoot(decor) ?: return

        if (cardView.width == 0 || cardView.height == 0) {
            cardView.post { applyCardTheme(service) }
            return
        }

        // 清理中间层父容器背景，避免遮挡圆角
        var parent = cardView.parent
        while (parent is ViewGroup && parent !== decor) {
            parent.setBackgroundColor(Color.TRANSPARENT)
            parent = parent.parent
        }

        // 移除顶部内边距，使工具栏与卡片顶边对齐
        if (cardView.paddingTop != 0) {
            cardView.setPadding(cardView.paddingLeft, 0, cardView.paddingRight, cardView.paddingBottom)
        }

        // 顶部 20dp 圆角裁剪
        if (cardView.outlineProvider !is CardOutlineProvider) {
            cardView.outlineProvider = CardOutlineProvider()
            cardView.clipToOutline = true
        }

        cardView.alpha = 1.0f

        // 叠加边缘微光线
        if (cardView.foreground !is CardOverlayDrawable) {
            cardView.foreground = CardOverlayDrawable(cardView)
        } else {
            cardView.foreground?.invalidateSelf()
        }

        // 隐藏展开按钮下方的搜狗角标
        val sogouId = decor.resources.getIdentifier("expand_btn_layout_sogou_bg", "id", KB_PKG)
        if (sogouId != 0) {
            decor.findViewById<View>(sogouId)?.let {
                it.visibility = View.GONE
                it.background = null
            }
        }
    }

    private fun findCardRoot(decor: View): View? {
        val res = decor.resources
        val rootId = res.getIdentifier("layout_base_frame_root", "id", KB_PKG)
        if (rootId != 0) {
            decor.findViewById<View>(rootId)?.let { return it }
        }
        val root = decor as? ViewGroup ?: return null
        var best: View? = null
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (best == null || c.height > best.height) best = c
        }
        return best
    }

    /** 顶部 20dp 圆角 OutlineProvider（底部延伸保持直角） */
    private class CardOutlineProvider : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val r = (20f * view.resources.displayMetrics.density).toInt()
            outline.setRoundRect(0, 0, view.width, view.height + r, r.toFloat())
        }
    }

    /** 顶部边缘自适应微光描边（日间微暗防白底隐形，夜间微亮） */
    private class CardOverlayDrawable(
        private val view: View
    ) : Drawable() {

        private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        private val rimPath = Path()
        private var lastW = 0
        private var lastH = 0
        private var lastNight = false

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return

            val ctx = view.context
            val night = (ctx.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val density = ctx.resources.displayMetrics.density

            val radius = 20f * density
            val strokeWidth = 0.6f * density

            if (w.toInt() != lastW || h.toInt() != lastH || night != lastNight) {
                lastW = w.toInt()
                lastH = h.toInt()
                lastNight = night

                val inset = strokeWidth * 0.5f
                val r = radius - inset
                val fadeEndY = inset + r

                // 路径：左圆弧 -> 顶边 -> 右圆弧
                rimPath.reset()
                rimPath.moveTo(inset, inset + r)
                rimPath.arcTo(inset, inset, inset + r * 2f, inset + r * 2f, 180f, 90f, false)
                rimPath.lineTo(w - inset - r, inset)
                rimPath.arcTo(w - inset - r * 2f, inset, w - inset, inset + r * 2f, 270f, 90f, false)

                // 纵向渐变：由顶至弧底渐隐
                val gradPositions = floatArrayOf(0f, 0.45f, 0.8f, 1.0f)
                val hairlineColors = if (night) {
                    intArrayOf(
                        Color.argb(90, 255, 255, 255),
                        Color.argb(55, 255, 255, 255),
                        Color.argb(18, 255, 255, 255),
                        Color.argb(0, 255, 255, 255)
                    )
                } else {
                    intArrayOf(
                        Color.argb(38, 0, 0, 0),
                        Color.argb(24, 0, 0, 0),
                        Color.argb(8, 0, 0, 0),
                        Color.argb(0, 0, 0, 0)
                    )
                }

                hairlinePaint.strokeWidth = strokeWidth
                hairlinePaint.shader = LinearGradient(0f, inset, 0f, fadeEndY, hairlineColors, gradPositions, Shader.TileMode.CLAMP)
            }

            canvas.drawPath(rimPath, hairlinePaint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
