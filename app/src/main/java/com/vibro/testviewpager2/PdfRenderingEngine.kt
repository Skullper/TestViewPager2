package com.vibro.testviewpager2

import android.util.Log
import io.reactivex.Observable
import java.io.File
import java.util.*

class PdfRenderingEngine {

    private val TAG = this.javaClass.simpleName

    private val snRenderer = SnRenderer()
    private val pagesCache = PagesCache(snRenderer)
    private val pages: MutableList<PageInfo> = mutableListOf()

    fun open(files: List<File>): List<PageInfo> {
        snRenderer.open(files)
            .run { pages.addAll(this) }
//        pagesCache.initCache()
        return pages
    }

    fun close() {
        snRenderer.close()
    }

    fun getPage(position: Int): Observable<RenderedPageData> {
        Log.d(TAG, "get page $position")
        val pageToRender = pages[position]
        return pagesCache.getCachedPage(pageToRender)
    }


}

class PagesCache(private val snRenderer: SnRenderer) {

    private val TAG = this.javaClass.simpleName

    private val CACHE_SIZE = 7
    private val CACHE_BUFFER = CACHE_SIZE - 1
    private val CACHE_PAGES_SIDE_LIMIT = 2

    private val firstIndexInCache: Int
        get() {
            if (cache.isEmpty()) return 0
            return cache.first.pageInfo.pageIndexOfTotal
        }
    private val lastIndexInCache: Int
        get() {
            if (cache.isEmpty()) return 0
            return cache.last.pageInfo.pageIndexOfTotal
        }

    private var cache: LinkedList<RenderedPageData> = LinkedList()

    fun getCachedPage(currentPageToRender: PageInfo): Observable<RenderedPageData> {
        alignCache(currentPageToRender)
        val page =
            cache.first { it.pageInfo.pageIndexOfTotal == currentPageToRender.pageIndexOfTotal }
        return if (page.bitmap != null) {
            Observable.just(page)
        } else {
            snRenderer.waitRender(page.pageInfo.pageIndexOfTotal, SnRenderer.Quality.Normal)
        }
    }

    private fun alignCache(currentPageToRender: PageInfo) {
        val i = currentPageToRender.pageIndexOfTotal
        val pages = snRenderer.getPages()
        val newCache = LinkedList<RenderedPageData>()

        if (i > lastIndexInCache) {
            val dif = pages.lastIndex - i
            if (dif > CACHE_BUFFER) {
                (0..CACHE_BUFFER).forEach { ind -> newCache.addLast(getPage(i + ind)) }
            } else {
                val intRange = (pages.lastIndex downTo pages.lastIndex - CACHE_BUFFER)
                intRange.forEach { ind -> newCache.addFirst(getPage(ind)) }
            }
        } else if (i <= firstIndexInCache) {
            if (i > CACHE_BUFFER) {
                (i downTo i - CACHE_BUFFER).forEach { ind -> newCache.addFirst(getPage(ind)) }
            } else {
                (0..CACHE_BUFFER).forEach { ind -> newCache.addLast(getPage(ind)) }
            }
        } else {
            if (i + 3 <= pages.lastIndex && i - 3 >= 0) {
                (i + 1..i + 3).forEach { ind -> newCache.addLast(getPage(ind)) }
                (i downTo i - 3).forEach { ind -> newCache.addFirst(getPage(ind)) }
            }
        }
        if (newCache.isNotEmpty()) {
            cache = newCache
            Observable.fromIterable(cache)
                .filter { it.bitmap == null && it.status == RenderingStatus.Wait}
                .doOnNext { updatePageInCache(it.copy(status = RenderingStatus.Rendering)) }
                .map { it.pageInfo.pageIndexOfTotal }
                .flatMap { snRenderer.renderPage(it, SnRenderer.Quality.Normal) }
                .doOnNext { updatePageInCache(it) }
                .subscribeAndDispose()
        }

        cache.forEach {
            Log.d(TAG, "cache ${it.pageInfo.pageIndexOfTotal}")
        }
    }

    private fun getPage(i: Int): RenderedPageData {
        val pages = snRenderer.getPages()
        val p = cache.firstOrNull { it.pageInfo.pageIndexOfTotal == i }
        return p ?: RenderedPageData(pages[i], SnRenderer.Quality.Normal, status = RenderingStatus.Wait)
    }

    private fun updatePageInCache(renderData: RenderedPageData) {
        Log.d("TAG Cache", "find in cache ${renderData.pageInfo.pageIndexOfTotal}")
        val i =
            cache.indexOfFirst { it.pageInfo.pageIndexOfTotal == renderData.pageInfo.pageIndexOfTotal }
        if (i != -1) cache[i] = renderData
    }
}