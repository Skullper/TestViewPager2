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
data class PageInfo(
    val pdfFileData: PdfFileData,
    val pageIndex: Int,
    val pageIndexOfTotal: Int,
    val pageAttributes: PageAttributes)

data class PdfFileData(val file: File, val pageCount: Int)

data class PageToRender(val pageInfo: PageInfo, val quality: SnRenderer.Quality = SnRenderer.Quality.Normal)

data class RenderedPageData(val pageInfo: PageInfo,
                            val quality: SnRenderer.Quality?,
                            val bitmap: Bitmap? = null,
                            val status:RenderingStatus)

sealed class RenderingStatus {
    object Wait:RenderingStatus()
    object Rendering:RenderingStatus()
    object Complete:RenderingStatus()
}

sealed class RotateDirection(open val angle: Float) {
    data class Clockwise(override val angle: Float = 90F) : RotateDirection(angle)
    data class Counterclockwise(override val angle: Float = -90F) : RotateDirection(angle)
}

class SnRenderer(private val pageTransformer: PageTransformer) {

    private val TAG = this.javaClass.simpleName

    private lateinit var pdfRenderer: PdfRenderer
    private lateinit var currentFile: PdfFileData

    private val pages: MutableList<PageInfo> = mutableListOf()

    private val screenHeight = Resources.getSystem().displayMetrics.heightPixels
    private val screenWidth = Resources.getSystem().displayMetrics.widthPixels

    private val renderingResultPublisher = PublishSubject.create<RenderedPageData>()
    private val renderingThread = HandlerThread("pdf_rendering").apply { this.start() }
    private val renderingHandler = object : Handler(renderingThread.looper) {
        override fun handleMessage(msg: Message) {
            val pageInfoToRender = msg.obj as PageToRender
            val pdfFileData = pageInfoToRender.pageInfo.pdfFileData
            if (pdfFileData.file != currentFile.file) {
                pdfRenderer.close()
                initMainRenderer(pdfFileData)
            }
            val currentPage = pdfRenderer.openPage(pageInfoToRender.pageInfo.pageIndex)
            val attributes = pageInfoToRender.pageInfo.pageAttributes
            val pageAttributes = if (attributes.viewSize.first == 0) setPageAttributes(currentPage, pageInfoToRender) else attributes
            val bitmap = renderPage(currentPage, pageAttributes)

            val renderedData = RenderedPageData(pageInfoToRender.pageInfo, pageInfoToRender.quality, bitmap, RenderingStatus.Complete)
            renderingResultPublisher.onNext(renderedData)
        }
    }

    fun updatePage(pageInfo: PageInfo) {
        pages[pageInfo.pageIndexOfTotal] = pageInfo
    }

    fun open(files: List<File>): List<PageInfo> {
        return initPages(files)
    }

    fun close() {
        renderingThread.quitSafely()
    }

    fun getPages(): List<PageInfo> = pages

    fun getCurrentFile(): PdfFileData {
        return currentFile
    }

    fun renderPage(index: Int, quality: Quality): Observable<RenderedPageData> {
        val pageInfo = pages[index]
        val p = PageToRender(pageInfo, quality)
        val message = Message().apply { obj = p }
        renderingHandler.sendMessage(message)
        return waitRender(pageInfo.pageIndexOfTotal, quality)
    }

    fun waitRender(position: Int, quality: Quality): Observable<RenderedPageData> {
        return renderingResultPublisher
            .filter { it.pageInfo.pageIndexOfTotal == position }
            .filter { it.quality == quality }
    }

    fun rotatePage(index: Int): PageInfo {
        val originalPage = pages[index]
        val result = pageTransformer.rotatePage(originalPage.pageAttributes as FrameworkPageAttributes)
        val updatedPage = originalPage.copy(pageAttributes = result)
        updatePage(updatedPage)
        return updatedPage
    }

    fun rotateAllPages(direction: RotateDirection): Observable<Unit> {
        // TODO(08.04.2020)
        return Observable.fromIterable(pages)
            .map { pageInfo ->
                val attributes = pageInfo.pageAttributes
                val currentDirection = RotateDirection.Clockwise(attributes.rotateDirection.angle + direction.angle)

//                    pageInfo.copy(pageAttributes = DefaultPageAttributes(rotateDirection = currentDirection))
            }
//                .map { pageInfo -> pages[pageInfo.pageIndexOfTotal] = pageInfo }
    }

    private fun initPages(files: List<File>): List<PageInfo> {
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
                val index = indexOfTotal++
                val info = PageInfo(fileData, it, index, FrameworkPageAttributes(Pair(0, 0), Pair(0, 0), RotateDirection.Clockwise(0F), Matrix()))
                pages.add(info)
            }
            if (!::currentFile.isInitialized && !::pdfRenderer.isInitialized) initMainRenderer(fileData)
        }
        return pages
    }

    private fun initMainRenderer(fileData: PdfFileData) {
        currentFile = fileData
        val parcelFileDescriptor =
            ParcelFileDescriptor.open(currentFile.file, ParcelFileDescriptor.MODE_READ_ONLY)
        pdfRenderer = PdfRenderer(parcelFileDescriptor)
    }

    private fun setPageAttributes(currentPage: PdfRenderer.Page, pageInfoToRender: PageToRender): PageAttributes? {
        val viewSize = calculatePageSize(currentPage, pageInfoToRender.quality.scaleFactor)
        val pageIndex = pages.indexOf(pageInfoToRender.pageInfo)
        val matrix = Matrix().apply {
            val initialScale = viewSize.first.toFloat() / currentPage.width.toFloat()
            postScale(initialScale, initialScale)
        }
        val direction = pageInfoToRender.pageInfo.pageAttributes.rotateDirection
        val newAttr = if (direction.angle == 0F) {
            FrameworkPageAttributes(viewSize, Pair(currentPage.width, currentPage.height), matrix = matrix)
        } else {
            pageTransformer.rotatePage(FrameworkPageAttributes(viewSize, Pair(currentPage.width, currentPage.height), direction, matrix))
        }
        pages[pageIndex] = pages[pageIndex].copy(pageAttributes = newAttr)
        return newAttr
    }

    private fun renderPage(currentPage: PdfRenderer.Page, pageAttributes: PageAttributes? = null): Bitmap {
        if (pageAttributes is NativePageAttributes) throw IllegalArgumentException("You need to use FrameworkPageAttributes here")
        if (pageAttributes == null) throw NullPointerException("Page attributes cannot be null during rendering")

        val (viewWidth, viewHeight) = pageAttributes.viewSize
        val (pageWidth, pageHeight) = pageAttributes.pageSize
        val matrix = (pageAttributes as FrameworkPageAttributes).matrix
        val needToRotate = matrix.isPortrait()

        val bitmap: Bitmap = if (needToRotate) {
            Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
        } else {
            val scale = viewWidth.toFloat() / pageHeight.toFloat()
            Bitmap.createBitmap(viewWidth, (pageWidth.toFloat() * scale).toInt(), Bitmap.Config.ARGB_8888)
        }
        currentPage.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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

    sealed class Quality(val scaleFactor: Float) {
        object Normal : Quality(1F)
        object Preview : Quality(0.2F)
        object PreviewLow : Quality(0.1F)
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