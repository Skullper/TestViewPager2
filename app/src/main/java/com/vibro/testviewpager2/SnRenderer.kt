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
import kotlin.math.abs
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

// TODO(13.04.2020) Refactor this!!!
abstract class RotateDirection(angle: Float) {

    protected var angle: Float = abs(angle)
        set(value) {
            field = if (value >= 360F) 0F else value
        }
    abstract fun get(): Float
    abstract fun rotateAndForget(angle: Float): RotateDirection

    fun rotate(angle: Float): RotateDirection {
        this.angle += abs(angle)
        return this
    }

    class Clockwise(angle: Float = 0F): RotateDirection(angle) {
        override fun get(): Float = angle
        override fun rotateAndForget(angle: Float): RotateDirection {
            val newAngle = this.angle + angle
            return Clockwise(if (newAngle >= 360F) 0F else newAngle)
        }
    }

    class CounterClockwise(angle: Float = 0F) : RotateDirection(angle) {
        override fun get(): Float = -angle
        override fun rotateAndForget(angle: Float): RotateDirection {
            val newAngle = this.angle + abs(angle)
            return CounterClockwise(if (newAngle >= 360F) 0F else newAngle)
        }
    }
}

class SnRenderer(private val pageTransformer: PageTransformer) {

    private val TAG = this.javaClass.simpleName

    private lateinit var pdfRenderer: PdfRenderer
    private lateinit var currentFile: PdfFileData

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
            val pageAttributes = setAttributesIfNeeded(currentPage, pageInfoToRender)

            val rotatedPageAttributes = pageTransformer.rotatePage(pageAttributes)
            val bitmap = renderPage(currentPage, rotatedPageAttributes)

            val pageInfo = pageInfoToRender.pageInfo.copy(pageAttributes = rotatedPageAttributes)
            val renderedData = RenderedPageData(pageInfo, pageInfoToRender.quality, bitmap, RenderingStatus.Complete)
            renderingResultPublisher.onNext(renderedData)
        }
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

    fun waitRender(id: Long, quality: Quality): Observable<RenderedPageData> {
        return renderingResultPublisher
            .filter { it.pageInfo.id == id }
            .filter { it.quality == quality }
            .take(1)
    }

    fun getCurrentFile() = currentFile

    private fun initPages(files: List<File>): List<PageInfo> {
        val pages = mutableListOf<PageInfo>()
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

    private fun setAttributesIfNeeded(currentPage: PdfRenderer.Page, pageInfoToRender: PageToRender): PageAttributes? {
        val attrs = pageInfoToRender.pageInfo.pageAttributes

        val viewSize = calculatePageSize(currentPage, pageInfoToRender.quality.scaleFactor)
        val pageSize = Pair(currentPage.width, currentPage.height)
        val matrix = Matrix().apply {
            val initialScale = viewSize.first.toFloat() / currentPage.width.toFloat()
            postScale(initialScale, initialScale)
        }
        return if (attrs == null || attrs !is FrameworkPageAttributes) {
            FrameworkPageAttributes(viewSize, pageSize, matrix = matrix)
        } else if (attrs.isNotRenderedYet()) {
            attrs.copy(viewSize, pageSize, matrix = matrix)
        } else {
            attrs
        }
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