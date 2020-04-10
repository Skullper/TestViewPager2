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

class PdfRenderingEngine(private val context: Context, private val snRenderer: SnRenderer) :
    RenderPagesProvider {

    private val TAG = this.javaClass.simpleName

    private val cache = PagesCache(this)

    private val originalPages: MutableList<PageInfo> = mutableListOf()
    private val currentPages: MutableList<PageInfo> by lazy {
        hasChanges = true
        ArrayList<PageInfo>(originalPages)
    }
    private var hasChanges = false

    fun open(files: List<File>): List<PageInfo> {
        return snRenderer.open(files).apply { originalPages.addAll(this) }
    }

    fun close() {
        snRenderer.close()
        cache.clearCache()
    }

    override fun getPages() = if (!hasChanges) originalPages else currentPages

    override fun providePage(index: Int, quality: SnRenderer.Quality): Observable<RenderedPageData> {
        if (!pageIsNewOrHasChanges(index)) {
            val pageInfo = getPages()[index]
            return snRenderer.renderPage(pageInfo, quality)
        } else {
            return Observable.fromCallable {
                val currentPage = currentPages[index]
                val b =
                    Glide.with(context).asBitmap().load(currentPage.pageChangesData?.storedPage)
                        .submit()
                        .get()
                RenderedPageData(currentPage, quality, b, RenderingStatus.Complete)
            }
        }
    }

    fun addPage(uri: Uri): Observable<Boolean> {
        return Observable.fromCallable {
            val pageIndex = currentPages.lastIndex + 1
            val pageInfo = PageInfo(null, -1, pageIndex, null, PageChangesData(uri))
            currentPages.add(pageInfo)
            true
        }
    }

    fun rearrangePage(indexFrom: Int, indexTo: Int): Observable<Unit> {
        return Observable.fromCallable {
            val pageInfoToRearrange = currentPages.removeAt(indexFrom)
            currentPages.add(indexTo, pageInfoToRearrange)
            updateIndexes()
            cache.clearCache()
        }
    }

    private fun updateIndexes() {
        val mapped = currentPages.mapIndexed { i, p -> p.copy(pageIndexOfTotal = i) }
        currentPages.clear()
        currentPages.addAll(mapped)
    }

    /**
     * Return prev page index
     */
    fun removePage(index: Int): Observable<Unit> {
        if (currentPages.size == 1) return Observable.error(LastPageCannotBeRemoved())
        return Observable.fromCallable {
            val pageInfo = currentPages.removeAt(index)
            updateIndexes()
            cache.remove(pageInfo)
        }
    }


    fun getPage(index: Int, quality: SnRenderer.Quality = SnRenderer.Quality.Normal): Observable<RenderedPageData> {
        Log.d(TAG, "get page $index")
        return when (quality) {
            SnRenderer.Quality.Normal -> provideViaCache(index)
            else -> providePage(index,quality)
        }
    }

    private fun provideViaCache(index: Int): Observable<RenderedPageData> {
        return cache.getCachedPage(index)
            .flatMap { cachedPage: RenderedPageData ->
                if (cachedPage.bitmap != null) {
                    Observable.just(cachedPage)
                } else {
                    snRenderer.waitRender(cachedPage.pageInfo.id, SnRenderer.Quality.Normal)
                }
            }
    }

    private fun pageIsNewOrHasChanges(index: Int): Boolean {
        return currentPages[index].pageChangesData != null
    }

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

class LastPageCannotBeRemoved : RuntimeException()

class PagesCache(private val renderPagesProvider: RenderPagesProvider) {

    private val TAG = this.javaClass.simpleName

    private val CACHE_SIZE = 7
    private val CACHE_BUFFER = CACHE_SIZE - 1
    private val CACHE_PAGES_SIDE_LIMIT = floor(CACHE_SIZE / 2F).toInt()

    private var cache: LinkedList<RenderedPageData> = LinkedList()

    fun getCachedPage(index: Int): Observable<RenderedPageData> {
        return Observable
            .fromCallable { alignCache(index) }
            .map { cache.first { it.pageInfo.pageIndexOfTotal == index } }
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

    fun alignCache(index: Int) {
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
                .flatMap { renderPagesProvider.providePage(it, SnRenderer.Quality.Normal) }
                .doOnNext { updatePageInCache(it) }
                .subscribeAndDispose()
        }

        cache.forEach {
            Log.d(TAG, "cache ${it.pageInfo.pageIndexOfTotal}")
        }
    }

    private fun fillRightSide(currentIndex: Int, newCache: LinkedList<RenderedPageData>) {
        val pages = renderPagesProvider.getPages()
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
        val pages = renderPagesProvider.getPages()
        val p = cache.firstOrNull { it.pageInfo.pageIndexOfTotal == i }
        return p ?: RenderedPageData(
            pages[i],
            SnRenderer.Quality.Normal,
            status = RenderingStatus.Wait
        )
    }

    fun remove(pageInfo: PageInfo) {
        cache.find { it.pageInfo.id == pageInfo.id }?.let { cache.remove(it) }
    }

}

interface RenderPagesProvider {
    fun getPages(): List<PageInfo>
    fun providePage(index: Int, quality: SnRenderer.Quality): Observable<RenderedPageData>
}