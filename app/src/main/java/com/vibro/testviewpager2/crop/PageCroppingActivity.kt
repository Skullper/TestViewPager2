package com.vibro.testviewpager2.crop

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.vibro.testviewpager2.R
import kotlinx.android.synthetic.main.activity_cropping.*
import java.io.File
import kotlin.random.Random

fun Context.getCroppingActivityIntent() = Intent(this, PageCroppingActivity::class.java)

object CroppingDataHolder {
    var bitmap: Bitmap? = null
}

/**
 * Used for cropping bitmap stored in [CroppingDataHolder]. If [CroppingDataHolder.bitmap]
 * doesn't contains data screen will be closed.
 */
class PageCroppingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cropping)
        //Used when reset btn clicked
        val originalBitmap = CroppingDataHolder.bitmap
        val imageUri: Uri = Uri.parse(intent.getStringExtra(EXTRA_IMAGE_URI) ?: "")
        //Display bitmap if exists, goBack otherwise
        if (CroppingDataHolder.bitmap == null && imageUri.path.isNullOrBlank()) {
            finish()
        } else {
            CroppingDataHolder.bitmap?.let { iv_cropping_activity?.setImageBitmap(it) }
                ?: iv_cropping_activity?.setImageUriAsync(imageUri)
        }
        //Return to original image
        btn_crop_reset.setOnClickListener {
            iv_cropping_activity.resetCropRect()
            CroppingDataHolder.bitmap = originalBitmap
            iv_cropping_activity.setImageBitmap(originalBitmap)
        }
        iv_cropping_activity?.setOnCropImageCompleteListener { _, result ->
            Log.e("LOADING", "Dismissed")
            val croppedImageUri = result.uri
            if (croppedImageUri == null) {
                Toast.makeText(this, "Image cannot be cropped", Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_CANCELED)
            } else {
                intent.putExtra(EXTRA_IMAGE_URI, result.uri.toString())
                setResult(Activity.RESULT_OK, intent)
            }
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.crop_activity_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            android.R.id.home -> onBackPressed()
            R.id.crop_done -> {
                Log.e("LOADING", "Started")
                val croppedImageUri = CroppedImageProcessor().getUriForCroppedImage(this)
                iv_cropping_activity?.saveCroppedImageAsync(croppedImageUri, Bitmap.CompressFormat.PNG, 100)
            }
        }
        return true
    }

    companion object {

        const val RC_CROPPING = 3511
        const val EXTRA_IMAGE_URI = "j7uhas%a"
    }
}

private const val DIRECTORY = "app_images"
class CroppedImageProcessor {

    fun getUriForCroppedImage(context: Context): Uri {
        val croppedImagesDirectoryPath = getFilesDir(context).path.plus("/").plus("cropped_images").plus("/")
        val directory = File(croppedImagesDirectoryPath)
        if (!directory.exists()) directory.mkdirs()
        val fileName = "${System.currentTimeMillis()}_${Random.nextInt(0, 10)}.png"
        val imagePath = File(directory, fileName)
        return imagePath.toUri()
    }

    private fun getFilesDir(context: Context): File {
        val appDirectory = context.getExternalFilesDir(DIRECTORY)
        return if (Environment.MEDIA_MOUNTED == Environment.getExternalStorageState() && appDirectory != null) {
            appDirectory
        } else {
            File(context.filesDir, DIRECTORY).apply { mkdirs() }
        }
    }
}