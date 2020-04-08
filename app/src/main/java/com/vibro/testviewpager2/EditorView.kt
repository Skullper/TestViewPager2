package com.vibro.testviewpager2

import android.content.Context
import android.net.Uri
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
import io.reactivex.android.schedulers.AndroidSchedulers
import kotlinx.android.synthetic.main.view_editor.view.*
import org.koin.core.KoinComponent
import org.koin.core.inject
import java.io.File

class EditorView : FrameLayout, KoinComponent {

    private val engine: PdfRenderingEngine by inject()

    constructor(context: Context) : super(context) {
        initView(context)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initView(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, @AttrRes defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        initView(context, attrs, defStyleAttr)
    }

    constructor(
        context: Context,
        attrs: AttributeSet?, @AttrRes defStyleAttr: Int, @StyleRes defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        initView(context, attrs, defStyleAttr)
    }

    fun show(files: List<File>, fragmentActivity: FragmentActivity): Observable<Unit> {
        if (files.isEmpty()) throw IllegalArgumentException("List with files is empty")


        return Observable.just(files)
            .map {
                engine.open(it)
                Unit
            }
            .observeOn(AndroidSchedulers.mainThread())
            .doFinally {
                val adapter = PageAdapter(fragmentActivity)
                viewPager.adapter = adapter
            }
    }

    fun close() {
        engine.close()
    }

    private fun initView(
        context: Context,
        attrs: AttributeSet? = null, @AttrRes defStyleAttr: Int = 0, @StyleRes defStyleRes: Int = 0
    ) {
        View.inflate(context, R.layout.view_editor, this)
        viewPager?.setPageTransformer(MarginPageTransformer(50))
    }

    fun addPage(imageUri: Uri) {
        engine.addPage(imageUri)
        viewPager.adapter?.notifyDataSetChanged()
        setPage(engine.getPagesCount() - 1)
    }

    fun setPage(index: Int) {
        viewPager.setCurrentItem(index, false)
    }

    inner class PageAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

        override fun getItemCount(): Int = engine.getPagesCount()

        override fun createFragment(position: Int): Fragment {
            return PageFragment.newInstance(position)
        }

    }

}
