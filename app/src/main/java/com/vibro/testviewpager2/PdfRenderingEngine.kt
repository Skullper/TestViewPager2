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
    private var prevIndex: Int = 0

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

        val i = currentPageToRender.pageIndexOfTotal
        Log.d(TAG, "get cached page $i")
        val cachedPage = cache.firstOrNull { it.pageInfo.pageIndexOfTotal == i }

        alignCache(currentPageToRender)
//        val pageToReturn = if (cachedPage == null) {
//
//            val pages = snRenderer.getPages()
//            val newCache = LinkedList<RenderedPageData>()
//
//            if (i > lastIndexInCache) {
//                val dif = pages.lastIndex - i
//                if (dif > CACHE_BUFFER) {
//                    (0..CACHE_BUFFER).forEach { ind -> newCache.addLast(getPage(i + ind)) }
//                } else {
//                    val intRange = (pages.lastIndex downTo pages.lastIndex - CACHE_BUFFER)
//                    intRange.forEach { ind -> newCache.addFirst(getPage(ind)) }
//                }
//            } else if (i < firstIndexInCache) {
//                if (i > CACHE_BUFFER) {
//                    (i downTo i-CACHE_BUFFER).forEach { ind -> newCache.addFirst(getPage(ind)) }
//                }else{
//                    (0..CACHE_BUFFER).forEach { ind -> newCache.addLast(getPage(ind)) }
//                }
//            }
//            cache = newCache
//            cache.forEach {
//                Log.d(TAG, "cache ${it.pageInfo.pageIndexOfTotal}")
//            }
//            Observable.error(IllegalAccessError())
//        } else if (cachedPage.bitmap == null) {
//            snRenderer.waitRender(i, SnRenderer.Quality.Normal)
//                .doOnNext { updatePageInCachePage(it) }
//                .doOnNext { next(currentPageToRender) }
//        } else {
//            Observable.just(cachedPage)
//                .doOnNext { next(currentPageToRender) }
//        }

        return Observable.just(RenderedPageData(currentPageToRender, SnRenderer.Quality.Normal))
//        return pageToReturn
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
        cache = if (newCache.isNotEmpty()) newCache else cache
        cache.forEach {
            Log.d(TAG, "cache ${it.pageInfo.pageIndexOfTotal}")
        }

    }

    private fun getPage(i: Int): RenderedPageData {
        val pages = snRenderer.getPages()
        val p = cache.firstOrNull { it.pageInfo.pageIndexOfTotal == i }?.pageInfo ?: pages[i]
        return RenderedPageData(p, SnRenderer.Quality.Normal)
    }

    private fun next(pageToRender: PageInfo) {
        renderNextPage(pageToRender)
        prevIndex = pageToRender.pageIndexOfTotal
    }

    private fun renderNextPage(pageToRender: PageInfo) {
        if (pageToRender.pageIndexOfTotal > prevIndex) {
            //going forward
            val pages = snRenderer.getPages()
            if (lastIndexInCache != pages.lastIndex) {
                if (lastIndexInCache - pageToRender.pageIndexOfTotal <= CACHE_PAGES_SIDE_LIMIT) {
                    val nextIndexToRender = lastIndexInCache + 1
                    renderToCache(Direction.Forward(nextIndexToRender))
                }
            }
        } else {
            //going back
            if (firstIndexInCache != 0) {
                if (pageToRender.pageIndexOfTotal - firstIndexInCache <= CACHE_PAGES_SIDE_LIMIT) {
                    val nextIndexToRender = firstIndexInCache - 1
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