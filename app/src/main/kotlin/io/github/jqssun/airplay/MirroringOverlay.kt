package io.github.jqssun.airplay

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

internal class MirroringOverlay(
    private val activity: Activity,
    private val attachSurface: (Surface) -> Unit,
    private val detachSurface: (Surface) -> Unit,
    private val onClose: () -> Unit
) {
    private val manager = activity.getSystemService(WindowManager::class.java)
    private val preferences = activity.getSharedPreferences("mirroring_window", Activity.MODE_PRIVATE)
    private val frame = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
    }
    private val viewport = FrameLayout(activity)
    private val video = SurfaceView(activity)
    private val barHeight = dp(48)
    private val panel = 0xff101820.toInt()
    private val accent = 0xff8fe6d1.toInt()
    private var active = false
    private var aspect = 16f / 9f

    private val params = WindowManager.LayoutParams(
        preferences.getInt("width", dp(360)),
        preferences.getInt("height", dp(270)),
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.OPAQUE
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = preferences.getInt("x", dp(24))
        y = preferences.getInt("y", dp(48))
    }

    init {
        val bar = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(6), 0)
            setBackgroundColor(panel)
        }
        val move = control("投屏").apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            typeface = Typeface.DEFAULT_BOLD
            contentDescription = "拖动投屏窗口"
            setCompoundDrawablesWithIntrinsicBounds(
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent)
                    setSize(dp(7), dp(7))
                }, null, null, null
            )
            compoundDrawablePadding = dp(9)
        }
        bar.addView(move, LinearLayout.LayoutParams(0, barHeight, 1f))
        val close = control("全屏").apply {
            setTextColor(accent)
            background = rounded(0xff20342f.toInt(), 8)
            setOnClickListener { onClose() }
        }
        bar.addView(close, LinearLayout.LayoutParams(dp(68), dp(40)))
        frame.addView(bar)

        frame.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
        viewport.addView(video, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        val resize = control("↘").apply {
            contentDescription = "拖动调整窗口大小"
            textSize = 24f
            setTextColor(accent)
            background = rounded(0xee182924.toInt(), 8)
            elevation = dp(2).toFloat()
        }
        viewport.addView(resize, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.RIGHT).apply {
            rightMargin = dp(6)
            bottomMargin = dp(6)
        })
        move.setOnTouchListener(dragListener(resize = false))
        resize.setOnTouchListener(dragListener(resize = true))
        viewport.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = attachSurface(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = detachSurface(holder.surface)
        })
    }

    fun show(): Boolean {
        clampBounds()
        return try {
            manager.addView(frame, params)
            active = true
            true
        } catch (_: SecurityException) {
            false
        } catch (_: WindowManager.BadTokenException) {
            false
        }
    }

    fun updateAspect(value: Float) {
        if (!value.isFinite() || value <= 0 || aspect == value) return
        aspect = value
        fitVideo()
    }

    fun close() {
        if (!active) return
        active = false
        manager.removeViewImmediate(frame)
    }

    private fun fitVideo() {
        if (viewport.width == 0 || viewport.height == 0) return
        val width = minOf(viewport.width, (viewport.height * aspect).roundToInt()).coerceAtLeast(1)
        val height = minOf(viewport.height, (width / aspect).roundToInt()).coerceAtLeast(1)
        val current = video.layoutParams
        if (width != current.width || height != current.height) {
            video.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
        }
    }

    private fun dragListener(resize: Boolean) = object : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var initialX = 0
        private var initialY = 0
        private var initialWidth = 0
        private var initialHeight = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    initialX = params.x
                    initialY = params.y
                    initialWidth = params.width
                    initialHeight = params.height
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startX).roundToInt()
                    val dy = (event.rawY - startY).roundToInt()
                    if (resize) {
                        params.width = initialWidth + dx
                        params.height = initialHeight + dy
                    } else {
                        params.x = initialX + dx
                        params.y = initialY + dy
                    }
                    clampBounds()
                    manager.updateViewLayout(frame, params)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> preferences.edit()
                    .putInt("x", params.x).putInt("y", params.y)
                    .putInt("width", params.width).putInt("height", params.height).apply()
                else -> return false
            }
            return true
        }
    }

    @Suppress("DEPRECATION")
    private fun clampBounds() {
        val size = android.graphics.Point().also { manager.defaultDisplay.getSize(it) }
        val width = size.x.coerceAtLeast(1)
        val height = size.y.coerceAtLeast(1)
        params.width = params.width.coerceIn(minOf(dp(200), width), width)
        params.height = params.height.coerceIn(minOf(dp(142), height), height)
        params.x = params.x.coerceIn(0, width - params.width)
        params.y = params.y.coerceIn(0, height - params.height)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun control(label: String) = TextView(activity).apply {
        text = label
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
    }

    private fun dp(value: Int) = (activity.resources.displayMetrics.density * value).roundToInt()
}
