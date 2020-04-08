package com.vibro.testviewpager2

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import kotlinx.android.synthetic.main.fragment_page.*
import org.koin.android.ext.android.inject
import org.koin.core.inject

class PageFragment : Fragment() {

    private val renderer: PdfRenderingEngine by inject()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_page, container)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val pageIndex = arguments?.getInt(ARG_PAGE_INDEX) ?: 0
        position.setText(pageIndex.toString())
        renderer.getPage(pageIndex)
            .compose(applySchedulersObservable())
            .subscribeAndDispose(
                { bitmap ->
                    iv_page_fragment?.setImageBitmap(bitmap.bitmap)
                    progressBar?.visibility = View.GONE
                },
                { error -> Log.e("TAGA", "Error: ${error.message}") }
            )
        iv_page_fragment?.setOnClickListener {
//            renderer.rotatePage(pageIndex)
//                .compose(applySchedulersObservable())
//                .map { page -> page.bitmap!! }
//                .subscribeAndDispose(
//                    { iv_page_fragment?.setImageBitmap(it)},
//                    { error -> Log.e("TAGA", "Rotating error: ${error.message}")}
//                )
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