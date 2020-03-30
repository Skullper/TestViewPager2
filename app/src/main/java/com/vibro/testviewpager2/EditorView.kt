package com.vibro.testviewpager2

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.MarginPageTransformer
import io.reactivex.Observable
import kotlinx.android.synthetic.main.view_editor.view.*
import org.koin.core.KoinComponent
import org.koin.core.get
import org.koin.core.parameter.parametersOf
import java.io.File

class SnRendererWrapper(val pdfPageProvider: SnRenderer)

class EditorView : FrameLayout, KoinComponent {

    private lateinit var renderer: SnRenderer
    private val pages: MutableList<PageInfo> = mutableListOf()

    constructor(context: Context) : super(context) {
        initView(context)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initView(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, @AttrRes defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        initView(context, attrs, defStyleAttr)
    }

    constructor(context: Context, attrs: AttributeSet?, @AttrRes defStyleAttr: Int, @StyleRes defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes) {
        initView(context, attrs, defStyleAttr)
    }

    fun show(files: List<File>, fragmentActivity: FragmentActivity): Observable<Unit> {
        if (files.isEmpty()) throw IllegalArgumentException("List with files is empty")

        return Observable.just(files)
                .map {
                    renderer = SnRenderer(it)
                    get<SnRendererWrapper> { parametersOf(renderer) }
                    pages.addAll(renderer.getPages())
                    Unit
                }
                .doFinally {
                    val adapter = PageAdapter(fragmentActivity, pages)
                    viewPager.adapter = adapter
                }
    }

    fun getCurrentPage(): PageInfo {
        return pages[viewPager.currentItem]
    }

    private fun initView(context: Context, attrs: AttributeSet? = null, @AttrRes defStyleAttr: Int = 0, @StyleRes defStyleRes: Int = 0) {
        View.inflate(context, R.layout.view_editor, this)
        viewPager?.setPageTransformer(MarginPageTransformer(50))
    }
}

class PageAdapter(fragmentActivity: FragmentActivity, private val pages: List<PageInfo>) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int = pages.size

    override fun createFragment(position: Int): Fragment {
        return PageFragment.newInstance(pages[position])
    }

}