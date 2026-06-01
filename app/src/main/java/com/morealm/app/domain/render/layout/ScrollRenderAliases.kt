package com.morealm.app.domain.render.layout

// 过渡兼容别名 —— 核心排版类型已迁至 com.morealm.epub.render 模块。
// 旧消费方的 import 经本文件别名解析，保持源码不动即可编译。
// 后续清理阶段：把消费方 import 直接指向 com.morealm.epub.render 后删除本文件。

typealias ScrollLayoutEngine = com.morealm.epub.render.ScrollLayoutEngine
typealias ScrollChapterLayout = com.morealm.epub.render.ScrollChapterLayout
typealias ScrollPage = com.morealm.epub.render.ScrollPage
typealias ScrollLine = com.morealm.epub.render.ScrollLine
typealias ScrollColumn = com.morealm.epub.render.ScrollColumn
typealias ScrollLineCell = com.morealm.epub.render.ScrollLineCell
typealias Atom = com.morealm.epub.render.Atom
typealias TextRun = com.morealm.epub.render.TextRun
typealias InlineImage = com.morealm.epub.render.InlineImage
typealias ScrollAtomBridge = com.morealm.epub.render.ScrollAtomBridge
typealias ScrollHitResult = com.morealm.epub.render.ScrollHitResult
