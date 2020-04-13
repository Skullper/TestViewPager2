package com.vibro.testviewpager2

import org.koin.dsl.module

val myModule = module {

    factory { PageTransformer() }
    single { SnRenderer(pageTransformer = get()) }
    single { PdfRenderingEngine(get(), get()) }
}