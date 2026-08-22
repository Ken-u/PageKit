package com.kenjc.pagekit.session

import android.content.Context
import com.kenjc.pagekit.api.dto.ExpandedSection
import com.kenjc.pagekit.api.dto.FetchRequest
import com.kenjc.pagekit.engine.SearchHit
import com.kenjc.pagekit.profile.ProfileSlotStore
import com.kenjc.pagekit.profile.ProfileWorkerClient
import com.kenjc.pagekit.profile.ProfileWorkerOperations
import com.kenjc.pagekit.profile.ProfileWorkerRequest
import com.kenjc.pagekit.profile.ProfileWorkerResponse
import com.kenjc.pagekit.runtime.RuntimePageResult
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 主进程路由：slot 0 为本地 UI/Profile，slot 1..3 为独立 WebView Profile 进程。 */
class MultiProfileSessionGateway(
    context: Context,
    private val localSessions: BrowserSessionRegistry,
    private val slotStore: ProfileSlotStore = ProfileSlotStore(context),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false },
) : PageKitSessionGateway {
    private val clients = (1..3).associateWith { ProfileWorkerClient(context, it) }
    private val random = SecureRandom()

    override suspend fun createProfile(profileId: String): ProfileInfo {
        require(profileId != DEFAULT_PROFILE_ID) { "the default profile already exists" }
        val existing = slotStore.slotFor(profileId)
        val slot = existing ?: slotStore.reserve(profileId)
        try {
            remote(slot, ProfileWorkerOperations.INIT, profileId)
        } catch (error: Throwable) {
            if (existing == null) slotStore.release(profileId)
            throw error
        }
        return ProfileInfo(profileId, slot, isolated = true)
    }

    override suspend fun listProfiles(): List<ProfileInfo> = buildList {
        add(ProfileInfo(DEFAULT_PROFILE_ID, 0, isolated = false))
        slotStore.mappings().toSortedMap().forEach { (slot, profile) ->
            add(ProfileInfo(profile, slot, isolated = true))
        }
    }

    override suspend fun deleteProfile(profileId: String): Boolean {
        require(profileId != DEFAULT_PROFILE_ID) { "the default profile cannot be deleted" }
        val slot = slotStore.slotFor(profileId) ?: return false
        remote(slot, ProfileWorkerOperations.INIT, profileId)
        remote(slot, ProfileWorkerOperations.RESET, profileId)
        slotStore.release(profileId)
        clients.getValue(slot).close()
        return true
    }

    override suspend fun createSession(profileId: String): SessionInfo {
        val slot = if (profileId == DEFAULT_PROFILE_ID) 0 else requireNotNull(slotStore.slotFor(profileId)) {
            "unknown profile_id: $profileId; call profile_create first"
        }
        val sessionId = newSessionId(slot)
        if (slot == 0) return localSessions.create(sessionId)
        remote(slot, ProfileWorkerOperations.INIT, profileId)
        val result = remote(slot, ProfileWorkerOperations.SESSION_CREATE, profileId, sessionId)
        return json.decodeFromJsonElement(result.getValue("session"))
    }

    override suspend fun listSessions(profileId: String?): List<SessionInfo> {
        val profiles = if (profileId == null) listProfiles() else listOf(resolveProfile(profileId))
        return profiles.flatMap { profile ->
            if (profile.processSlot == 0) {
                localSessions.list()
            } else {
                remote(profile.processSlot, ProfileWorkerOperations.INIT, profile.profileId)
                val result = remote(profile.processSlot, ProfileWorkerOperations.SESSION_LIST, profile.profileId)
                json.decodeFromJsonElement(result.getValue("sessions"))
            }
        }
    }

    override suspend fun closeSession(sessionId: String): Boolean {
        val route = route(sessionId)
        if (route.slot == 0) return localSessions.close(sessionId)
        return remote(route.slot, ProfileWorkerOperations.SESSION_CLOSE, route.profileId, sessionId)
            .getValue("closed").jsonPrimitive.content.toBooleanStrict()
    }

    override suspend fun fetch(sessionId: String, request: FetchRequest): RuntimePageResult =
        routed(sessionId, ProfileWorkerOperations.FETCH, buildJsonObject {
            put("request", json.encodeToJsonElement(request))
        }, local = { api -> api.fetchDetailed(request) }) { result ->
            json.decodeFromJsonElement(result.getValue("result"))
        }

    override suspend fun search(sessionId: String, query: String, engine: String, limit: Int): List<SearchHit> =
        routed(sessionId, ProfileWorkerOperations.SEARCH, buildJsonObject {
            put("query", query); put("engine", engine); put("limit", limit)
        }, local = { api -> api.searchResults(query, engine, limit) }) { result ->
            json.decodeFromJsonElement(result.getValue("results"))
        }

    override suspend fun expand(sessionId: String, pageId: String?, section: String): ExpandedSection =
        routed(sessionId, ProfileWorkerOperations.EXPAND, buildJsonObject {
            if (pageId != null) put("page_id", pageId)
            put("section", section)
        }, local = { api -> api.expand(section, pageId) }) { result ->
            json.decodeFromJsonElement(result.getValue("result"))
        }

    override suspend fun snapshot(sessionId: String): List<String> =
        routed(sessionId, ProfileWorkerOperations.SNAPSHOT, local = { it.listInteractiveElements() }) { result ->
            result.getValue("elements").jsonArray.map { it.jsonPrimitive.content }
        }

    override suspend fun click(sessionId: String, elementId: String): Boolean =
        booleanOperation(sessionId, ProfileWorkerOperations.CLICK, buildJsonObject { put("element_id", elementId) }) {
            it.click(elementId)
        }

    override suspend fun type(sessionId: String, elementId: String, text: String): Boolean =
        booleanOperation(sessionId, ProfileWorkerOperations.TYPE, buildJsonObject {
            put("element_id", elementId); put("text", text)
        }) { it.type(elementId, text) }

    override suspend fun scroll(sessionId: String, dx: Int, dy: Int): Boolean =
        booleanOperation(sessionId, ProfileWorkerOperations.SCROLL, buildJsonObject { put("dx", dx); put("dy", dy) }) {
            it.scroll(dx, dy)
        }

    override suspend fun annotate(sessionId: String, on: Boolean): String =
        stringOperation(sessionId, ProfileWorkerOperations.ANNOTATE, buildJsonObject { put("on", on) }) {
            it.annotate(on)
        }

    override suspend fun title(sessionId: String): String =
        stringOperation(sessionId, ProfileWorkerOperations.TITLE) { it.title() }

    override suspend fun inspect(sessionId: String, elementId: String): String =
        stringOperation(sessionId, ProfileWorkerOperations.INSPECT, buildJsonObject { put("element_id", elementId) }) {
            it.inspect(elementId)
        }

    override suspend fun armSubmitHook(sessionId: String): String =
        stringOperation(sessionId, ProfileWorkerOperations.ARM_SUBMIT) { it.armSubmitHook() }

    override suspend fun select(sessionId: String, elementId: String, value: String): Boolean =
        booleanOperation(sessionId, ProfileWorkerOperations.SELECT, buildJsonObject {
            put("element_id", elementId); put("value", value)
        }) { it.selectResult(elementId, value).let { r -> selectOk(r) } }

    private fun selectOk(result: String): Boolean = runCatching {
        json.parseToJsonElement(result).jsonObject["ok"]?.jsonPrimitive?.content == "true"
    }.getOrDefault(false)

    override suspend fun currentUrl(sessionId: String): String =
        stringOperation(sessionId, ProfileWorkerOperations.CURRENT_URL) { it.currentUrl() }

    override suspend fun navigate(sessionId: String, url: String): Pair<Boolean, String> =
        routed(sessionId, ProfileWorkerOperations.NAVIGATE, buildJsonObject { put("url", url) },
            local = { it.navigate(url) }) { result ->
            result.getValue("ok").jsonPrimitive.content.toBooleanStrict() to result.getValue("url").jsonPrimitive.content
        }

    override suspend fun goBack(sessionId: String): Pair<Boolean, String> =
        routed(sessionId, ProfileWorkerOperations.GO_BACK, JsonObject(emptyMap()),
            local = { it.goBack() }) { result ->
            result.getValue("ok").jsonPrimitive.content.toBooleanStrict() to result.getValue("url").jsonPrimitive.content
        }

    override suspend fun closeIdleSessions(profileId: String?, idleMs: Long): Int {
        require(idleMs >= 0) { "idle_ms must be >= 0" }
        val targets: List<Pair<Int, String>> = when (profileId) {
            null -> {
                // 所有 profile：本地 default + 已分配的 worker 槽
                buildList {
                    add(0 to DEFAULT_PROFILE_ID)
                    slotStore.mappings().forEach { (slot, profile) -> add(slot to profile) }
                }
            }

            DEFAULT_PROFILE_ID -> listOf(0 to DEFAULT_PROFILE_ID)

            else -> {
                val slot = requireNotNull(slotStore.slotFor(profileId)) { "unknown profile_id: $profileId" }
                listOf(slot to profileId)
            }
        }
        return targets.sumOf { (slot, profile) ->
            if (slot == 0) {
                localSessions.closeIdle(idleMs).size
            } else {
                remote(slot, ProfileWorkerOperations.SESSION_CLOSE_IDLE, profile, arguments = buildJsonObject {
                    put("idle_ms", idleMs)
                }).getValue("closed").jsonArray.size
            }
        }
    }

    suspend fun close() {
        clients.values.forEach { it.close() }
        localSessions.closeAll()
    }

    private suspend fun booleanOperation(
        sessionId: String,
        operation: String,
        arguments: JsonObject = JsonObject(emptyMap()),
        local: suspend (com.kenjc.pagekit.api.DefaultPageKitApi) -> Boolean,
    ): Boolean = routed(sessionId, operation, arguments, local) { result ->
        result.getValue("ok").jsonPrimitive.content.toBooleanStrict()
    }

    private suspend fun stringOperation(
        sessionId: String,
        operation: String,
        arguments: JsonObject = JsonObject(emptyMap()),
        local: suspend (com.kenjc.pagekit.api.DefaultPageKitApi) -> String,
    ): String = routed(sessionId, operation, arguments, local) { result ->
        result.getValue("value").jsonPrimitive.content
    }

    private suspend fun <T> routed(
        sessionId: String,
        operation: String,
        arguments: JsonObject = JsonObject(emptyMap()),
        local: suspend (com.kenjc.pagekit.api.DefaultPageKitApi) -> T,
        decode: (JsonObject) -> T,
    ): T {
        val route = route(sessionId)
        return if (route.slot == 0) {
            localSessions.withApi(sessionId, local)
        } else {
            decode(remote(route.slot, operation, route.profileId, sessionId, arguments))
        }
    }

    private suspend fun remote(
        slot: Int,
        operation: String,
        profileId: String,
        sessionId: String? = null,
        arguments: JsonObject = JsonObject(emptyMap()),
    ): JsonObject {
        val raw = clients.getValue(slot).execute(
            json.encodeToString(ProfileWorkerRequest(operation, profileId, sessionId, arguments)),
        )
        val response = json.decodeFromString<ProfileWorkerResponse>(raw)
        require(response.ok) { response.error ?: "profile worker operation failed" }
        return response.result ?: JsonObject(emptyMap())
    }

    private fun resolveProfile(profileId: String): ProfileInfo = when (profileId) {
        DEFAULT_PROFILE_ID -> ProfileInfo(DEFAULT_PROFILE_ID, 0, isolated = false)
        else -> {
            val slot = requireNotNull(slotStore.slotFor(profileId)) { "unknown profile_id: $profileId" }
            ProfileInfo(profileId, slot, isolated = true)
        }
    }

    private fun route(sessionId: String): Route {
        if (sessionId == DEFAULT_SESSION_ID) return Route(0, DEFAULT_PROFILE_ID)
        require(BrowserSessionRegistry.SESSION_ID.matches(sessionId)) { "invalid session_id" }
        val slot = sessionId[1].digitToInt()
        val profileId = if (slot == 0) DEFAULT_PROFILE_ID else requireNotNull(slotStore.profileAt(slot)) {
            "session profile no longer exists: $sessionId"
        }
        return Route(slot, profileId)
    }

    private fun newSessionId(slot: Int): String {
        val bytes = ByteArray(8).also(random::nextBytes)
        return "s${slot}_" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private data class Route(val slot: Int, val profileId: String)
}
