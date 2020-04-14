package com.vibro.testviewpager2

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.vibro.testviewpager2.crop.CroppingDataHolder
import com.vibro.testviewpager2.crop.PageCroppingActivity
import com.vibro.testviewpager2.crop.getCroppingActivityIntent
import kotlinx.android.synthetic.main.fragment_page.*
import org.koin.android.ext.android.inject

class PageFragment : Fragment() {

    private val renderer: PdfRenderingEngine by inject()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_page, container)
    }

    private var pageIndex: Int = 0
    private var renderedPageData: RenderedPageData? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pageIndex = arguments?.getInt(ARG_PAGE_INDEX) ?: 0
        position.setText(pageIndex.toString())
        renderer.getPage(pageIndex)
            .compose(applySchedulersObservable())
            .subscribeAndDispose(
                { pageData ->
                    renderedPageData = pageData
                    iv_page_fragment?.setImageBitmap(pageData.bitmap)
                    progressBar?.visibility = View.GONE
                },
                { error -> Log.e("TAGA", "Error: ${error.message}") }
            )
        btn_remove.setOnClickListener {
            (activity as? MainActivity)?.removePage(pageIndex)
        }
        iv_page_fragment?.setOnClickListener {
            cropPage()
        }
    }

    private fun cropPage() {
        CroppingDataHolder.bitmap = renderedPageData?.bitmap
        val intent = activity?.getCroppingActivityIntent()
        startActivityForResult(
            intent,
            PageCroppingActivity.RC_CROPPING
        )
    }

    private fun rotateAllPages() {
        renderer.rotateAllPages(pageIndex, RotateDirection.Clockwise(90F))
            .compose(applySchedulersObservable())
            .subscribeAndDispose(
                { page ->
                    iv_page_fragment?.setImageBitmap(page.bitmap)
                    (activity as? MainActivity)?.reloadEditorView()
                },
                { error -> Log.e("TAGA", "Rotating error: ${error.message}") }
            )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                PageCroppingActivity.RC_CROPPING -> {
                    data?.getStringExtra(PageCroppingActivity.EXTRA_IMAGE_URI)?.let { croppedImageUri ->
                        renderer.updatePageUri(pageIndex, Uri.parse(croppedImageUri))
                            .compose(applySchedulersObservable())
                            .subscribeAndDispose(
                                { renderedPageData ->
                                    this.renderedPageData = renderedPageData
                                    iv_page_fragment?.setImageBitmap(renderedPageData.bitmap)
                                },
                                { Log.e("CROPPING", "Cropping error: ${it.message}") },
                                {
                                    //Must be recycled only when cropped image successfully saved on disk
                                    //otherwise crash will occur because CroppingDataHolder hold reference for real bitmap
                                    CroppingDataHolder.bitmap?.recycle()
                                    CroppingDataHolder.bitmap = null
                                }
                            )
                    } ?: Log.e("TAGA", "Error occurred while getting uri")
                }
            }
        }
    }

    companion object {

        const val ARG_PAGE_INDEX = "PAGE_INDEX"

        fun newInstance(index: Int): PageFragment {
            return PageFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_PAGE_INDEX, index)
                }
            }
        }
    }
}