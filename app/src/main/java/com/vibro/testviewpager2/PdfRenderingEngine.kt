package com.vibro.testviewpager2

import android.content.Context
import android.net.Uri
import android.util.Log
import com.bumptech.glide.Glide
import io.reactivex.Observable
import java.io.File
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.floor

class PageProvider(private val context: Context, private val snRenderer: SnRenderer) {

    data class PageChangesData(val uri: Uri)

    private val originalPages: MutableList<PageInfo> = mutableListOf()
    private val currentPages: MutableList<PageInfo> by lazy { ArrayList<PageInfo>(originalPages) }
    private var hasChanges = false

    fun open(files: List<File>): List<PageInfo> {
        return snRenderer.open(files).apply { originalPages.addAll(this) }
    }

    fun close() = snRenderer.close()

    fun getPages() = if (currentPages.isEmpty()) originalPages else currentPages

    fun waitRender(pageIndexOfTotal: Int, quality: SnRenderer.Quality): Observable<RenderedPageData> {
        return snRenderer.waitRender(pageIndexOfTotal, quality)
    }

    fun providePage(pageIndex: Int, quality: SnRenderer.Quality): Observable<RenderedPageData> {
        if (!pageIsNewOrPageHasChanges(pageIndex)) {
            return snRenderer.renderPage(pageIndex, quality)
        } else {
            return Observable.fromCallable {
                val currentPage = currentPages[pageIndex]
                val b = Glide.with(context).asBitmap().load(currentPage.pageChangesData?.uri).submit().get()
                RenderedPageData(currentPage, SnRenderer.Quality.Normal, b, RenderingStatus.Complete)
            }
        }
    }

    fun addPage(uri: Uri) {
        val pageIndex = currentPages.lastIndex + 1
        val pageInfo = PageInfo(null, pageIndex, pageIndex, null, PageChangesData(uri))
        currentPages.add(pageInfo)
        hasChanges = true
    }

    fun pageIsNewOrPageHasChanges(index: Int): Boolean {
        return when {
            currentPages.isEmpty() -> false
            else -> currentPages[index].pageChangesData != null
        }
    }

}


class PdfRenderingEngine(
    private val pageProvider: PageProvider,
    private val pagesCache: PagesCache
) {

    private val TAG = this.javaClass.simpleName

    private val pages: MutableList<PageInfo> = mutableListOf()

    fun open(files: List<File>): List<PageInfo> {
        pageProvider.open(files).run { pages.addAll(this) }
        return pages
    }

    fun close() {
        pageProvider.close()
    }

    fun getPage(position: Int): Observable<RenderedPageData> {
        Log.d(TAG, "get page $position")
        return pagesCache.getCachedPage(position)
    }

    fun addPage(uri: Uri) {
        pageProvider.addPage(uri)
    }

    fun getPagesCount() = pageProvider.getPages().size

//    fun rotatePage(index: Int): Observable<RenderedPageData> {
//        val updatedPage = snRenderer.rotatePage(index)
//        pagesCache.updatePageInCache(RenderedPageData(updatedPage, SnRenderer.Quality.Normal, null, RenderingStatus.Wait))
//        return pagesCache.getCachedPage(updatedPage)
//    }
//
//    fun rotateAllPages(index: Int): Observable<RenderedPageData> {
//        return Observable.fromCallable(pagesCache::clearCache)
//            .flatMap { snRenderer.rotateAllPages(RotateDirection.Clockwise()) }
//            .flatMap { pagesCache.getCachedPage(snRenderer.getPages()[index]) }
//    }

}

class PagesCache(private val pageProvider: PageProvider) {

    private val TAG = this.javaClass.simpleName

    private val CACHE_SIZE = 7
    private val CACHE_BUFFER = CACHE_SIZE - 1
    private val CACHE_PAGES_SIDE_LIMIT = floor(CACHE_SIZE / 2F).toInt()

    private var cache: LinkedList<RenderedPageData> = LinkedList()

    fun getCachedPage(index: Int): Observable<RenderedPageData> {
        return Observable
            .fromCallable { alignCache(index) }
            .map { cache.first { it.pageInfo.pageIndexOfTotal == index } }
            .flatMap { page ->
                if (page.bitmap != null) {
                    Observable.just(page)
                } else {
                    pageProvider.waitRender(
                        page.pageInfo.pageIndexOfTotal,
                        SnRenderer.Quality.Normal
                    )
                }
            }
    }

    fun updatePageInCache(renderData: RenderedPageData) {
        Log.d("TAG Cache", "find in cache ${renderData.pageInfo.pageIndexOfTotal}")
        val i =
            cache.indexOfFirst { it.pageInfo.pageIndexOfTotal == renderData.pageInfo.pageIndexOfTotal }
        if (i != -1) cache[i] = renderData
    }

    fun clearCache() {
        cache.clear()
    }

    private fun alignCache(index: Int) {
        val newCache = LinkedList<RenderedPageData>()

        fillRightSide(index, newCache)
        fillLeftSide(index, newCache)

        if (newCache.isNotEmpty()) {
            cache = newCache
            Observable.fromIterable(cache)
                .filter { it.bitmap == null && it.status == RenderingStatus.Wait }
                .doOnNext { updatePageInCache(it.copy(status = RenderingStatus.Rendering)) }
                .map { it.pageInfo.pageIndexOfTotal }
                .doOnNext { Log.d(TAG, "start rendering ${it}") }
                .flatMap { pageProvider.providePage(it, SnRenderer.Quality.Normal) }
                .doOnNext { updatePageInCache(it) }
                .subscribeAndDispose()
        }

        cache.forEach {
            Log.d(TAG, "cache ${it.pageInfo.pageIndexOfTotal}")
        }
    }

    private fun fillRightSide(currentIndex: Int, newCache: LinkedList<RenderedPageData>) {
        val pages = pageProvider.getPages()
        val lastIndex = if (currentIndex < CACHE_BUFFER - CACHE_PAGES_SIDE_LIMIT) {
            CACHE_BUFFER
        } else if (currentIndex + CACHE_PAGES_SIDE_LIMIT < pages.lastIndex) {
            currentIndex + CACHE_PAGES_SIDE_LIMIT
        } else {
            pages.lastIndex
        }
        (currentIndex + 1..lastIndex).forEach { ind -> newCache.addLast(getPage(ind)) }
    }

    private fun fillLeftSide(currentIndex: Int, newCache: LinkedList<RenderedPageData>) {
        val firstInd =
            if (currentIndex - CACHE_PAGES_SIDE_LIMIT > 0) currentIndex - CACHE_PAGES_SIDE_LIMIT else 0
        (currentIndex downTo firstInd).forEach { ind -> newCache.addFirst(getPage(ind)) }
    }

    private fun getPage(i: Int): RenderedPageData {
        val pages = pageProvider.getPages()
        val p = cache.firstOrNull { it.pageInfo.pageIndexOfTotal == i }
        return p ?: RenderedPageData(
            pages[i],
            SnRenderer.Quality.Normal,
            status = RenderingStatus.Wait
        )
    }

}