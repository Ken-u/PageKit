package com.kenjc.pagekit.session

import com.kenjc.pagekit.api.DefaultPageKitApi
import com.kenjc.pagekit.api.dto.ExpandedSection
import com.kenjc.pagekit.api.dto.FileDownloadResult
import com.kenjc.pagekit.api.dto.FetchRequest
import com.kenjc.pagekit.api.dto.FileInfo
import com.kenjc.pagekit.engine.SearchHit
import com.kenjc.pagekit.runtime.RuntimePageResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 纯 JVM 测试与兼容嵌入场景使用的单 Session gateway。 */
class SingleSessionGateway(
    private val api: DefaultPageKitApi,
) : PageKitSessionGateway {
    private val createdAt = System.currentTimeMillis()

    override suspend fun createProfile(profileId: String): ProfileInfo =
        error("isolated profiles require Android profile workers")

    override suspend fun listProfiles() = listOf(ProfileInfo(DEFAULT_PROFILE_ID, 0, false))
    override suspend fun deleteProfile(profileId: String) = false
    override suspend fun createSession(profileId: String): SessionInfo =
        error("additional sessions require a BrowserSessionRegistry")

    override suspend fun listSessions(profileId: String?) = listOf(
        SessionInfo(
            sessionId = DEFAULT_SESSION_ID,
            profileId = DEFAULT_PROFILE_ID,
            processSlot = 0,
            createdAtEpochMs = createdAt,
            lastAccessEpochMs = System.currentTimeMillis(),
            currentUrl = api.currentUrl(),
            isDefault = true,
        ),
    )

    override suspend fun closeSession(sessionId: String) = false

    private fun requireDefault(sessionId: String) {
        require(sessionId == DEFAULT_SESSION_ID) { "unknown session_id: $sessionId" }
    }

    override suspend fun fetch(sessionId: String, request: FetchRequest): RuntimePageResult {
        requireDefault(sessionId); return api.fetchDetailed(request)
    }

    override suspend fun fetchAuto(request: FetchRequest): Pair<String, RuntimePageResult> =
        DEFAULT_SESSION_ID to api.fetchDetailed(request)

    override suspend fun search(sessionId: String, query: String, engine: String, limit: Int): List<SearchHit> {
        requireDefault(sessionId); return api.searchResults(query, engine, limit)
    }

    override suspend fun expand(sessionId: String, pageId: String?, section: String): ExpandedSection {
        requireDefault(sessionId); return api.expand(section, pageId)
    }

    override suspend fun snapshot(sessionId: String): List<String> {
        requireDefault(sessionId); return api.listInteractiveElements()
    }

    override suspend fun click(sessionId: String, elementId: String): Boolean {
        requireDefault(sessionId); return api.click(elementId)
    }

    override suspend fun type(sessionId: String, elementId: String, text: String): Boolean {
        requireDefault(sessionId); return api.type(elementId, text)
    }

    override suspend fun select(sessionId: String, elementId: String, value: String): Boolean {
        requireDefault(sessionId)
        return runCatching {
            Json.parseToJsonElement(api.selectResult(elementId, value)).jsonObject["ok"]?.jsonPrimitive?.content == "true"
        }.getOrDefault(false)
    }

    override suspend fun scroll(sessionId: String, dx: Int, dy: Int): Boolean {
        requireDefault(sessionId); return api.scroll(dx, dy)
    }

    override suspend fun annotate(sessionId: String, on: Boolean): String {
        requireDefault(sessionId); return api.annotate(on)
    }

    override suspend fun title(sessionId: String): String {
        requireDefault(sessionId); return api.title()
    }

    override suspend fun inspect(sessionId: String, elementId: String): String {
        requireDefault(sessionId); return api.inspect(elementId)
    }

    override suspend fun armSubmitHook(sessionId: String): String {
        requireDefault(sessionId); return api.armSubmitHook()
    }

    override suspend fun currentUrl(sessionId: String): String {
        requireDefault(sessionId); return api.currentUrl()
    }

    override suspend fun navigate(sessionId: String, url: String): Pair<Boolean, String> {
        requireDefault(sessionId); return api.navigate(url)
    }

    override suspend fun goBack(sessionId: String): Pair<Boolean, String> {
        requireDefault(sessionId); return api.goBack()
    }

    override suspend fun closeIdleSessions(profileId: String?, idleMs: Long): Int {
        // 单 Session gateway 只有 default UI session，永不回收。
        return 0
    }

    override suspend fun fileDownload(sessionId: String, url: String, fileName: String): FileDownloadResult {
        requireDefault(sessionId); return api.fileDownload(url, fileName)
    }

    override suspend fun fileDownloadPending(sessionId: String): FileDownloadResult? {
        requireDefault(sessionId); return api.fileDownloadPending()
    }

    override suspend fun fileList(sessionId: String): List<FileInfo> {
        requireDefault(sessionId); return api.fileList()
    }

    override suspend fun fileDelete(sessionId: String, fileName: String): Boolean {
        requireDefault(sessionId); return api.fileDelete(fileName)
    }
}
