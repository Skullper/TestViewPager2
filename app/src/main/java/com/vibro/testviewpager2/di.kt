package com.vibro.testviewpager2

import com.vibro.testviewpager2.utils.PageViewSizeCalculator
import org.koin.dsl.module

val myModule = module {

    factory { PageTransformer() }
    single { SnRenderer(pageTransformer = get(), pageViewSizeCalculator = get()) }
    single { PdfRenderingEngine(context = get(), snRenderer = get(), pageViewSizeCalculator = get()) }
    factory { PageViewSizeCalculator() }
}