package com.vibro.testviewpager2

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.util.Log
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.parcel.Parcelize
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Contains information about pdf page
 *
 * @param file which contains current page
 * @param filePageNumber concrete page number in [file]
 * @param renderedPageNumber index of the page displayed on the screen. This page number refers to the index in the list of pages of all documents
 * which are rendering at that point
 */
@Parcelize
data class PageInfo(val fileData: RenderedFileData, val filePageNumber: Int, val renderedPageNumber: Int, val quality: SnRenderer.RenderType = SnRenderer.RenderType.NORMAL) :
    Parcelable

@Parcelize
data class RenderedFileData(val file: File, val pageCount: Int): Parcelable

data class RenderData(val pageInfo: PageInfo, val bitmap: Bitmap)

private const val CACHE_SIZE = 9

class SnRenderer(private val files: List<File>) {

    private lateinit var renderer: PdfRenderer
    private val pages: MutableList<PageInfo> = mutableListOf()
    var wholePagesCount: Int = 0
    private val sparseArray: ArrayList<RenderData> = ArrayList(CACHE_SIZE)
    private lateinit var currentFile: RenderedFileData
    private val filesForRender: ArrayList<RenderedFileData> = arrayListOf()

    private val screenHeight = Resources.getSystem().displayMetrics.heightPixels
    private val screenWidth = Resources.getSystem().displayMetrics.widthPixels

    fun getPages(): List<PageInfo> {
        files.forEach { file ->
            val parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val pdfRenderer = PdfRenderer(parcelFileDescriptor)
            val filePagesCount = pdfRenderer.pageCount
            val fileData = RenderedFileData(file, filePagesCount)
            filesForRender.add(fileData)
            pdfRenderer.close()
            val filePages = (0 until filePagesCount)
            pages.addAll(filePages.map {
                val info = PageInfo(fileData, it, wholePagesCount)
                wholePagesCount += 1
                info
            })
        }
        return pages
    }

    fun startRendering(): Single<List<RenderData>> {
        return if (currentFile.pageCount < CACHE_SIZE) {
            Observable.fromIterable((1 until currentFile.pageCount))
        } else {
            Observable.fromIterable((1 until CACHE_SIZE))
        }
                .flatMap { pageNumber -> renderPage(PageInfo(currentFile, pageNumber, pageNumber)) }
                .toList()
    }

    private var prevIndex: Int = 0

    fun getPage(pageInfo: PageInfo): Observable<RenderData> {
        return if (!::renderer.isInitialized) {
            currentFile = filesForRender[0]
            val parcelFileDescriptor = ParcelFileDescriptor.open(currentFile.file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(parcelFileDescriptor)
            startRendering().compose(applySchedulersSingle()).subscribeAndDispose()
            renderPage(pageInfo)
//        } else if (sparseArray.get(pageInfo.filePageNumber) == null) {
//            renderPage(pageInfo)
        } else {
            val currentPosition = sparseArray.indexOfFirst { pageInfo.renderedPageNumber == it.pageInfo.renderedPageNumber }
            if (pageInfo.renderedPageNumber > prevIndex) { //forward
                if (currentPosition > 5) {
                    val nextPagePosition = pages.indexOf(pageInfo) + 3
                    renderPage(pages[nextPagePosition]).compose(applySchedulersObservable()).subscribeAndDispose({
//                        sparseArray[0].bitmap.recycle()
                        sparseArray.removeAt(0)
                    })
                }
                prevIndex = pageInfo.renderedPageNumber
                Observable.just(sparseArray[if (currentPosition < 0) 0 else currentPosition])
            } else { //backwards
                if (currentPosition < 5) {
                    val nextPagePosition = pages.indexOf(pageInfo) - 3
                    if (nextPagePosition >= 0) {
                        renderPage(pages[nextPagePosition], false).compose(applySchedulersObservable()).subscribeAndDispose({
                            val lastIndex = sparseArray.size - 1
//                            sparseArray[lastIndex].bitmap.recycle()
                            sparseArray.removeAt(lastIndex)
                        })
                    }
                }
                prevIndex = pageInfo.renderedPageNumber
                Observable.just(sparseArray[if (currentPosition < 0) 0 else currentPosition])
            }
        }
    }

    fun renderPagePreview(index: Int) {
        // TODO(26.03.2020) Add render page preview
    }

    private fun renderPage(page: PageInfo, tail: Boolean = true): Observable<RenderData> {
        return Observable.just(page)
                .map {
                    if (page.fileData.file != currentFile.file) {
                        renderer.close()
                        currentFile = page.fileData
                        val parcelFileDescriptor = ParcelFileDescriptor.open(currentFile.file, ParcelFileDescriptor.MODE_READ_ONLY)
                        renderer = PdfRenderer(parcelFileDescriptor)
                    }
                    it
                }
                .map { renderPage(renderer.openPage(it.filePageNumber), it.quality) }
                .map { bitmap ->
                    val data = RenderData(page, bitmap)
                    if (tail) sparseArray.add(data) else sparseArray.add(0, data)
                    data
                }
    }

    private fun renderPage(currentPage: PdfRenderer.Page, renderType: RenderType, transformMatrix: Matrix? = null): Bitmap {
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

    data class PageForRender(val index: Int, val renderType: RenderType)

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
    onError: (t: Throwable) -> Unit = { t -> Log.e("Error", t.localizedMessage?:t.toString()) },
    onComplete: () -> Unit = {}): Disposable {
    var d: Disposable? = null
    d = this.doFinally {
        d?.dispose()
    }
        .subscribe(onNext, onError, onComplete)
    return d
}

fun <T> Single<T>.subscribeAndDispose(
    onNext: (T) -> Unit = {},
    onError: (t: Throwable) -> Unit = { t -> Log.e("Error", t.localizedMessage?:t.toString()) }): Disposable {
    var d: Disposable? = null
    d = this.doFinally { d?.dispose() }
        .subscribe(onNext, onError)
    return d
}