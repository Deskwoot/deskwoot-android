package com.deskwoot.sdk.ui.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import com.deskwoot.sdk.R
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.people.Face
import kotlin.math.min

/**
 * One face: a photo in a circle (a failed photo shows
 * the person glyph), initials on warm beige, the bot's picture or Fynn's
 * mark on the brand fill, the robot on the brand gradient, or the business
 * logo as a rounded square with its first letter as the fallback. [ring]
 * draws the 2 dp ring in the surface color that overlapping faces carry.
 */
@SuppressLint("ViewConstructor")
internal class FaceView(context: Context, private val ui: Ui) : View(context) {
    private var face: Face? = null
    private var bitmap: Bitmap? = null
    private var failed = false
    var ring: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private var glyph: Drawable? = null

    init {
        decorative()
    }

    fun setFace(value: Face?) {
        if (value == face && (bitmap != null || failed || !needsImage(value))) return
        face = value
        bitmap = null
        failed = false
        glyph = null
        ui.images.cancel(this)
        val url = when (value) {
            is Face.Photo -> value.url
            is Face.Fynn -> value.url
            is Face.Logo -> value.url
            else -> null
        }
        if (url != null) {
            val size = maxOf(width, height, dp(48))
            ui.images.cached(url, size)?.let { bitmap = it } ?: ui.images.load(this, ui.session, url, url, size) { loaded ->
                bitmap = loaded
                failed = loaded == null
                invalidate()
            }
        }
        invalidate()
    }

    private fun needsImage(f: Face?): Boolean = f is Face.Photo || (f is Face.Fynn && f.url != null) || (f is Face.Logo && f.url != null)

    override fun onDraw(canvas: Canvas) {
        val f = face ?: return
        val c = ui.colors
        val ringWidth = if (ring != null) dp(2).toFloat() else 0f
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        ring?.let {
            paint.shader = null
            paint.color = it
            if (f is Face.Logo) {
                rect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
                canvas.drawRoundRect(rect, size * 0.28f, size * 0.28f, paint)
            } else {
                canvas.drawCircle(cx, cy, size / 2, paint)
            }
        }
        val s = size - ringWidth * 2
        rect.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        val image = bitmap
        val square = f is Face.Logo
        val radius = if (square) s * 0.28f else s / 2
        when {
            image != null -> drawBitmap(canvas, image, radius)
            f is Face.Photo -> {
                fill(canvas, c.initialsBg, radius)
                drawGlyph(canvas, R.drawable.dw_ic_person_fallback, c.initialsInk, 0.55f, s)
            }
            f is Face.Initials -> {
                fill(canvas, c.initialsBg, radius)
                drawText(canvas, f.letters, c.initialsInk, s * 0.38f, 650, 0.02f)
            }
            f is Face.Fynn -> {
                fill(canvas, c.fill, radius)
                drawGlyph(canvas, R.drawable.dw_ic_fynn_mark, c.onBrand, 0.6f, s)
            }
            f is Face.Robot -> {
                paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, c.brand, c.deep, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(rect, radius, radius, paint)
                paint.shader = null
                drawGlyph(canvas, R.drawable.dw_ic_robot, c.onBrand, 0.62f, s)
            }
            f is Face.Logo -> {
                fill(canvas, c.fill, radius)
                drawText(canvas, f.letter, c.onBrand, s * 0.52f, 750, -0.02f)
            }
        }
    }

    private fun fill(canvas: Canvas, color: Int, radius: Float) {
        paint.shader = null
        paint.color = color
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    private fun drawBitmap(canvas: Canvas, image: Bitmap, radius: Float) {
        // Aspect fill, centered, on bg-2.
        fill(canvas, ui.colors.bg2, radius)
        val shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = maxOf(rect.width() / image.width, rect.height() / image.height)
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate(rect.centerX() - image.width * scale / 2, rect.centerY() - image.height * scale / 2)
        shader.setLocalMatrix(m)
        paint.shader = shader
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null
    }

    private fun drawGlyph(canvas: Canvas, res: Int, color: Int, fraction: Float, s: Float) {
        val d = glyph ?: context.icon(res, color).also { glyph = it }
        val g = (s * fraction).toInt()
        val left = (rect.centerX() - g / 2f).toInt()
        val top = (rect.centerY() - g / 2f).toInt()
        d.setBounds(left, top, left + g, top + g)
        d.draw(canvas)
    }

    private fun drawText(canvas: Canvas, text: String, color: Int, size: Float, weight: Int, spacing: Float) {
        textPaint.color = color
        textPaint.textSize = size
        textPaint.typeface = cachedTypeface(weight)
        textPaint.letterSpacing = spacing
        val y = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(text, rect.centerX(), y, textPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // A face that leaves the screen stops its request; it loads again when it comes back.
        if (bitmap == null && !failed) ui.images.cancel(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (bitmap == null && !failed && needsImage(face)) {
            val current = face
            face = null
            setFace(current)
        }
    }
}
