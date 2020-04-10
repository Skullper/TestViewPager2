package com.vibro.testviewpager2

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream


class MainActivity : AppCompatActivity() {

    private val RC_GET_IMG: Int = 2222
    val DIRECTORY = "app_docs"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val files = listOf(
            getPdf(this, "b.pdf")
//            getPdf(this, "b.pdf")
        )
        editorView.show(files, this)
            .compose(applySchedulersObservable())
            .subscribeAndDispose()

        btn_add_page.setOnClickListener {
            openPicker()
//            editorView.openPage()
        }

        btn_rearrange.setOnClickListener {
            editorView.rearrangePage(0, 5).subscribeAndDispose()
        }
    }

    fun openPicker() {
        val photoPickerIntent = Intent(Intent.ACTION_PICK)
        photoPickerIntent.type = "image/*"
        startActivityForResult(photoPickerIntent, RC_GET_IMG)
    }

    fun removePage(pageIndex: Int) {
        editorView.removePage(pageIndex)
            .subscribeAndDispose()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            try {
                val imageUri: Uri? = data?.data
                imageUri?.let {
                    editorView.addPage(imageUri).subscribeAndDispose()
                }
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
                Toast.makeText(this, "Something went wrong", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "You haven't picked Image", Toast.LENGTH_LONG).show()
        }
    }


    override fun onDestroy() {
        editorView.close()
        super.onDestroy()
    }

    fun getPdf(context: Context, filenameInAssets: String): File {
        val unsyncedDocsCacheDir =
            getFilesDir("$DIRECTORY/1").path.plus("/").plus("unsynced_docs").plus("/")
        val name = filenameInAssets.substringBeforeLast(".")
        val format = filenameInAssets.substringAfterLast(".")
        val filename = generateFilename(name, format, unsyncedDocsCacheDir)
        File(unsyncedDocsCacheDir).mkdirs()
        val file = File(unsyncedDocsCacheDir, filename)
        try {
            file.createNewFile()
            // Since PdfRenderer cannot handle the compressed asset file directly, we copy it into
            // the cache directory.
            val asset = context.resources.assets.open(filenameInAssets)
            val output = FileOutputStream(file)
            val buffer = ByteArray(1024)
            var size: Int = 0
            while ((size != -1)) {
                size = asset.read(buffer)
                if (size != -1) {
                    output.write(buffer, 0, size)
                }
            }
            asset.close()
            output.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return file
    }

    private fun getFilesDir(subDir: String): File {
        val externalFilesDir = getExternalFilesDir(subDir)
        return if (Environment.MEDIA_MOUNTED == Environment.getExternalStorageState() && externalFilesDir != null) {
            externalFilesDir
        } else {
            File(filesDir, subDir).apply { mkdirs() }
        }
    }

    fun generateFilename(
        filename: String,
        format: String?,
        filesDir: String,
        counter: Int = 0
    ): String {
        val name = if (counter > 0) "($counter)$filename" else filename
        val newFormat = format?.substringAfterLast(".")
        val filenameWithFormat = format?.let { name.plus(".").plus(newFormat) } ?: name
        val file = File("$filesDir/", filenameWithFormat)
        return if (file.exists()) {
            return generateFilename(filename, format, filesDir, counter + 1)
        } else {
            filenameWithFormat
        }
    }
}
