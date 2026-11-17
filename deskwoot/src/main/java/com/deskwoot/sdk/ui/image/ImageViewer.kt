package com.deskwoot.sdk.ui.image

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.deskwoot.sdk.R
import com.deskwoot.sdk.ui.DeskwootActivity
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.design.ModalAccessibility
import com.deskwoot.sdk.ui.widgets.MATCH
import com.deskwoot.sdk.ui.widgets.dp
import com.deskwoot.sdk.ui.widgets.icon
import com.deskwoot.sdk.ui.widgets.linear
import com.deskwoot.sdk.ui.widgets.oval
import com.deskwoot.sdk.ui.widgets.ripple
import com.deskwoot.sdk.ui.widgets.role
import kotlin.math.max
import kotlin.math.min

/**
 * An image of the chat full screen over the messenger: pinch and double
 * tap to zoom, drag to pan, share and close at the top. Back closes it.
 * The picture comes through the SDK's loader at most at the screen's size.
 * It covers the whole window (no strip of the messenger under the
 * navigation bar), the status and navigation bar icons turn light on its
 * black, and the screens beneath leave the TalkBack order while it shows:
 * TalkBack's focus moves to the picture and back to the row that opened it.
 */
@SuppressLint("ViewConstructor")
internal class ImageViewer(
    private val activity: DeskwootActivity,
    private val ui: Ui,
    val key: String,
    val link: String,
    val name: String?,
    private val onClosed: () -> Unit,
) : FrameLayout(ui.context) {
    private val image = ZoomImageView(ui.context)
    private val spinner = ProgressBar(ui.context)
    private var closed = false
    private val modal = ModalAccessibility()
    private var lightStatusBars = false
    private var lightNavigationBars = false

    init {
        id = R.id.dw_image_viewer
        setBackgroundColor(Color.BLACK)
        isClickable = true
        // It sits over the window's content, outside the messenger's root: the session's direction is set here.
        layoutDirection = if (ui.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        textDirection = if (ui.rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_FIRST_STRONG
        image.id = R.id.dw_viewer_image
        image.contentDescription = name ?: ui.t(R.string.dw_file_label)
        addView(image, LayoutParams(MATCH, MATCH))
        addView(spinner, LayoutParams(dp(40), dp(40), Gravity.CENTER))
        val bar = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        bar.addView(button(R.id.dw_viewer_share, R.drawable.dw_ic_external, ui.t(R.string.dw_sdk_share)) {
            activity.files.share(ui.images, key, link, name, "image/*")
        })
        bar.addView(button(R.id.dw_viewer_close, R.drawable.dw_ic_close, ui.t(R.string.dw_close)) { close() })
        addView(bar, LayoutParams(MATCH, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        ViewCompat.setOnApplyWindowInsetsListener(bar) { v, insets ->
            // Below the status bar, and clear of a cutout or the navigation bar at the sides in landscape.
            val edges = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(edges.left + dp(8), edges.top + dp(8), edges.right + dp(8), v.paddingBottom)
            insets
        }
        val size = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        ui.images.load(image, ui.session, key, link, size) { bitmap ->
            spinner.visibility = View.GONE
            if (bitmap != null) image.show(bitmap)
        }
    }

    private fun button(id: Int, iconRes: Int, label: String, onTap: () -> Unit): View = ImageView(ui.context).apply {
        this.id = id
        setImageDrawable(context.icon(iconRes, Color.WHITE))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = ripple(0x40FFFFFF, context.oval(0x66000000))
        contentDescription = label
        role(button = true)
        setOnClickListener { onTap() }
        layoutParams = linear(dp(48), dp(48)).apply { marginStart = dp(8) }
    }

    /** Shows the viewer in [host] (the window's content, over everything) above [under] (the messenger). */
    fun show(host: ViewGroup, under: View, animated: Boolean = true) {
        host.addView(this, LayoutParams(MATCH, MATCH))
        ViewCompat.requestApplyInsets(this)
        // TalkBack reads the picture alone, from the picture on, and comes back to the row that opened it.
        modal.open(listOf(under), opener = ModalAccessibility.focused(under), focus = image)
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        lightStatusBars = bars.isAppearanceLightStatusBars
        lightNavigationBars = bars.isAppearanceLightNavigationBars
        bars.isAppearanceLightStatusBars = false
        bars.isAppearanceLightNavigationBars = false
        if (animated && !ui.reducedMotion) {
            alpha = 0f
            animate().alpha(1f).setDuration(160).start()
        }
        image.requestFocus()
    }

    fun close() {
        if (closed) return
        closed = true
        animate().alpha(0f).setDuration(if (ui.reducedMotion) 0 else 140).withEndAction { dismissNow() }.start()
    }

    fun dismissNow() {
        val wasShown = parent != null
        closed = true
        ui.images.cancel(image)
        (parent as? ViewGroup)?.removeView(this)
        modal.close(returnFocus = wasShown)
        if (wasShown) {
            val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            bars.isAppearanceLightStatusBars = lightStatusBars
            bars.isAppearanceLightNavigationBars = lightNavigationBars
            onClosed()
        }
    }

    internal companion object {
        private const val KEY_KEY = "dw_viewer_key"
        private const val KEY_LINK = "dw_viewer_link"
        private const val KEY_NAME = "dw_viewer_name"

        /** What a recreated Activity needs to show the same picture again. */
        fun save(out: Bundle, key: String, link: String, name: String?) {
            out.putString(KEY_KEY, key)
            out.putString(KEY_LINK, link)
            name?.let { out.putString(KEY_NAME, it) }
        }

        /** The picture a saved state asks for: key, link and name; null when no viewer was open. */
        fun restore(saved: Bundle?): Triple<String, String, String?>? {
            val key = saved?.getString(KEY_KEY) ?: return null
            val link = saved.getString(KEY_LINK) ?: return null
            return Triple(key, link, saved.getString(KEY_NAME))
        }
    }
}

/** An ImageView that fits its picture and zooms (1 to 4 times) by pinch and double tap, and pans while zoomed. */
internal class ZoomImageView(context: android.content.Context) : ImageView(context) {
    private val base = Matrix()
    private val zoom = Matrix()
    private val shown = Matrix()
    private var scale = 1f
    private var bitmap: Bitmap? = null
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (scale * detector.scaleFactor).coerceIn(1f, 4f)
            val f = next / scale
            scale = next
            zoom.postScale(f, f, detector.focusX, detector.focusY)
            settle()
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scale <= 1f) return false
            zoom.postTranslate(-distanceX, -distanceY)
            settle()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > 1f) {
                zoom.reset()
                scale = 1f
            } else {
                zoom.postScale(2.5f, 2.5f, e.x, e.y)
                scale = 2.5f
            }
            settle()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        isFocusable = true
    }

    fun show(b: Bitmap) {
        bitmap = b
        setImageBitmap(b)
        fit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    private fun fit() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        val s = min(width.toFloat() / b.width, height.toFloat() / b.height)
        base.setScale(s, s)
        base.postTranslate((width - b.width * s) / 2, (height - b.height * s) / 2)
        zoom.reset()
        scale = 1f
        settle()
    }

    /** Keeps the zoomed picture over the view: no empty edge while it is larger than the view. */
    private fun settle() {
        val b = bitmap ?: return
        shown.set(base)
        shown.postConcat(zoom)
        val rect = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        shown.mapRect(rect)
        var dx = 0f
        var dy = 0f
        if (rect.width() <= width) dx = (width - rect.width()) / 2 - rect.left else {
            if (rect.left > 0) dx = -rect.left
            if (rect.right < width) dx = width - rect.right
        }
        if (rect.height() <= height) dy = (height - rect.height()) / 2 - rect.top else {
            if (rect.top > 0) dy = -rect.top
            if (rect.bottom < height) dy = height - rect.bottom
        }
        zoom.postTranslate(dx, dy)
        shown.postTranslate(dx, dy)
        imageMatrix = shown
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(scale > 1f)
        return true
    }
}
