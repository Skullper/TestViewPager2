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
        pagesCache.initCache()
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
    private val CACHE_PAGES_SIDE_LIMIT = 2
    private var prevIndex: Int = 0

    private val cache: LinkedList<RenderedPageData> = LinkedList()

    fun initCache() {
        val pageCountOfInitialFile = snRenderer.getCurrentFile().pageCount
        val pages = if (pageCountOfInitialFile < CACHE_SIZE) {
            (0 until pageCountOfInitialFile)
        } else {
            (0 until CACHE_SIZE)
        }
        pages.forEach { renderToCache(Direction.Forward(it)) }
    }

    private fun renderToCache(direction: Direction) {
        //write cache stub
        val pages = snRenderer.getPages()
        val pageToRender = pages[direction.index]
        when (direction) {
            is Direction.Backward -> {
                cache.addFirst(RenderedPageData(pageToRender, null, null))
                if (cache.size > CACHE_SIZE) clearCachedPage(cache.removeLast())
                Log.d(TAG, "page ${direction.index} added in front of cache")
            }
            is Direction.Forward -> {
                cache.addLast(RenderedPageData(pageToRender, null, null))
                if (cache.size > CACHE_SIZE) clearCachedPage(cache.removeFirst())
                Log.d(TAG, "page ${direction.index} added in the end of cache")
            }
        }

        cache.forEach { Log.d(TAG, "cache state - ${it.pageInfo.pageIndexOfTotal}") }

        snRenderer.renderPage(pageToRender.pageIndexOfTotal, SnRenderer.Quality.Normal)
            .doOnNext { updatePageInCachePage(it) }
            .subscribeAndDispose()
    }

    private fun clearCachedPage(cachedPage: RenderedPageData) {
        cachedPage.bitmap?.recycle()
    }

    fun getCachedPage(currentPageToRender: PageInfo): Observable<RenderedPageData> {

        val cachedPage =
            cache.firstOrNull { it.pageInfo.pageIndexOfTotal == currentPageToRender.pageIndexOfTotal }
        val pageToReturn = if (cachedPage?.bitmap != null) {
            Observable.just(cachedPage)
                .doOnNext { next(currentPageToRender) }
        } else {
            snRenderer.waitRender(currentPageToRender.pageIndexOfTotal, SnRenderer.Quality.Normal)
                .doOnNext { updatePageInCachePage(it) }
                .doOnNext { next(currentPageToRender) }
        }

        return pageToReturn
    }

    private fun next(pageToRender: PageInfo) {
        renderNextPage(pageToRender)
        prevIndex = pageToRender.pageIndexOfTotal
    }

    private fun renderNextPage(pageToRender: PageInfo) {
        if (pageToRender.pageIndexOfTotal > prevIndex) {
            //going forward
            val lastPageNumberInCache = cache.last.pageInfo.pageIndexOfTotal
            val pages = snRenderer.getPages()
            if (lastPageNumberInCache != pages.lastIndex) {
                if (lastPageNumberInCache - pageToRender.pageIndexOfTotal <= CACHE_PAGES_SIDE_LIMIT) {
                    val nextIndexToRender = lastPageNumberInCache + 1
                    renderToCache(Direction.Forward(nextIndexToRender))
                }
            }
        } else {
            //going back
            val firstNumberInCache = cache.first.pageInfo.pageIndexOfTotal
            if (firstNumberInCache != 0) {
                if (pageToRender.pageIndexOfTotal - firstNumberInCache <= CACHE_PAGES_SIDE_LIMIT) {
                    val nextIndexToRender = firstNumberInCache - 1
                    renderToCache(Direction.Backward(nextIndexToRender))
                }
            }
        }
    }

    private fun updatePageInCachePage(renderData: RenderedPageData) {
        Log.d("TAG Cache", "find in cache ${renderData.pageInfo.pageIndexOfTotal}")
        val i =
            cache.indexOfFirst { it.pageInfo.pageIndexOfTotal == renderData.pageInfo.pageIndexOfTotal }
        if (i != -1) cache[i] = renderData
    }

    sealed class Direction(val index: Int) {
        class Backward(index: Int) : Direction(index)
        class Forward(index: Int) : Direction(index)
    }

}