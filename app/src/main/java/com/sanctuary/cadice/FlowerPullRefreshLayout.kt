package com.sanctuary.cadice

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 🌸 可黛丝的小花下拉刷新容器（仿 QQ 拉开顶栏间距 + 旋转小花 + 成功散落花瓣 + 失败持续旋转 + 随时上拉取消）
 * - 刷新中或失败旋转时，只要手指上滑即可立即取消本次刷新并丝滑收起；
 * - 无任何冗余文字提示，纯动画反馈。
 */
class FlowerPullRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class State { IDLE, PULLING, REFRESHING, PETAL_FALLING, FAILED_SPINNING }

    private val density = resources.displayMetrics.density
    private val triggerHeightPx = 62f * density
    private val maxPullPx = 110f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val headerView = FlowerRefreshHeaderView(context)
    private var contentView: View? = null

    private var state = State.IDLE
    private var currentOffset = 0f
    private var startY = 0f
    private var startX = 0f
    private var baseOffsetAtDown = 0f
    private var isDragging = false

    private var settleAnimator: ValueAnimator? = null
    private var onRefreshListener: (() -> Unit)? = null
    private var onCancelListener: (() -> Unit)? = null

    init {
        val headerLp = LayoutParams(LayoutParams.MATCH_PARENT, (76f * density).toInt())
        addView(headerView, headerLp)
        headerView.visibility = View.INVISIBLE
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child !== headerView) {
                contentView = child
                break
            }
        }
        headerView.bringToFront()
    }

    fun setOnRefreshListener(listener: () -> Unit) {
        onRefreshListener = listener
    }

    fun setOnCancelListener(listener: () -> Unit) {
        onCancelListener = listener
    }

    fun setThemeDark(dark: Boolean) {
        headerView.setThemeDark(dark)
    }

    fun finishRefresh(success: Boolean) {
        if (state != State.REFRESHING && state != State.FAILED_SPINNING) return
        if (success) {
            state = State.PETAL_FALLING
            headerView.startPetalFall {
                state = State.IDLE
                animateOffsetTo(0f) {
                    headerView.resetToIdle()
                    headerView.visibility = View.INVISIBLE
                }
            }
        } else {
            state = State.FAILED_SPINNING
            headerView.setFailedSpinning()
        }
    }

    fun cancelRefresh() {
        if (state == State.IDLE || state == State.PETAL_FALLING) return
        state = State.IDLE
        isDragging = false
        onCancelListener?.invoke()
        headerView.resetToIdle()
        animateOffsetTo(0f) {
            headerView.visibility = View.INVISIBLE
        }
    }

    private fun triggerRefreshFromHeader() {
        state = State.REFRESHING
        headerView.startSpinning()
        animateOffsetTo(triggerHeightPx)
        onRefreshListener?.invoke()
    }

    private fun canChildScrollUp(): Boolean {
        val target = contentView ?: return false
        return canViewScrollUp(target)
    }

    private fun canViewScrollUp(view: View): Boolean {
        if (view.canScrollVertically(-1)) return true
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child.visibility == View.VISIBLE && canViewScrollUp(child)) {
                    return true
                }
            }
        }
        return false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = ev.y
                startX = ev.x
                baseOffsetAtDown = currentOffset
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - startY
                val dx = ev.x - startX
                if (abs(dy) <= touchSlop || abs(dy) <= abs(dx) * 1.15f) {
                    return false
                }
                // 1. 刷新中或失败旋转中，只要上拉立刻拦截跟手取消！
                if ((state == State.REFRESHING || state == State.FAILED_SPINNING) && dy < -touchSlop) {
                    isDragging = true
                    startY = ev.y
                    baseOffsetAtDown = currentOffset
                    return true
                }
                // 2. 顶部下拉触发（含失败状态下再下拉重刷）
                if (dy > touchSlop && !canChildScrollUp() && (state == State.IDLE || state == State.FAILED_SPINNING)) {
                    isDragging = true
                    startY = ev.y
                    baseOffsetAtDown = currentOffset
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return true
                val deltaY = event.y - startY
                val targetOffset = (baseOffsetAtDown + deltaY * 0.52f).coerceIn(0f, maxPullPx)

                if ((state == State.REFRESHING || state == State.FAILED_SPINNING) && deltaY < -12f * density) {
                    // 跟随手指上拉并即时进入取消预览
                    applyOffset(targetOffset)
                    if (targetOffset <= triggerHeightPx * 0.45f) {
                        cancelRefresh()
                    }
                    return true
                }

                if (state == State.IDLE || state == State.PULLING) {
                    state = State.PULLING
                    headerView.setPullProgress((targetOffset / triggerHeightPx).coerceIn(0f, 1.4f))
                }
                applyOffset(targetOffset)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    if (state == State.REFRESHING || state == State.FAILED_SPINNING) {
                        if (currentOffset < triggerHeightPx * 0.72f) {
                            cancelRefresh()
                        } else {
                            animateOffsetTo(triggerHeightPx)
                        }
                    } else if (currentOffset >= triggerHeightPx) {
                        triggerRefreshFromHeader()
                    } else {
                        state = State.IDLE
                        animateOffsetTo(0f) {
                            headerView.resetToIdle()
                            headerView.visibility = View.INVISIBLE
                        }
                    }
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun applyOffset(offset: Float) {
        currentOffset = offset
        contentView?.translationY = offset
        if (offset > 1f) {
            if (headerView.visibility != View.VISIBLE) headerView.visibility = View.VISIBLE
            headerView.translationY = (offset - headerView.height) * 0.5f
            headerView.alpha = (offset / (triggerHeightPx * 0.65f)).coerceIn(0f, 1f)
        } else {
            headerView.alpha = 0f
        }
    }

    private fun animateOffsetTo(target: Float, onEnd: (() -> Unit)? = null) {
        settleAnimator?.cancel()
        settleAnimator = ValueAnimator.ofFloat(currentOffset, target).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                applyOffset(anim.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    private class FlowerRefreshHeaderView(context: Context) : View(context) {

        private data class FallingPetal(
            val startX: Float,
            val startY: Float,
            val vx: Float,
            val vy: Float,
            val rotStart: Float,
            val rotSpeed: Float,
            val scale: Float,
            val color: Int
        )

        private var isDark = true
        private var rotationDeg = 0f
        private var pullProgress = 0f
        private var mode = 0 // 0=pull/spin, 1=petalFalling, 2=failedSpin
        private var fallProgress = 0f
        private val fallingPetals = mutableListOf<FallingPetal>()

        private var spinAnimator: ValueAnimator? = null
        private var fallAnimator: ValueAnimator? = null

        private val petalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val petalPath = Path()

        fun setThemeDark(dark: Boolean) {
            isDark = dark
            invalidate()
        }

        fun setPullProgress(progress: Float) {
            if (mode == 1) return
            mode = 0
            pullProgress = progress
            rotationDeg = progress * 210f
            invalidate()
        }

        fun startSpinning() {
            mode = 0
            pullProgress = 1f
            fallAnimator?.cancel()
            if (spinAnimator?.isRunning != true) {
                spinAnimator = ValueAnimator.ofFloat(rotationDeg, rotationDeg + 360f).apply {
                    duration = 820L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        rotationDeg = (it.animatedValue as Float) % 360f
                        invalidate()
                    }
                    start()
                }
            }
        }

        fun setFailedSpinning() {
            mode = 2
            if (spinAnimator?.isRunning != true) {
                startSpinning()
                mode = 2
            }
            invalidate()
        }

        fun startPetalFall(onFinished: () -> Unit) {
            spinAnimator?.cancel()
            fallAnimator?.cancel()
            mode = 1
            fallProgress = 0f
            fallingPetals.clear()
            val d = resources.displayMetrics.density
            val colors = intArrayOf(
                Color.parseColor("#F472B6"),
                Color.parseColor("#F9A8D4"),
                Color.parseColor("#C084FC"),
                Color.parseColor("#FBCFE8"),
                Color.parseColor("#A78BFA"),
                Color.parseColor("#F472B6"),
                Color.parseColor("#DDD6FE")
            )
            for (i in 0 until 7) {
                val angle = (i * 51.4f + 15f) * Math.PI / 180.0
                val dist = (6f + (i % 3) * 5f) * d
                fallingPetals.add(
                    FallingPetal(
                        startX = (cos(angle) * dist).toFloat(),
                        startY = (sin(angle) * dist).toFloat(),
                        vx = ((i - 3) * 14f + if (i % 2 == 0) 6f else -6f) * d,
                        vy = (32f + (i % 3) * 14f) * d,
                        rotStart = i * 50f,
                        rotSpeed = if (i % 2 == 0) 220f else -190f,
                        scale = 0.72f + (i % 3) * 0.14f,
                        color = colors[i]
                    )
                )
            }
            fallAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 760L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    fallProgress = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        onFinished()
                    }
                })
                start()
            }
        }

        fun resetToIdle() {
            spinAnimator?.cancel()
            fallAnimator?.cancel()
            mode = 0
            pullProgress = 0f
            fallProgress = 0f
            fallingPetals.clear()
            invalidate()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            spinAnimator?.cancel()
            fallAnimator?.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f

            if (mode == 1) {
                val alpha = ((1f - fallProgress) * 255f).toInt().coerceIn(0, 255)
                for (p in fallingPetals) {
                    val px = cx + p.startX + p.vx * fallProgress
                    val py = cy + p.startY + p.vy * fallProgress * fallProgress + 16f * d * fallProgress
                    petalPaint.color = p.color
                    petalPaint.alpha = alpha
                    canvas.save()
                    canvas.translate(px, py)
                    canvas.rotate(p.rotStart + p.rotSpeed * fallProgress)
                    canvas.scale(p.scale, p.scale)
                    drawSinglePetal(canvas, 10f * d)
                    canvas.restore()
                }
                return
            }

            val flowerScale = (0.55f + pullProgress * 0.45f).coerceIn(0.45f, 1.08f)
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(rotationDeg)
            canvas.scale(flowerScale, flowerScale)

            petalPaint.color = if (mode == 2) Color.parseColor("#FB7185") else Color.parseColor("#F472B6")
            petalPaint.alpha = 240
            for (i in 0 until 5) {
                canvas.save()
                canvas.rotate(i * 72f)
                drawSinglePetal(canvas, 11.5f * d)
                canvas.restore()
            }
            centerPaint.color = if (mode == 2) Color.parseColor("#FDE047") else Color.parseColor("#FEF08A")
            canvas.drawCircle(0f, 0f, 3.3f * d, centerPaint)
            canvas.restore()
        }

        private fun drawSinglePetal(canvas: Canvas, len: Float) {
            petalPath.reset()
            petalPath.moveTo(0f, 0f)
            petalPath.cubicTo(-len * 0.45f, -len * 0.42f, -len * 0.32f, -len, 0f, -len)
            petalPath.cubicTo(len * 0.32f, -len, len * 0.45f, -len * 0.42f, 0f, 0f)
            petalPath.close()
            canvas.drawPath(petalPath, petalPaint)
        }
    }
}
