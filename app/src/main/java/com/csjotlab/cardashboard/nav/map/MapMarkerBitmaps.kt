package com.csjotlab.cardashboard.nav.map

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Marker images drawn in code (no assets), at the device density so they stay crisp.
 * The vehicle car points north; the map rotates it by the feature's bearing.
 */
internal object MapMarkerBitmaps {

    /**
     * A top-down car, nose to the north, in the style of iOS car navigation: a coloured body with a
     * white rim, tinted glass, headlights and a soft ground shadow. The map rotates it by the
     * vehicle's bearing, so the nose always shows where the car is facing.
     */
    fun vehicleCar(density: Float, body: Int): Bitmap = draw(density, 64f) { canvas, s ->
        val c = s / 2f
        val w = s * 0.34f
        val l = s * 0.86f
        val left = c - w / 2f
        val top = c - l / 2f
        val right = c + w / 2f
        val bottom = c + l / 2f

        // Soft shadow under the car, offset slightly as if lit from above.
        val shadow = paint(0x55000000).apply { maskFilter = BlurMaskFilter(s * 0.07f, BlurMaskFilter.Blur.NORMAL) }
        canvas.drawRoundRect(RectF(left, top + s * 0.03f, right, bottom + s * 0.03f), w * 0.42f, w * 0.42f, shadow)

        // Side mirrors, drawn first so the body overlaps their roots.
        val mirrorY = top + l * 0.34f
        canvas.drawRoundRect(RectF(left - s * 0.055f, mirrorY, left + s * 0.02f, mirrorY + s * 0.045f), s * 0.02f, s * 0.02f, paint(WHITE))
        canvas.drawRoundRect(RectF(right - s * 0.02f, mirrorY, right + s * 0.055f, mirrorY + s * 0.045f), s * 0.02f, s * 0.02f, paint(WHITE))

        // Body: white rim, then the colour. The nose (top) is rounder than the tail.
        val rim = s * 0.035f
        canvas.drawPath(carBody(left - rim, top - rim, right + rim, bottom + rim), paint(WHITE))
        canvas.drawPath(carBody(left, top, right, bottom), paint(body))

        // Glass: windscreen, roof, rear window.
        val glass = paint(0xCC0B1220.toInt())
        val windscreen = Path().apply {
            moveTo(left + w * 0.12f, top + l * 0.34f)
            quadTo(c, top + l * 0.24f, right - w * 0.12f, top + l * 0.34f)
            lineTo(right - w * 0.18f, top + l * 0.46f)
            lineTo(left + w * 0.18f, top + l * 0.46f)
            close()
        }
        canvas.drawPath(windscreen, glass)
        canvas.drawRoundRect(RectF(left + w * 0.18f, top + l * 0.49f, right - w * 0.18f, top + l * 0.74f), w * 0.08f, w * 0.08f, paint(lighten(body)))
        val rearWindow = Path().apply {
            moveTo(left + w * 0.18f, top + l * 0.77f)
            lineTo(right - w * 0.18f, top + l * 0.77f)
            lineTo(right - w * 0.14f, top + l * 0.86f)
            quadTo(c, top + l * 0.89f, left + w * 0.14f, top + l * 0.86f)
            close()
        }
        canvas.drawPath(rearWindow, glass)

        // Headlights at the nose, tail lights at the back.
        val lampR = w * 0.09f
        canvas.drawCircle(left + w * 0.24f, top + l * 0.07f, lampR, paint(0xFFFFF7D6.toInt()))
        canvas.drawCircle(right - w * 0.24f, top + l * 0.07f, lampR, paint(0xFFFFF7D6.toInt()))
        canvas.drawRoundRect(RectF(left + w * 0.10f, bottom - l * 0.05f, left + w * 0.34f, bottom - l * 0.02f), lampR, lampR, paint(0xFFEF4444.toInt()))
        canvas.drawRoundRect(RectF(right - w * 0.34f, bottom - l * 0.05f, right - w * 0.10f, bottom - l * 0.02f), lampR, lampR, paint(0xFFEF4444.toInt()))
    }

    private fun carBody(left: Float, top: Float, right: Float, bottom: Float): Path {
        val w = right - left
        val nose = w * 0.46f
        val tail = w * 0.30f
        return Path().apply {
            moveTo(left, top + nose)
            quadTo(left, top, left + nose, top)
            lineTo(right - nose, top)
            quadTo(right, top, right, top + nose)
            lineTo(right, bottom - tail)
            quadTo(right, bottom, right - tail, bottom)
            lineTo(left + tail, bottom)
            quadTo(left, bottom, left, bottom - tail)
            close()
        }
    }

    private fun lighten(color: Int): Int {
        fun mix(channel: Int) = channel + (255 - channel) * 30 / 100
        return (color and 0xFF000000.toInt()) or
            (mix(color shr 16 and 0xFF) shl 16) or (mix(color shr 8 and 0xFF) shl 8) or mix(color and 0xFF)
    }

    fun vehicleDot(density: Float, fill: Int): Bitmap = draw(density, 30f) { canvas, s ->
        val c = s / 2f
        halo(canvas, c, s * 0.48f)
        canvas.drawCircle(c, c, s * 0.34f, paint(WHITE))
        canvas.drawCircle(c, c, s * 0.25f, paint(fill))
    }

    /** A teardrop pin whose tip is the bottom-centre of the bitmap (anchor "bottom"). */
    fun destinationPin(density: Float, fill: Int): Bitmap = draw(density, 40f, heightDp = 52f) { canvas, s ->
        val c = s / 2f
        val r = s * 0.40f
        val pin = Path().apply {
            moveTo(c, s * 1.28f)
            cubicTo(c - r * 0.35f, s * 1.0f, c - r, c + r * 0.7f, c - r, c)
            arcTo(c - r, c - r, c + r, c + r, 180f, 180f, false)
            cubicTo(c + r, c + r * 0.7f, c + r * 0.35f, s * 1.0f, c, s * 1.28f)
            close()
        }
        canvas.drawPath(pin, paint(WHITE).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.08f })
        canvas.drawPath(pin, paint(fill))
        canvas.drawCircle(c, c, r * 0.42f, paint(WHITE))
    }

    private fun halo(canvas: Canvas, c: Float, r: Float) {
        canvas.drawCircle(c, c, r, paint(0x33000000))
    }

    private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private inline fun draw(density: Float, sizeDp: Float, heightDp: Float = sizeDp, block: (Canvas, Float) -> Unit): Bitmap {
        val s = sizeDp * density
        val bitmap = Bitmap.createBitmap(s.toInt(), (heightDp * density).toInt(), Bitmap.Config.ARGB_8888)
        block(Canvas(bitmap), s)
        return bitmap
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
}
