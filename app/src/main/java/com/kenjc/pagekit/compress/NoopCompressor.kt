package com.kenjc.pagekit.compress

import com.kenjc.pagekit.api.dto.CompressedPage
import com.kenjc.pagekit.api.dto.FetchRequest

/** 纯离线透传实现，适合嵌入方只需要 raw 模式时使用。 */
class NoopCompressor : Compressor {
    override val name: String = "noop"
    override suspend fun compress(request: FetchRequest, context: PageContext): CompressedPage =
        context.structured.copy(page_id = context.pageId.takeIf(String::isNotBlank))
}
