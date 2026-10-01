package io.nightfish.lightnovelreader.api.web

import io.nightfish.lightnovelreader.api.book.RelatedBookKind
import io.nightfish.lightnovelreader.api.book.RelatedBooksRequest
import io.nightfish.lightnovelreader.api.web.explore.ExploreExpandedPageDataSource

/**
 * 书源可选实现的关联书籍查询能力。
 * 未实现此接口的书源继续使用原有标签跳转接口，不支持作者关联入口。
 */
interface RelatedBooksDataSource {
    /** 支持的关联查询类型；空集合表示不支持关联查询。 */
    val supportedRelatedBookKinds: Set<RelatedBookKind>

    /**
     * 为已支持的关联请求创建独立页面数据源，不执行网络请求或导航。
     * 网络请求在结果流被收集时执行，分页与筛选状态不能跨页面共享。
     * 返回可重新收集的冷流，每轮从第一页开始；取消必须停止当前网络与分页等待。
     *
     * @param request 使用书源原始作者或标签值的关联查询
     * @return 每次调用创建的全新页面会话
     */
    fun createRelatedBooksPage(request: RelatedBooksRequest): ExploreExpandedPageDataSource
}
