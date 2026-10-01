package io.nightfish.lightnovelreader.api.book

import kotlinx.serialization.Serializable

/** 关联书籍的查询类型。 */
@Serializable
enum class RelatedBookKind {
    /** 按作者查询。 */
    AUTHOR,
    /** 按标签查询。 */
    TAG
}

/**
 * 由书籍详情发起的关联书籍查询。
 *
 * @property bookId 发起查询的书籍在当前书源中的 ID
 * @property kind 关联查询的类型
 * @property value 书源提供的原始作者或标签值，不经过显示文本转换
 */
@Serializable
data class RelatedBooksRequest(
    val bookId: String,
    val kind: RelatedBookKind,
    val value: String,
)
