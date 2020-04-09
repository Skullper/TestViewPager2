package com.vibro.testviewpager2

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import io.reactivex.Observable
import java.io.File
import java.util.*
import kotlin.math.floor

class PdfRenderingEngine(private val snRenderer: SnRenderer,
                         private val pagesCache: PagesCache) {

    private val TAG = this.javaClass.simpleName

    private val pages: MutableList<PageInfo> = mutableListOf()

    fun open(files: List<File>): List<PageInfo> {
        snRenderer.open(files)
            .run { pages.addAll(this) }
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

    fun rotatePage(index: Int): Observable<RenderedPageData> {
        val updatedPage = snRenderer.rotatePage(index)
        pagesCache.updatePageInCache(RenderedPageData(updatedPage, SnRenderer.Quality.Normal, null, RenderingStatus.Wait))
        return pagesCache.getCachedPage(updatedPage)
    }

    fun rotateAllPages(index: Int): Observable<RenderedPageData> {
        return Observable.fromCallable(pagesCache::clearCache)
            .flatMap { snRenderer.rotateAllPages(RotateDirection.Clockwise()) }
            .flatMap { pagesCache.getCachedPage(snRenderer.getPages()[index]) }
    }

    fun updatePage(index: Int, rect: RectF): Observable<RenderedPageData> {
        val pageInfo = snRenderer.getPages()[index]
        val attributes = (pageInfo.pageAttributes as FrameworkPageAttributes)
        return Observable.fromCallable {
            snRenderer.updatePage(pageInfo.copy(pageAttributes = attributes))
        }
            .map {
                pagesCache.updatePageInCache(RenderedPageData(pageInfo, SnRenderer.Quality.Normal, null, RenderingStatus.Wait))
            }
            .flatMap { pagesCache.getCachedPage(snRenderer.getPages()[index]) }
    }

}

class PagesCache(private val snRenderer: SnRenderer) {

    private val TAG = this.javaClass.simpleName

    private val CACHE_SIZE = 7
    private val CACHE_BUFFER = CACHE_SIZE - 1
    private val CACHE_PAGES_SIDE_LIMIT = floor(CACHE_SIZE / 2F).toInt()

    private var cache: LinkedList<RenderedPageData> = LinkedList()

    fun getCachedPage(currentPageToRender: PageInfo): Observable<RenderedPageData> {
        return Observable
            .fromCallable { alignCache(currentPageToRender) }
            .map { cache.first { it.pageInfo.pageIndexOfTotal == currentPageToRender.pageIndexOfTotal } }
            .flatMap { page ->
                if (page.bitmap != null) {
                    Observable.just(page)
                } else {
                    snRenderer.waitRender(page.pageInfo.pageIndexOfTotal, SnRenderer.Quality.Normal)
                }
            }
    }

    fun updatePageInCache(renderData: RenderedPageData) {
        Log.d("TAG Cache", "find in cache ${renderData.pageInfo.pageIndexOfTotal}")
        val i = cache.indexOfFirst { it.pageInfo.pageIndexOfTotal == renderData.pageInfo.pageIndexOfTotal }
        if (i != -1) cache[i] = renderData
    }

    fun clearCache() {
        cache.clear()
    }

    private fun alignCache(currentPageToRender: PageInfo) {
        val i = currentPageToRender.pageIndexOfTotal
        val newCache = LinkedList<RenderedPageData>()

        fillRightSide(i, newCache)
        fillLeftSide(i, newCache)

        if (newCache.isNotEmpty()) {
            cache = newCache
            Observable.fromIterable(cache)
                .filter { it.bitmap == null && it.status == RenderingStatus.Wait }
                .doOnNext { updatePageInCache(it.copy(status = RenderingStatus.Rendering)) }
                .map { it.pageInfo.pageIndexOfTotal }
                .doOnNext { Log.d(TAG, "start rendering ${it}") }
                .flatMap { snRenderer.renderPage(it, SnRenderer.Quality.Normal) }
                .doOnNext { updatePageInCache(it) }
                .subscribeAndDispose()
        }

        cache.forEach {
            Log.d(TAG, "cache ${it.pageInfo.pageIndexOfTotal}")
        }
    }

    private fun fillRightSide(currentIndex: Int, newCache: LinkedList<RenderedPageData>) {
        val pages = snRenderer.getPages()
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
        val firstInd = if (currentIndex - CACHE_PAGES_SIDE_LIMIT > 0) currentIndex - CACHE_PAGES_SIDE_LIMIT else 0
        (currentIndex downTo firstInd).forEach { ind -> newCache.addFirst(getPage(ind)) }
    }

    private fun getPage(i: Int): RenderedPageData {
        val pages = snRenderer.getPages()
        val p = cache.firstOrNull { it.pageInfo.pageIndexOfTotal == i }
        return p ?: RenderedPageData(
            pages[i],
            SnRenderer.Quality.Normal,
            status = RenderingStatus.Wait
        )
    }

}