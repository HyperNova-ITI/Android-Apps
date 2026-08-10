package com.hypernova.navigation.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.content.ContextCompat
import com.hypernova.navigation.R

internal object VehicleArrowBitmap {
    fun create(context: Context): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (MARKER_SIZE_DP * density).toInt().coerceAtLeast(1)
        val center = size / 2.0f
        val radius = size * 0.34f
        val cyan =
            ContextCompat.getColor(context, R.color.hypernova_cyan)
        val outline =
            ContextCompat.getColor(context, R.color.hypernova_cyan_dark)
        val bitmap =
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.style = Paint.Style.FILL
        paint.color = (cyan and RGB_MASK) or HALO_ALPHA
        canvas.drawCircle(center, center, size * 0.28f, paint)

        val arrow =
            Path().apply {
                moveTo(center, center - radius)
                lineTo(center + radius * 0.72f, center + radius * 0.78f)
                lineTo(center, center + radius * 0.34f)
                lineTo(center - radius * 0.72f, center + radius * 0.78f)
                close()
            }

        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = size * 0.11f
        paint.color = outline
        canvas.drawPath(arrow, paint)

        paint.style = Paint.Style.FILL
        paint.color = cyan
        canvas.drawPath(arrow, paint)
        return bitmap
    }

    private const val MARKER_SIZE_DP = 58
    private const val RGB_MASK = 0x00FFFFFF
    private const val HALO_ALPHA = 0x33000000
}
