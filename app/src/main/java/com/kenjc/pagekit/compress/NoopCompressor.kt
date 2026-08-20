package com.kenjc.pagekit.compress

import com.kenjc.pagekit.api.dto.CompressedPage
import com.kenjc.pagekit.api.dto.FetchRequest

/** V1 占位实现：不做语义压缩，直接返回规则装配的结构化结果 */
class NoopCompressor : Compressor {
    override val name: String = "noop"
    override suspend fun compress(request: FetchRequest, context: PageContext): CompressedPage =
        context.structured
}
