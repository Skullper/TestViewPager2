package com.vibro.testviewpager2

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.*
import android.util.Log
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import kotlinx.android.parcel.Parcelize
import java.io.File
import java.util.*
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Contains information about pdf page
 *
 * @param file which contains current page
 * @param pageIndex concrete page number in [file]
 * @param pageIndexOfTotal index of the page displayed on the screen. This page number refers to the index in the list of pages of all documents
 * which are rendering at that point
 */
@Parcelize
data class PageInfo(
    val pdfFileData: PdfFileData,
    val pageIndex: Int,
    val pageIndexOfTotal: Int,
    val quality: SnRenderer.RenderType = SnRenderer.RenderType.NORMAL
) : Parcelable

@Parcelize
data class PdfFileData(val file: File, val pageCount: Int) : Parcelable

data class RenderPageData(val pageInfo: PageInfo, val bitmap: Bitmap?)

sealed class Direction(val index: Int) {
    class Backward(index: Int) : Direction(index)
    class Forward(index: Int) : Direction(index)
}


private const val CACHE_SIZE = 7
private const val CACHE_PAGES_SIDE_LIMIT = 2

class SnRenderer {

    private val TAG = this.javaClass.simpleName

    private lateinit var pdfRenderer: PdfRenderer
    private lateinit var currentFile: PdfFileData

    private val pages: MutableList<PageInfo> = mutableListOf()
    private val cache: LinkedList<RenderPageData> = LinkedList()

    private var prevIndex: Int = 0

    private val screenHeight = Resources.getSystem().displayMetrics.heightPixels
    private val screenWidth = Resources.getSystem().displayMetrics.widthPixels

    private val renderingResultPublisher = PublishSubject.create<RenderPageData>()
    private val renderingThread = HandlerThread("pdf_rendering").apply { this.start() }
    private val renderingHandler = object : Handler(renderingThread.looper) {
        override fun handleMessage(msg: Message) {
//            Thread.sleep(1000)
            val indexOfPageToRender = msg.what
            val page = pages[indexOfPageToRender]
            if (page.pdfFileData.file != currentFile.file) {
                pdfRenderer.close()
                initMainRenderer(page.pdfFileData)
            }

            val renderedPage = renderPage(pdfRenderer.openPage(page.pageIndex), page.quality)
            val renderData = RenderPageData(page, renderedPage)

            updatePageInCachePage(renderData)
            renderingResultPublisher.onNext(renderData)
        }
    }

    private fun updatePageInCachePage(renderData: RenderPageData) {
        Log.d(TAG, "find in cache ${renderData.pageInfo.pageIndexOfTotal}")
        val i = cache.indexOfFirst { it.pageInfo.pageIndexOfTotal == renderData.pageInfo.pageIndexOfTotal }
        if (i != -1) cache[i] = renderData
    }

    fun open(files: List<File>): List<PageInfo> {
        initPages(files)
        initCache()
        return pages
    }

    private fun initPages(files: List<File>) {
        var indexOfTotal = 0
        files.forEach { file ->
            val parcelFileDescriptor =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val localRenderer = PdfRenderer(parcelFileDescriptor)
            val filePagesCount = localRenderer.pageCount
            localRenderer.close()
            val fileData = PdfFileData(file, filePagesCount)
            val filePages = (0 until filePagesCount)
            filePages.forEach {
                val info = PageInfo(fileData, it, indexOfTotal++)
                pages.add(info)
            }
            if (!::currentFile.isInitialized && !::pdfRenderer.isInitialized) initMainRenderer(fileData)
        }
    }

    private fun initMainRenderer(fileData: PdfFileData) {
        currentFile = fileData
        val parcelFileDescriptor =
            ParcelFileDescriptor.open(currentFile.file, ParcelFileDescriptor.MODE_READ_ONLY)
        pdfRenderer = PdfRenderer(parcelFileDescriptor)
    }

    private fun initCache() {
        val pages = if (currentFile.pageCount < CACHE_SIZE) {
            (0 until currentFile.pageCount)
        } else {
            (0 until CACHE_SIZE)
        }
        pages.forEach { renderToCache(Direction.Forward(it)) }
    }

    fun close() {
        renderingThread.quitSafely()
    }

