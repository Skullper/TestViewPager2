package com.vibro.testviewpager2

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.*
import android.util.Log
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
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
data class PageInfo(
    val pdfFileData: PdfFileData?,
    val pageIndex: Int,
    val pageIndexOfTotal: Int,
    val pageAttributes: PageAttributes? = null,
    val pageChangesData: PageChangesData? = null,
    val id:Long = UUID.randomUUID().mostSignificantBits)

data class PageChangesData(val storedPage: Uri)

data class PdfFileData(val file: File, val pageCount: Int)

data class PageToRender(val pageInfo: PageInfo, val quality: SnRenderer.Quality = SnRenderer.Quality.Normal)

data class RenderedPageData(
    val pageInfo: PageInfo,
    val quality: SnRenderer.Quality?,
    val bitmap: Bitmap? = null,
    val status: RenderingStatus
)

sealed class RenderingStatus {
    object Wait : RenderingStatus()
    object Rendering : RenderingStatus()
    object Complete : RenderingStatus()
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
            if (pdfFileData?.file != null && pdfFileData.file != currentFile.file) {
                pdfRenderer.close()
                initMainRenderer(pdfFileData)
            }
            val currentPage = pdfRenderer.openPage(pageInfoToRender.pageInfo.pageIndex)
            val pageAttributes = pageInfoToRender.pageInfo.pageAttributes
            val bitmap = renderPage(
                currentPage,
                setPageAttributes(currentPage, pageInfoToRender, pageAttributes)
            )

            val pageInfo = pageInfoToRender.pageInfo.copy(pageAttributes = pageAttributes)
            val renderedData = RenderedPageData(pageInfo, pageInfoToRender.quality, bitmap, RenderingStatus.Complete)
            renderingResultPublisher.onNext(renderedData)
        }
    }

    fun updatePage(pageInfo: PageInfo) {
        val i = pages.indexOfFirst { it.id == pageInfo.id }
        pages[i]
    }

    fun open(files: List<File>): List<PageInfo> {
        return initPages(files)
    }

    fun close() {
        renderingThread.quitSafely()
    }

    fun renderPage(pageInfo: PageInfo, quality: Quality): Observable<RenderedPageData> {
        val p = PageToRender(pageInfo, quality)
        val message = Message().apply { obj = p }
        renderingHandler.sendMessage(message)
        return waitRender(pageInfo.id, quality)
    }

    fun waitRender(id:Long, quality: Quality): Observable<RenderedPageData> {
        return renderingResultPublisher
            .filter { it.pageInfo.id == id }
            .filter { it.quality == quality }
            .take(1)
    }

    fun rotatePage(index: Int, direction: RotateDirection = RotateDirection.Clockwise()): PageInfo {
        val originalPage = pages[index]
        val pageAttributes = originalPage.pageAttributes as? FrameworkPageAttributes
        val result = pageTransformer.rotatePage(pageAttributes?.copy(rotateDirection = direction))
        val updatedPage = originalPage.copy(pageAttributes = result)
        updatePage(updatedPage)
        return updatedPage
    }

    fun getCurrentFile() = currentFile

    fun rotateAllPages(direction: RotateDirection): Observable<List<Unit>> {
        // TODO(08.04.2020)
        return Observable.fromIterable(pages)
            .map { pageInfo ->
                var attributes = pageInfo.pageAttributes as? FrameworkPageAttributes
                attributes = attributes?.copy(rotateDirection = direction)
                    ?: FrameworkPageAttributes(Pair(0, 0), Pair(0, 0), direction, Matrix())
                if (pageInfo.pageAttributes != null && pageInfo.pageAttributes.viewSize.first != 0)
                    pageInfo.copy(pageAttributes = pageTransformer.rotatePage(attributes))
                else
                    pageInfo.copy(pageAttributes = attributes)
            }
            .map { pageInfo ->
                pages[pageInfo.pageIndexOfTotal] = pageInfo
            }
            .toList()
            .toObservable()
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
                val info = PageInfo(fileData, it, index)
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
        val pageInfo = pageInfoToRender.pageInfo
        val matrix = Matrix().apply {
            val initialScale = viewSize.first.toFloat() / currentPage.width.toFloat()
            postScale(initialScale, initialScale)
        }
        val direction = pageInfo.pageAttributes?.rotateDirection
        val newAttr = FrameworkPageAttributes(viewSize, Pair(currentPage.width, currentPage.height), matrix = matrix)
        updatePage(pageInfo.copy(pageAttributes = newAttr))
        return newAttr
    }

    private fun setPageAttributes(
        currentPage: PdfRenderer.Page,
        pageInfoToRender: PageToRender,
        attrs: PageAttributes?
    ): PageAttributes? {
        val pageIndex = pages.indexOf(pageInfoToRender.pageInfo)
        val newAttr = if (attrs == null || attrs !is FrameworkPageAttributes) {
            val viewSize = calculatePageSize(currentPage, pageInfoToRender.quality.scaleFactor)
            val matrix = Matrix().apply {
                val initialScale = viewSize.first.toFloat() / currentPage.width.toFloat()
                postScale(initialScale, initialScale)
            }
            val attributes = FrameworkPageAttributes(
                viewSize,
                Pair(currentPage.width, currentPage.height),
                RotateDirection.Clockwise(0F),
                matrix = matrix
            )
            pageTransformer.rotatePage(attributes)
        } else if (attrs.viewSize.first == 0) {
            val viewSize = calculatePageSize(currentPage, pageInfoToRender.quality.scaleFactor)
            val matrix = Matrix().apply {
                val initialScale = viewSize.first.toFloat() / currentPage.width.toFloat()
                postScale(initialScale, initialScale)
            }
            val attributes = FrameworkPageAttributes(
                viewSize,
                Pair(currentPage.width, currentPage.height),
                attrs.rotateDirection,
                matrix
            )
            pageTransformer.rotatePage(attributes)
        } else {
            attrs
        }
        pages[pageIndex] = pages[pageIndex].copy(pageAttributes = newAttr)
        return newAttr
    }

    private fun renderPage(
        currentPage: PdfRenderer.Page,
        pageAttributes: PageAttributes? = null
    ): Bitmap {
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