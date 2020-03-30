package com.vibro.testviewpager2

import org.koin.dsl.module

val myModule = module {

    single { (renderer: SnRenderer) -> SnRendererWrapper(renderer) }
    single { get<SnRendererWrapper>().pdfPageProvider }
}