    fun getPage(position: Int): Observable<RenderPageData> {
        Log.d(TAG, "get page $position")
        val pageToRender = pages[position]

        val cachedPage =
            cache.firstOrNull { it.pageInfo.pageIndexOfTotal == pageToRender.pageIndexOfTotal }
        val pageToReturn = if (cachedPage?.bitmap != null) {
            Observable.just(cachedPage)
        } else {
            renderingResultPublisher.filter { it.pageInfo.pageIndexOfTotal == position }
        }

        renderNextPage(pageToRender)
        prevIndex = pageToRender.pageIndexOfTotal
        return pageToReturn
    }

    private fun renderNextPage(pageToRender: PageInfo) {
        if (pageToRender.pageIndexOfTotal > prevIndex) {
            //going forward
            val lastPageNumberInCache = cache.last.pageInfo.pageIndexOfTotal
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

    private fun renderToCache(direction: Direction) {
        //write cache stub
        val pageToRender = pages[direction.index]
        when (direction) {
            is Direction.Backward -> {
                cache.addFirst(RenderPageData(pageToRender, null))
                if (cache.size > CACHE_SIZE) clearCachedPage(cache.removeLast())
                Log.d(TAG, "page ${direction.index} added in front of cache")
            }
            is Direction.Forward -> {
                cache.addLast(RenderPageData(pageToRender, null))
                if (cache.size > CACHE_SIZE) clearCachedPage(cache.removeFirst())
                Log.d(TAG, "page ${direction.index} added in the end of cache")
            }
        }

        cache.forEach { Log.d(TAG, "cache state - ${it.pageInfo.pageIndexOfTotal}") }
        renderingHandler.sendEmptyMessage(pageToRender.pageIndexOfTotal)
    }

    private fun clearCachedPage(cachedPage: RenderPageData) {
        cachedPage.bitmap?.recycle()
    }


    private fun renderPage(
        currentPage: PdfRenderer.Page,
        renderType: RenderType,
        transformMatrix: Matrix? = null
    ): Bitmap {
        val pageSize = calculatePageSize(currentPage, renderType.scaleFactor)
        val bitmap = Bitmap.createBitmap(pageSize.first, pageSize.second, Bitmap.Config.ARGB_8888)
        currentPage.render(bitmap, null, transformMatrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        currentPage.close()
        return bitmap
    }

    private fun calculatePageSize(currentPage: Any, qualityFactor: Float): Pair<Int, Int> {
        val (pageWidth, pageHeight) = getPageDimensions(currentPage)

        val screenHeight = screenHeight
        val screenWidth = screenWidth
        val scaleToFitHeight = screenHeight.toFloat() / pageHeight
        val scaleToFitWidth = screenWidth.toFloat() / pageWidth

        val scale = min(scaleToFitWidth, scaleToFitHeight)
        val newWidth = ((scale * qualityFactor) * pageWidth).roundToInt()
        val newHeight = ((scale * qualityFactor) * pageHeight).roundToInt()

        return Pair(newWidth, newHeight)
    }

    private fun getPageDimensions(page: Any): Pair<Int, Int> {
        var pageWidth = 0
        var pageHeight = 0
        if (page is PdfRenderer.Page) {
            pageWidth = page.width
            pageHeight = page.height
        } else if (page is Bitmap) {
            pageWidth = page.width
            pageHeight = page.height
        }
        return Pair(pageWidth, pageHeight)
    }

    enum class RenderType(val scaleFactor: Float) {
        NORMAL(1F),
        PREVIEW(0.2F),
        PREVIEW_LOW(0.1F)
    }

}

fun <T> applySchedulersObservable(): (Observable<T>) -> Observable<T> {
    return { o: Observable<T> ->
        o.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }
}

fun <T> applySchedulersSingle(): (Single<T>) -> Single<T> {
    return { o: Single<T> ->
        o.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }
}

fun <T> Observable<T>.subscribeAndDispose(
    onNext: (T) -> Unit = {},
    onError: (t: Throwable) -> Unit = { t -> Log.e("Error", t.localizedMessage ?: t.toString()) },
    onComplete: () -> Unit = {}
): Disposable {
    var d: Disposable? = null
    d = this.doFinally {
        d?.dispose()
    }
        .subscribe(onNext, onError, onComplete)
    return d
}

fun <T> Single<T>.subscribeAndDispose(
    onNext: (T) -> Unit = {},
    onError: (t: Throwable) -> Unit = { t -> Log.e("Error", t.localizedMessage ?: t.toString()) }
): Disposable {
    var d: Disposable? = null
    d = this.doFinally { d?.dispose() }
        .subscribe(onNext, onError)
    return d
}