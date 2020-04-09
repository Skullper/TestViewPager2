package com.vibro.testviewpager2.utils

import android.graphics.Bitmap
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.vibro.testviewpager2.FrameworkPageAttributes
import java.security.MessageDigest

/**
 * Rotates bitmap according to the matrix stored in [pageAttributes]
 */
class RotatePageTransformer(private val pageAttributes: FrameworkPageAttributes?) : BitmapTransformation() {

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        if (pageAttributes == null) return toTransform //return original bitmap

        return Bitmap.createBitmap(toTransform, 0, 0, toTransform.width, toTransform.height, pageAttributes.matrix, true)
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("rotation:${pageAttributes?.matrix?.toShortString()}".toByteArray())
    }

}