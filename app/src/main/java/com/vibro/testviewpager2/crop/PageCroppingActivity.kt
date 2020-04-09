package com.vibro.testviewpager2.crop

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.vibro.testviewpager2.R
import kotlinx.android.synthetic.main.activity_cropping.*

fun Context.getCroppingActivityIntent() = Intent(this, PageCroppingActivity::class.java)

object CroppingDataHolder {
    var bitmap: Bitmap? = null
}

/**
 * Used for cropping bitmap stored in [CroppingDataHolder]. If [CroppingDataHolder.bitmap]
 * doesn't contains data screen will be closed.
 * NOTE: Need to find a way to inform user about occured issue. In current version no message displayed
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
        }
        //Return to original image
        btn_crop_reset.setOnClickListener {
            iv_cropping_activity.resetCropRect()
            CroppingDataHolder.bitmap = originalBitmap
            iv_cropping_activity.setImageBitmap(originalBitmap)
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
                CroppingDataHolder.bitmap = iv_cropping_activity.croppedImage
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
        return true
    }

    companion object {

        const val RC_CROPPING = 3511
        const val EXTRA_IMAGE_URI = "j7uhas%a"
    }
}