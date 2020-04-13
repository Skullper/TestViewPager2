package com.vibro.testviewpager2

import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import com.bumptech.glide.Glide
import com.vibro.testviewpager2.utils.RotatePageTransformer
import io.reactivex.Observable
import java.io.File
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.floor

class PdfRenderingEngine(
    private val context: Context,
    private val snRenderer: SnRenderer
) : RenderPagesProvider {

    private val TAG = this.javaClass.simpleName

    private val pagesCache = PagesCache(this)

    private val originalPages: MutableList<PageInfo> = mutableListOf()
    private val currentPages: MutableList<PageInfo> by lazy { ArrayList<PageInfo>(originalPages) }
    private var hasChanges = false

    fun open(files: List<File>): List<PageInfo> {
        return snRenderer.open(files).apply { originalPages.addAll(this) }
    }

    fun close() {
        snRenderer.close()
        pagesCache.clearCache()
    }

    override fun getPages() = if (currentPages.isEmpty()) originalPages else currentPages

    override fun providePage(index: Int, quality: SnRenderer.Quality): Observable<RenderedPageData> {
        return if (!pageIsNewOrHasChanges(index)) {
            snRenderer.renderPage(index, quality)
        } else {
            Observable.fromCallable {
                val currentPage = currentPages[index]
                val transformer = RotatePageTransformer(currentPage.pageAttributes as? FrameworkPageAttributes)
                val b = Glide.with(context).asBitmap().load(currentPage.pageChangesData?.uri).transform(transformer).submit().get()
                RenderedPageData(currentPage, quality, b, RenderingStatus.Complete)
            }
        }
    }

    fun addPage(uri: Uri): Observable<Boolean> {
        return Observable.fromCallable {
            val pageIndex = currentPages.lastIndex + 1
            val matrix = Matrix()
            val attributes = FrameworkPageAttributes(Pair(0, 0), Pair(0, 0), RotateDirection.Clockwise(0F), matrix)
            val pageInfo = PageInfo(null, pageIndex, pageIndex, attributes, PageChangesData(uri))
            currentPages.add(pageInfo)
            hasChanges = true
            hasChanges
        }
    }

    fun pageIsNewOrHasChanges(index: Int): Boolean {
        return when {
            currentPages.isEmpty() -> false
            else -> currentPages[index].pageChangesData != null
        }
    }

    fun getPage(index: Int): Observable<RenderedPageData> {
        Log.d(TAG, "get page $index")
        return pagesCache.getCachedPage(index)
            .flatMap { cachedPage: RenderedPageData ->
                if (cachedPage.bitmap != null) {
                    Observable.just(cachedPage)
                } else {
                    snRenderer.waitRender(index, SnRenderer.Quality.Normal)
                }
            }
    }

    fun rotatePage(index: Int, direction: RotateDirection = RotateDirection.Clockwise()): Observable<RenderedPageData> {
        return if (!pageIsNewOrHasChanges(index)) {
            rotatePageFromRenderer(index, direction)
        } else {
            rotatePageFromUri(index, direction)
        }
    }

    private fun rotatePageFromUri(index: Int, direction: RotateDirection): Observable<RenderedPageData> {
        return Observable.fromCallable {
            val currentPage = currentPages[index]
            val attributes = (currentPage.pageAttributes as? FrameworkPageAttributes)?.copy(rotateDirection = direction)
            attributes?.matrix?.preRotate(direction.angle)
            val updatePage = currentPage.copy(pageAttributes = attributes)
            val transformer = RotatePageTransformer(attributes)
            val b = Glide.with(context).asBitmap().load(currentPage.pageChangesData?.uri).transform(transformer).submit().get()
            currentPages[index] = updatePage
            RenderedPageData(currentPages[index], SnRenderer.Quality.Normal, b, RenderingStatus.Complete)
        }
    }

    private fun rotatePageFromRenderer(index: Int, direction: RotateDirection = RotateDirection.Clockwise()): Observable<RenderedPageData> {
        return Observable.fromCallable {
            val rotatedPage = snRenderer.rotatePage(index, direction)
            val renderedPageData = RenderedPageData(rotatedPage, SnRenderer.Quality.Normal, null, RenderingStatus.Wait)
            pagesCache.updatePageInCache(renderedPageData)
        }.flatMap { getPage(index) }
    }

    fun rotateAllPages(index: Int, direction: RotateDirection): Observable<RenderedPageData> {
        if (currentPages.isEmpty()) {
            return snRenderer.rotateAllPages(direction)
                .map { pagesCache.clearCache() }
                .flatMap { getPage(index) }
        } else {
            // TODO(10.04.2020) Change it
            currentPages.forEach {
                if (pageIsNewOrHasChanges(it.pageIndexOfTotal)) {
                    val attributes =
                        (it.pageAttributes as? FrameworkPageAttributes)?.copy(rotateDirection = direction)
                    attributes?.matrix?.preRotate(direction.angle)
                    currentPages[it.pageIndexOfTotal] = it.copy(pageAttributes = attributes)
                }
            }
            return Observable.fromCallable(pagesCache::clearCache)
                .flatMap { snRenderer.rotateAllPages(direction) }
                .flatMap { getPage(index) }
        }
    }

}

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
        val i = cache.indexOfFirst { it.pageInfo.pageIndexOfTotal == renderData.pageInfo.pageIndexOfTotal }
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
        val firstInd = if (currentIndex - CACHE_PAGES_SIDE_LIMIT > 0) currentIndex - CACHE_PAGES_SIDE_LIMIT else 0
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

}

interface RenderPagesProvider {
    fun getPages(): List<PageInfo>
    fun providePage(index: Int, quality: SnRenderer.Quality): Observable<RenderedPageData>
}