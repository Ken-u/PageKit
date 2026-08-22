package com.kenjc.pagekit.session

import com.kenjc.pagekit.api.dto.ExpandedSection
import com.kenjc.pagekit.api.dto.FetchRequest
import com.kenjc.pagekit.engine.SearchHit
import com.kenjc.pagekit.runtime.RuntimePageResult
import kotlinx.serialization.Serializable

const val DEFAULT_PROFILE_ID = "default"
const val DEFAULT_SESSION_ID = "default"

@Serializable
data class ProfileInfo(
    val profileId: String,
    val processSlot: Int,
    val isolated: Boolean,
)

@Serializable
data class SessionInfo(
    val sessionId: String,
    val profileId: String,
    val processSlot: Int,
    val createdAtEpochMs: Long,
    val lastAccessEpochMs: Long,
    val currentUrl: String = "",
    val isDefault: Boolean = false,
)

/** MCP、Kimi HTTP 与本地 UI 之上的 Session/Profile 路由边界。 */
interface PageKitSessionGateway {
    suspend fun createProfile(profileId: String): ProfileInfo
    suspend fun listProfiles(): List<ProfileInfo>
    suspend fun deleteProfile(profileId: String): Boolean

    suspend fun createSession(profileId: String = DEFAULT_PROFILE_ID): SessionInfo
    suspend fun listSessions(profileId: String? = null): List<SessionInfo>
    suspend fun closeSession(sessionId: String): Boolean

    suspend fun fetch(sessionId: String, request: FetchRequest): RuntimePageResult
    suspend fun search(sessionId: String, query: String, engine: String, limit: Int): List<SearchHit>
    suspend fun expand(sessionId: String, pageId: String?, section: String): ExpandedSection
    suspend fun snapshot(sessionId: String): List<String>
    suspend fun click(sessionId: String, elementId: String): Boolean
    suspend fun type(sessionId: String, elementId: String, text: String): Boolean
    suspend fun select(sessionId: String, elementId: String, value: String): Boolean
    suspend fun scroll(sessionId: String, dx: Int, dy: Int): Boolean
    suspend fun annotate(sessionId: String, on: Boolean): String
    suspend fun title(sessionId: String): String
    suspend fun inspect(sessionId: String, elementId: String): String
    suspend fun armSubmitHook(sessionId: String): String

    /** 当前 URL（含重定向后的最终地址）。 */
    suspend fun currentUrl(sessionId: String): String

    /** 导航并等待终态；返回 (是否 Ready, 最终 URL)。 */
    suspend fun navigate(sessionId: String, url: String): Pair<Boolean, String>

    /** 历史后退并等待终态；返回 (是否有历史可退, 最终 URL)。 */
    suspend fun goBack(sessionId: String): Pair<Boolean, String>

    /** 关闭 profile（或指定 session）中所有空闲超过 [idleMs] 的非默认 session；返回关闭数量。 */
    suspend fun closeIdleSessions(profileId: String? = null, idleMs: Long): Int
}
