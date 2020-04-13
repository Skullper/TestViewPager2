package com.vibro.testviewpager2.utils

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.TransformationUtils
import com.vibro.testviewpager2.FrameworkPageAttributes
import java.security.MessageDigest

/**
 * Rotates bitmap according to the matrix stored in [pageAttributes]
 */
class RotatePageTransformer(private val pageAttributes: FrameworkPageAttributes?) : BitmapTransformation() {

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        if (pageAttributes == null) return toTransform //return original bitmap

        return TransformationUtils.rotateImageExif(pool, toTransform, getExifOrientationAngle())
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("rotation:${pageAttributes?.rotateDirection?.get()}".toByteArray())
    }

    private fun getExifOrientationAngle(): Int = when (pageAttributes?.rotateDirection?.get()) {
        90F, -270F -> ExifInterface.ORIENTATION_ROTATE_90
        180F, -180F -> ExifInterface.ORIENTATION_ROTATE_180
        270F, -90F -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

}