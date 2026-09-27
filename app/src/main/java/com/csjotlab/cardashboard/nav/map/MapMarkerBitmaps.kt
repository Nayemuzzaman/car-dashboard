package com.csjotlab.cardashboard.nav.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * Marker images drawn in code (no assets), at the device density so they stay crisp.
 * The vehicle arrow points north; the map rotates it by the feature's bearing.
 */
internal object MapMarkerBitmaps {

    fun vehicleArrow(density: Float, fill: Int): Bitmap = draw(density, 44f) { canvas, s ->
        val c = s / 2f
        halo(canvas, c, s * 0.46f)
        val arrow = Path().apply {
            moveTo(c, s * 0.10f)
            lineTo(s * 0.82f, s * 0.84f)
            lineTo(c, s * 0.66f)
            lineTo(s * 0.18f, s * 0.84f)
            close()
        }
        canvas.drawPath(arrow, paint(WHITE).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.09f; strokeJoin = Paint.Join.ROUND })
        canvas.drawPath(arrow, paint(fill))
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
