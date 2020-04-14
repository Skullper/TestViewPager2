package com.vibro.testviewpager2.utils

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import kotlin.math.min
import kotlin.math.roundToInt

class PageViewSizeCalculator {

    private val screenHeight = Resources.getSystem().displayMetrics.heightPixels
    private val screenWidth = Resources.getSystem().displayMetrics.widthPixels

    fun calculatePageSize(currentPage: Any, qualityFactor: Float): Pair<Int, Int> {
        val (pageWidth, pageHeight) = getPageDimensions(currentPage)

        val screenHeight = screenHeight
        val screenWidth = screenWidth
        val scaleToFitHeight = screenHeight.toFloat() / pageHeight
        val scaleToFitWidth = screenWidth.toFloat() / pageWidth

        val scale = min(scaleToFitWidth, scaleToFitHeight)
        val newWidth = ((scale * qualityFactor) * pageWidth).roundToInt()
        val newHeight = ((scale * qualityFactor) * pageHeight).roundToInt()

        return Pair(newWidth, newHeight)
    }

    private fun getPageDimensions(page: Any): Pair<Int, Int> {
        var pageWidth = 0
        var pageHeight = 0
        if (page is PdfRenderer.Page) {
            pageWidth = page.width
            pageHeight = page.height
        } else if (page is Bitmap) {
            pageWidth = page.width
            pageHeight = page.height
        }
        return Pair(pageWidth, pageHeight)
    }

}