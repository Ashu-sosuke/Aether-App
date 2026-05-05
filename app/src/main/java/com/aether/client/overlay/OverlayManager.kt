package com.aether.client.overlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverlayManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val overlayViews = mutableSetOf<View>()
    private var stopButton: View? = null

    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(context)

    fun requestOverlayPermission(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        activity.startActivity(intent)
    }

    fun showTap(x: Float, y: Float) {
        mainHandler.post {
            if (!hasOverlayPermission()) return@post
            val size = 128.dp
            val view = GhostTapView(context).apply {
                pivotX = size / 2f
                pivotY = size / 2f
            }
            val params = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = (x - size / 2f).toInt()
                this.y = (y - size / 2f).toInt()
            }

            runCatching {
                windowManager.addView(view, params)
                overlayViews.add(view)
                view.start()
                mainHandler.postDelayed({ removeView(view) }, 700L)
            }
        }
    }

    fun showStopButton(onStop: () -> Unit) {
        mainHandler.post {
            if (!hasOverlayPermission() || stopButton != null) return@post
            
            val size = 64.dp
            val params = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 20.dp
                y = 100.dp
            }

            val view = FrameLayout(context).apply {
                // Background
                val bg = View(context).apply {
                    setBackgroundColor(Color.parseColor("#FF6B6B"))
                    alpha = 0.9f
                    elevation = 8.dp.toFloat()
                }
                addView(bg)

                // Icon (using a simple cross/X if we don't have vector resources, but let's assume standard)
                val icon = ImageView(context).apply {
                    setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    setColorFilter(Color.WHITE)
                    setPadding(16.dp, 16.dp, 16.dp, 16.dp)
                }
                addView(icon)

                setOnClickListener { onStop() }
                
                // Make it draggable
                var initialX = 0
                var initialY = 0
                var initialTouchX = 0f
                var initialTouchY = 0f
                var startTime = 0L

                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            startTime = System.currentTimeMillis()
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(v, params)
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            val duration = System.currentTimeMillis() - startTime
                            val dx = Math.abs(event.rawX - initialTouchX)
                            val dy = Math.abs(event.rawY - initialTouchY)
                            
                            if (dx < 10.dp && dy < 10.dp && duration < 400) {
                                onStop()
                            }
                            true
                        }
                        else -> false
                    }
                }
                
                isClickable = true
                isFocusable = true
            }

            runCatching {
                windowManager.addView(view, params)
                stopButton = view
            }
        }
    }

    fun hideStopButton() {
        mainHandler.post {
            stopButton?.let {
                removeView(it)
                stopButton = null
            }
        }
    }

    fun clear() {
        mainHandler.post {
            overlayViews.toList().forEach(::removeView)
        }
    }

    private fun removeView(view: View) {
        runCatching {
            if (overlayViews.remove(view)) {
                windowManager.removeView(view)
            }
        }
    }

    private val Int.dp: Int
        get() = (this * context.resources.displayMetrics.density).toInt()

    private val Float.dp: Float
        get() = this * context.resources.displayMetrics.density

    private class GhostTapView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 107, 107)
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            alpha = (255 * 0.6f).toInt()
        }
        private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            alpha = (255 * 0.9f).toInt()
        }
        private var outerScale = 0.5f
        private var innerScale = 1f
        private var outerAlpha = 0.8f
        private var innerAlpha = 1f

        fun start() {
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(this@GhostTapView, "outerScale", 0.5f, 2.0f).setDuration(600L),
                    ObjectAnimator.ofFloat(this@GhostTapView, "outerAlpha", 0.8f, 0f).setDuration(600L),
                    ObjectAnimator.ofFloat(this@GhostTapView, "innerScale", 1.0f, 0.5f).setDuration(400L),
                    ObjectAnimator.ofFloat(this@GhostTapView, "innerAlpha", 1.0f, 0f).setDuration(400L)
                )
                start()
            }
        }

        fun setOuterScale(value: Float) {
            outerScale = value
            invalidate()
        }

        fun setInnerScale(value: Float) {
            innerScale = value
            invalidate()
        }

        fun setOuterAlpha(value: Float) {
            outerAlpha = value
            invalidate()
        }

        fun setInnerAlpha(value: Float) {
            innerAlpha = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            outerPaint.alpha = (255 * 0.6f * outerAlpha).toInt().coerceIn(0, 255)
            innerPaint.alpha = (255 * 0.9f * innerAlpha).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, 48f * density * outerScale, outerPaint)
            canvas.drawCircle(cx, cy, 12f * density * innerScale, innerPaint)
        }
    }
}
