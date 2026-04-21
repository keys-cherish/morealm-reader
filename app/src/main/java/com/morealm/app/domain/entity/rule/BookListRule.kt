package com.morealm.app.domain.entity.rule

/**
 * 书籍列表规则接口
 */
interface BookListRule {
    var bookList: String?
    var name: String?
    var author: String?
    var intro: String?
    var kind: String?
    var lastChapter: String?
    var updateTime: String?
    var bookUrl: String?
    var coverUrl: String?
    var wordCount: String?
}
