package com.vibro.testviewpager2

import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.round
import kotlin.math.sqrt

private const val INITIAL_SCALE_FACTOR = 1.0F

interface PageAttributes {
    val viewSize: Pair<Int, Int>
    val pageSize: Pair<Int, Int>
    val rotateDirection: RotateDirection
    fun isPortrait(): Boolean
}

data class FrameworkPageAttributes(override val viewSize: Pair<Int, Int>,
                                   override val pageSize: Pair<Int, Int>,
                                   override val rotateDirection: RotateDirection = RotateDirection.Clockwise(),
                                   val matrix: Matrix) : PageAttributes {
    override fun isPortrait() = matrix.isPortrait()
}

data class NativePageAttributes(override val viewSize: Pair<Int, Int>,
                                override val pageSize: Pair<Int, Int>,
                                override val rotateDirection: RotateDirection = RotateDirection.Clockwise(),
                                val angle: Float) : PageAttributes{
    override fun isPortrait() = true
}

class PageTransformer {

    fun rotatePage(page: PageAttributes?): PageAttributes? {
        return if (page is FrameworkPageAttributes) performRotation(page) else TODO("Create page rotation for native approach")
    }

    private fun performRotation(page: FrameworkPageAttributes): PageAttributes {
        val matrix = Matrix()
        val (pageWidth, pageHeight) = page.pageSize
        val (viewWidth, viewHeight) = page.viewSize
        val direction = page.rotateDirection

        //Calculating point of rotation
        val rotatePx = pageWidth.toFloat() / 2F
        val rotatePy = pageHeight.toFloat() / 2F
        //Performing rotation for passed angle around the calculated point into passed direction
        matrix.preRotate(direction.angle, rotatePx, rotatePy)

        //Retrieving current matrix state
        val scale = matrix.getScale()
        val (translateX, translateY) = matrix.getTranslatePoints()

        if (matrix.isPortrait()) {
            val dx = if (translateX >= (pageWidth * scale)) translateX - (pageWidth * scale) else translateX
            val dy = if (translateY > 1) (pageHeight * scale) - translateY else -translateY
            val sx = (viewHeight.toFloat() / pageHeight.toFloat()) / scale
            val sy = sx
            //Moving page to concrete x and y coordinates
            matrix.postTranslate(-dx, dy)
            //Scaling page to concrete value
            matrix.postScale(sx, sy)
        } else {
            val dx = if (translateX > 0) (pageHeight * scale) - translateX else -translateX
            val dy = if (translateY < (pageWidth * scale)) -translateY else (pageWidth * scale) - translateY
            val sx = if (scale == INITIAL_SCALE_FACTOR) viewWidth.toFloat() / pageHeight.toFloat() else (viewWidth.toFloat() / pageHeight.toFloat()) / scale
            val sy = sx
            //Moving page to concrete x and y coordinates
            matrix.postTranslate(abs(dx), dy)
            //Scaling page to concrete value
            matrix.postScale(sx, sy)

        }
        return page.copy(matrix = matrix)
    }

    // TODO(06.04.2020) Other page modification methods

    private fun Matrix.getTranslatePoints() = FloatArray(9)
        .apply { getValues(this) }
        .let { Pair(it[Matrix.MTRANS_X], it[Matrix.MTRANS_Y]) }

    private fun Matrix.getRotationAngle() = FloatArray(9)
        .apply { getValues(this) }
        .let { abs(round(atan2(it[Matrix.MSKEW_X], it[Matrix.MSCALE_X]) * (180 / Math.PI)).toFloat()) }

    private fun Matrix.getScale() = FloatArray(9)
        .apply { getValues(this) }
        .let { sqrt(it[Matrix.MSCALE_X] * it[Matrix.MSCALE_X] + it[Matrix.MSKEW_X] * it[Matrix.MSKEW_X]) }

}

fun Matrix.isPortrait() = FloatArray(9)
    .apply { getValues(this) }
    .let { -round(atan2(it[Matrix.MSKEW_X], it[Matrix.MSCALE_X]) * (180 / Math.PI)).toFloat() }
    .let { angle -> (angle / 90F) % 2 == 0F }