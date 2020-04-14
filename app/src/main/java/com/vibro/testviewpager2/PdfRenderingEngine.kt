package com.vibro.testviewpager2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import com.bumptech.glide.Glide
import com.vibro.testviewpager2.utils.RotatePageTransformer
import io.reactivex.Observable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Exception
import java.lang.IllegalArgumentException
import kotlin.collections.ArrayList

data class PageToSave(val index:Int, val bitmap: Bitmap, val width:Int, val height:Int)

class PdfRenderingEngine(
    private val context: Context,
    private val snRenderer: SnRenderer
) : RenderPagesProvider {

    private val TAG = this.javaClass.simpleName

    private val cache = PagesCache(this)
    private val originalPages: MutableList<PageInfo> = mutableListOf()
    private val currentPages: MutableList<PageInfo> by lazy {
        hasChanges = true
        ArrayList<PageInfo>(originalPages)
    }

    private var hasChanges = false
    private var multifiles = false

    fun open(files: List<File>): List<PageInfo> {
        multifiles = files.size > 1
        return snRenderer.open(files).apply { originalPages.addAll(this) }
    }

    fun close() {
        snRenderer.close()
        cache.clearCache()
    }

    @Throws(IllegalArgumentException::class, OperationNotSupported::class, NoChangesFound::class, IOException::class)
    fun save(): Observable<String> {
        if (multifiles) return Observable.error(OperationNotSupported())
        if (!hasChanges) return Observable.error(NoChangesFound())

        var counter = 0
        val pdfDocument = PdfDocument()

        fun providePageToSave(page: PageInfo): Observable<PageToSave> {
            return if (page.pageChangesData == null) {
                snRenderer.renderPage(page, SnRenderer.Quality.Normal)
                    .map {
                        val isPortrait = it.pageInfo.pageAttributes?.isPortrait()?:true
                        val pageSize = it.pageInfo.pageAttributes?.pageSize
                        if (it.bitmap == null || pageSize == null) throw IllegalArgumentException()
                        val width = if (isPortrait) pageSize.first else pageSize.second
                        val height = if (isPortrait) pageSize.second else pageSize.first
                        PageToSave(counter++, it.bitmap, width,height)
                    }
            } else {
                Observable.fromCallable {
                    val transformer = RotatePageTransformer(page.pageAttributes as? FrameworkPageAttributes)
                    val bitmap = bitmapFromUri(page.pageChangesData.storedPage, transformer) ?: throw IllegalArgumentException()
                    PageToSave(counter++, bitmap, bitmap.width, bitmap.height)
                }
            }
        }

        fun writePageToPdfFile(pageToSave: PageToSave, pdfDocument: PdfDocument) {
            val pageInfo = PdfDocument.PageInfo.Builder(pageToSave.width, pageToSave.height, pageToSave.index).create()
            val page = pdfDocument.startPage(pageInfo)
            page.canvas.drawBitmap(pageToSave.bitmap, null, Rect(0, 0, pageToSave.width, pageToSave.height), Paint())
            pdfDocument.finishPage(page)
        }

        fun writeOnDisk(pdfDocument: PdfDocument): String {
            val file = snRenderer.getCurrentFile().file
            val path = file.absolutePath
            file.delete()
            val outStream = FileOutputStream(path)
            pdfDocument.writeTo(outStream)
            try {
                outStream.close()
            } catch (e: Exception) {
                //do nothing
            }
            return path
        }

        return Observable.fromIterable(currentPages)
            .flatMap { page -> providePageToSave(page) }
            .map { pageToSave -> writePageToPdfFile(pageToSave, pdfDocument) }
            .toList()
            .toObservable()
            .map { writeOnDisk(pdfDocument) }
            .doFinally { pdfDocument.close() }
    }

    override fun getPages() = if (!hasChanges) originalPages else currentPages

    override fun providePage(index: Int, quality: SnRenderer.Quality): Observable<RenderedPageData> {
        if (!pageIsNewOrHasChanges(index)) {
            val pageInfo = getPages()[index]
            return snRenderer.renderPage(pageInfo, quality)
                .doOnNext { renderedPageData -> updatePage(renderedPageData.pageInfo) }
        } else {
            return Observable.fromCallable {
                val currentPage = currentPages[index]
                val transformer = RotatePageTransformer(currentPage.pageAttributes as? FrameworkPageAttributes)
                val b = bitmapFromUri(currentPage.pageChangesData?.storedPage, transformer)
                RenderedPageData(currentPage, quality, b, RenderingStatus.Complete)
            }
        }
    }

    private fun updatePage(page: PageInfo) {
        val pageIndex = getPages().indexOfFirst { it.id == page.id }
        getPages()[pageIndex] = page
    }

    private fun bitmapFromUri(uri: Uri?, transformer: RotatePageTransformer): Bitmap? {
        return Glide.with(context).asBitmap().load(uri)
            .transform(transformer)
            .submit()
            .get()
    }

    fun addPage(uri: Uri): Observable<Boolean> {
        return Observable.fromCallable {
            val pageIndex = currentPages.lastIndex + 1
            val attributes = FrameworkPageAttributes(Pair(0, 0), Pair(0, 0), RotateDirection.Clockwise(), Matrix())
            val pageInfo = PageInfo(null, -1, pageIndex, attributes, PageChangesData(uri))
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

    fun updatePageUri(index: Int, uri: Uri): Observable<RenderedPageData> {
        return Observable.fromCallable {
            val page = getPages()[index]
            val pageChangesData = if (page.pageChangesData == null) {
                PageChangesData(uri)
            } else {
                page.pageChangesData.copy(storedPage = uri)
            }
            val updatedPage = page.copy(pageChangesData = pageChangesData)
            updatePage(updatedPage)
            val transformer = RotatePageTransformer(updatedPage.pageAttributes)
            val b = Glide.with(context).asBitmap().load(updatedPage.pageChangesData?.storedPage).transform(transformer).submit().get()
            RenderedPageData(updatedPage, SnRenderer.Quality.Normal, b, RenderingStatus.Complete)
        }
            .map { renderedData -> cache.updatePageInCache(renderedData) }
            .flatMap { getPage(index) }
    }

    fun updatePages(newOrderOfElements:List<Long>): Observable<Unit> {
        return Observable.fromCallable {
            val pages = getPages()
            if (newOrderOfElements.size > pages.size) Observable.error<Unit>(NewOrderCapacityMoreThanInitial())
            if (newOrderOfElements.isNotEmpty()) {
                currentPages.clear()
                newOrderOfElements.forEach { id ->
                    val page = pages.firstOrNull { it.id == id }
                    page?.let { currentPages.add(page) }?: throw PageNotFound()
                }
                updateIndexes()
                cache.clearCache()
            }
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
            else -> providePage(index, quality)
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

    /*--------------------------ROTATE PAGE--------------------------------*/
    fun rotatePage(index: Int, direction: RotateDirection = RotateDirection.Clockwise()): Observable<RenderedPageData> {
        return if (!pageIsNewOrHasChanges(index)) {
            rotatePageFromRenderer(index, direction)
        } else {
            rotatePageFromUri(index, direction)
        }
    }

    private fun rotatePageFromUri(index: Int, direction: RotateDirection = RotateDirection.Clockwise()): Observable<RenderedPageData> {
        return Observable.fromCallable {
            val currentPage = currentPages[index]
            val attributes = currentPage.pageAttributes as? FrameworkPageAttributes
            val oldAngle = attributes?.rotateDirection?.get() ?: 0F
            val rotatedPage = currentPage.copy(pageAttributes = attributes?.copy(rotateDirection = direction.rotate(oldAngle)))
            val transformer = RotatePageTransformer(rotatedPage.pageAttributes)
            val b = Glide.with(context).asBitmap().load(rotatedPage.pageChangesData?.storedPage).transform(transformer).submit().get()
            RenderedPageData(rotatedPage, SnRenderer.Quality.Normal, b, RenderingStatus.Complete)
        }
    }

    private fun rotatePageFromRenderer(index: Int, direction: RotateDirection = RotateDirection.Clockwise()): Observable<RenderedPageData> {
        return Observable.fromCallable {
            val pageForRotation = getPages()[index]
            val oldAngle = pageForRotation.pageAttributes?.rotateDirection?.get() ?: 0F
            val rotatedPage = rotatePage(pageForRotation, direction.rotate(oldAngle))
            val renderedPageData = RenderedPageData(rotatedPage, SnRenderer.Quality.Normal, null, RenderingStatus.Wait)
            cache.updatePageInCache(renderedPageData)
        }.flatMap { getPage(index) }
    }

    fun rotateAllPages(index: Int, direction: RotateDirection): Observable<RenderedPageData> {
        return Observable.fromIterable(getPages())
                .map { page ->
                    val oldAngle = page.pageAttributes?.rotateDirection?.get() ?: 0F
                    rotatePage(page, direction.rotateAndForget(oldAngle))
                }
                .toList()
                .toObservable()
                .map { cache.clearCache() }
                .flatMap { getPage(index) }
    }

    private fun rotatePage(page: PageInfo, direction: RotateDirection = RotateDirection.Clockwise()): PageInfo {
        val attrs = if (page.pageAttributes == null || page.pageAttributes !is FrameworkPageAttributes) {
            FrameworkPageAttributes(Pair(0, 0), Pair(0, 0), direction, Matrix())
        } else {
            page.pageAttributes.copy(rotateDirection = direction)
        }
        val rotatedPage = page.copy(pageAttributes = attrs)
        updatePage(rotatedPage)
        return rotatedPage
    }
    /*--------------------------ROTATE PAGE--------------------------------*/

    /*--------------------------CROP PAGE--------------------------------*/
    fun cropPage(index: Int) {

    }
    /*--------------------------CROP PAGE--------------------------------*/

}

interface RenderPagesProvider {
    fun getPages(): List<PageInfo>
    fun providePage(index: Int, quality: SnRenderer.Quality): Observable<RenderedPageData>
}

class LastPageCannotBeRemoved : RuntimeException()
class OperationNotSupported : RuntimeException()
class NoChangesFound : RuntimeException()
class PageNotFound : RuntimeException()
class NewOrderCapacityMoreThanInitial : RuntimeException()