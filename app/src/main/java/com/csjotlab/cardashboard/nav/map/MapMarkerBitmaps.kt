package com.csjotlab.cardashboard.nav.map

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.csjotlab.cardashboard.nav.geocoding.StoreBrand

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

    /** A search result without a known chain: a filled dot with a white rim (anchor "center"). */
    fun searchDot(density: Float, fill: Int): Bitmap = draw(density, 20f) { canvas, s ->
        val c = s / 2f
        canvas.drawCircle(c, c, s * 0.46f, paint(WHITE))
        canvas.drawCircle(c, c, s * 0.34f, paint(fill))
    }

    /**
     * A chain's badge: its short name on its colours, with a pointer whose tip is the bottom-centre
     * of the bitmap (anchor "bottom"). Drawn in code from colours — not the chain's logo artwork.
     */
    fun storeBadge(density: Float, brand: StoreBrand): Bitmap {
        val colours = badgeStyle(brand)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colours.text
            textSize = 11f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val margin = 3f * density
        val rim = 2f * density
        val height = 22f * density
        val pointer = 6f * density
        val width = text.measureText(brand.badgeText) + 14f * density
        val bitmap = Bitmap.createBitmap((width + 2 * margin).toInt(), (height + pointer + 2 * margin).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val body = RectF(margin, margin, margin + width, margin + height)
        val radius = 6f * density
        val cx = bitmap.width / 2f
        val tip = bitmap.height.toFloat() - rim / 2f
        val shape = Path().apply {
            addRoundRect(body, radius, radius, Path.Direction.CW)
            moveTo(cx - pointer, body.bottom - 1f)
            lineTo(cx, tip)
            lineTo(cx + pointer, body.bottom - 1f)
            close()
        }

        canvas.drawPath(shape, paint(0x40000000).apply { maskFilter = BlurMaskFilter(2f * density, BlurMaskFilter.Blur.NORMAL) })
        canvas.drawPath(shape, paint(WHITE).apply { style = Paint.Style.STROKE; strokeWidth = 2 * rim; strokeJoin = Paint.Join.ROUND })
        canvas.drawPath(shape, paint(colours.background))

        // The chain's colour bands along the top edge, clipped to the rounded body.
        if (colours.stripes.isNotEmpty()) {
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(body, radius, radius, Path.Direction.CW) })
            val band = 2.5f * density
            colours.stripes.forEachIndexed { i, colour ->
                canvas.drawRect(body.left, body.top + i * band, body.right, body.top + (i + 1) * band, paint(colour))
            }
            canvas.restore()
        }

        val stripesHeight = colours.stripes.size * 2.5f * density
        val baseline = body.top + stripesHeight + (height - stripesHeight) / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(brand.badgeText, cx, baseline, text)
        return bitmap
    }

    private class BadgeStyle(val background: Int, val text: Int, val stripes: List<Int> = emptyList())

    private fun badgeStyle(brand: StoreBrand): BadgeStyle = when (brand) {
        StoreBrand.SevenEleven -> BadgeStyle(WHITE, 0xFF008163.toInt(), listOf(0xFFF58220.toInt(), 0xFF008163.toInt(), 0xFFEE2737.toInt()))
        StoreBrand.FamilyMart -> BadgeStyle(WHITE, 0xFF0068B7.toInt(), listOf(0xFF00A040.toInt(), 0xFF0068B7.toInt()))
        StoreBrand.Lawson -> BadgeStyle(0xFF0068B7.toInt(), WHITE)
        StoreBrand.Ministop -> BadgeStyle(0xFFFFD400.toInt(), 0xFF004EA2.toInt())
        StoreBrand.DailyYamazaki -> BadgeStyle(0xFFE60012.toInt(), WHITE)
        StoreBrand.Seicomart -> BadgeStyle(0xFFF39800.toInt(), WHITE)
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
