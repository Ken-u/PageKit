package com.kenjc.pagekit.profile

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.kenjc.pagekit.api.DefaultPageKitApi
import com.kenjc.pagekit.api.dto.FetchRequest
import com.kenjc.pagekit.compress.FilePageExpansionCache
import com.kenjc.pagekit.compress.OpenAiCompatibleCompressor
import com.kenjc.pagekit.compress.SharedPreferencesLlmSettings
import com.kenjc.pagekit.engine.adblock.AssetCosmeticRuleRepository
import com.kenjc.pagekit.engine.adblock.AssetHostsRuleRepository
import com.kenjc.pagekit.engine.adblock.CompositeAdBlocker
import com.kenjc.pagekit.engine.adblock.HostsAdBlocker
import com.kenjc.pagekit.runtime.AndroidPageKitRuntime
import com.kenjc.pagekit.session.BrowserSessionComponents
import com.kenjc.pagekit.session.BrowserSessionFactory
import com.kenjc.pagekit.session.BrowserSessionRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/** 独立 WebView data-directory 进程中的 Profile worker。 */
open class ProfileWorkerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private lateinit var dispatcher: Dispatcher

    override fun onCreate() {
        super.onCreate()
        val slot = requireNotNull(ProfileProcess.currentSlot(packageName)) {
            "ProfileWorkerService must run in a declared profile process"
        }
        dispatcher = Dispatcher(slot)
    }

    private val binder = object : IProfileWorker.Stub() {
        override fun execute(requestJson: String): ParcelFileDescriptor {
            val response = runBlocking { dispatcher.execute(requestJson) }
            val directory = cacheDir.resolve("profile-ipc").apply { mkdirs() }
            val file = File.createTempFile("response-", ".json", directory)
            file.writeText(response)
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            file.delete()
            return descriptor
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runBlocking { dispatcher.shutdown() }
        scope.cancel()
        super.onDestroy()
    }

    private inner class Dispatcher(private val slot: Int) {
        private val hostsRepository = AssetHostsRuleRepository(this@ProfileWorkerService).also { it.start(scope) }
        private val cosmeticRepository = AssetCosmeticRuleRepository(this@ProfileWorkerService).also { it.start(scope) }
        private val adBlocker = CompositeAdBlocker(
            HostsAdBlocker(
                repository = hostsRepository,
                initialAllowlist = setOf("localhost", "127.0.0.1", "::1"),
            ),
            cosmeticRepository,
        )
        private val compressor = OpenAiCompatibleCompressor(SharedPreferencesLlmSettings(this@ProfileWorkerService))
        private val profileMutex = Mutex()
        private var activeProfileId: String? = null
        private var registry: BrowserSessionRegistry? = null

        suspend fun execute(raw: String): String {
            val response = try {
                val request = json.decodeFromString<ProfileWorkerRequest>(raw)
                ProfileWorkerResponse(ok = true, result = dispatch(request))
            } catch (error: Throwable) {
                ProfileWorkerResponse(ok = false, error = error.message ?: error::class.java.simpleName)
            }
            return json.encodeToString(response)
        }

        suspend fun shutdown() {
            registry?.closeAll()
            registry = null
        }

        private suspend fun dispatch(request: ProfileWorkerRequest): JsonObject {
            require(BrowserSessionRegistry.PROFILE_ID.matches(request.profileId)) { "invalid profile_id" }
            return when (request.operation) {
                ProfileWorkerOperations.INIT -> {
                    profileMutex.withLock { initialize(request.profileId) }
                    buildJsonObject { put("slot", slot); put("profile_id", request.profileId) }
                }
                ProfileWorkerOperations.RESET -> {
                    profileMutex.withLock {
                        requireActive(request.profileId)
                        resetProfile()
                    }
                    buildJsonObject { put("reset", true) }
                }
                else -> {
                    val sessions = profileMutex.withLock { requireActive(request.profileId) }
                    dispatchSessionOperation(request, sessions)
                }
            }
        }

        private suspend fun dispatchSessionOperation(
            request: ProfileWorkerRequest,
            sessions: BrowserSessionRegistry,
        ): JsonObject = when (request.operation) {
            ProfileWorkerOperations.SESSION_CREATE -> {
                val info = sessions.create(request.requiredSessionId())
                buildJsonObject { put("session", json.encodeToJsonElement(info)) }
            }
            ProfileWorkerOperations.SESSION_LIST -> buildJsonObject {
                put("sessions", json.encodeToJsonElement(sessions.list()))
            }
            ProfileWorkerOperations.SESSION_CLOSE -> buildJsonObject {
                put("closed", sessions.close(request.requiredSessionId()))
            }
            ProfileWorkerOperations.FETCH -> sessions.withApi(request.requiredSessionId()) { api ->
                val fetch = json.decodeFromJsonElement<FetchRequest>(request.arguments.required("request"))
                buildJsonObject { put("result", json.encodeToJsonElement(api.fetchDetailed(fetch))) }
            }
            ProfileWorkerOperations.SEARCH -> sessions.withApi(request.requiredSessionId()) { api ->
                val hits = api.searchResults(
                    query = request.arguments.requiredString("query"),
                    engine = request.arguments.string("engine") ?: "bing",
                    limit = request.arguments.int("limit") ?: 5,
                )
                buildJsonObject { put("results", json.encodeToJsonElement(hits)) }
            }
            ProfileWorkerOperations.EXPAND -> sessions.withApi(request.requiredSessionId()) { api ->
                val expanded = api.expand(
                    section = request.arguments.requiredString("section"),
                    pageId = request.arguments.string("page_id"),
                )
                buildJsonObject { put("result", json.encodeToJsonElement(expanded)) }
            }
            ProfileWorkerOperations.SNAPSHOT -> sessions.withApi(request.requiredSessionId()) { api ->
                buildJsonObject { put("elements", JsonArray(api.listInteractiveElements().map(::JsonPrimitive))) }
            }
            ProfileWorkerOperations.CLICK -> sessions.withApi(request.requiredSessionId()) { api ->
                buildJsonObject { put("ok", api.click(request.arguments.requiredString("element_id"))) }
            }
            ProfileWorkerOperations.TYPE -> sessions.withApi(request.requiredSessionId()) { api ->
                buildJsonObject {
                    put(
                        "ok",
                        api.type(
                            request.arguments.requiredString("element_id"),
                            request.arguments.requiredString("text"),
                        ),
                    )
                }
            }
            ProfileWorkerOperations.SELECT -> sessions.withApi(request.requiredSessionId()) { api ->
                buildJsonObject {
                    put(
                        "ok",
                        selectOk(
                            api.selectResult(
                                request.arguments.requiredString("element_id"),
                                request.arguments.requiredString("value"),
                            ),
                        ),
                    )
                }
            }
            ProfileWorkerOperations.CURRENT_URL -> sessions.withApi(request.requiredSessionId()) { api ->
                buildJsonObject { put("value", api.currentUrl()) }
            }
            ProfileWorkerOperations.NAVIGATE -> sessions.withApi(request.requiredSessionId()) { api ->
                val (ok, url) = api.navigate(request.arguments.requiredString("url"))
                buildJsonObject { put("ok", ok); put("url", url) }
            }
            ProfileWorkerOperations.GO_BACK -> sessions.withApi(request.requiredSessionId()) { api ->
                val (ok, url) = api.goBack()
                buildJsonObject { put("ok", ok); put("url", url) }
            }
            ProfileWorkerOperations.SESSION_CLOSE_IDLE -> buildJsonObject {
                put("closed", JsonArray(sessions.closeIdle(request.arguments.long("idle_ms") ?: 0L).map(::JsonPrimitive)))
            }
            ProfileWorkerOperations.SCROLL -> sessions.withApi(request.requiredSessionId()) { api ->
                buildJsonObject {
                    put("ok", api.scroll(request.arguments.int("dx") ?: 0, request.arguments.int("dy") ?: 600))
                }
            }
            ProfileWorkerOperations.ANNOTATE -> sessions.withApi(request.requiredSessionId()) { api ->
                buildJsonObject { put("value", api.annotate(request.arguments.boolean("on") ?: false)) }
            }
            ProfileWorkerOperations.TITLE -> sessions.withApi(request.requiredSessionId()) { api ->
                buildJsonObject { put("value", api.title()) }
            }
            ProfileWorkerOperations.INSPECT -> sessions.withApi(request.requiredSessionId()) { api ->
                buildJsonObject { put("value", api.inspect(request.arguments.requiredString("element_id"))) }
            }
            ProfileWorkerOperations.ARM_SUBMIT -> sessions.withApi(request.requiredSessionId()) { api ->
                buildJsonObject { put("value", api.armSubmitHook()) }
            }
            else -> error("unknown worker operation: ${request.operation}")
        }

        private suspend fun initialize(profileId: String) {
            val current = activeProfileId
            require(current == null || current == profileId) {
                "profile slot $slot is already assigned to $current"
            }
            if (registry != null) return
            activeProfileId = profileId
            registry = BrowserSessionRegistry(
                profileId = profileId,
                processSlot = slot,
                initialMaxSessions = com.kenjc.pagekit.ui.screensaver.ScreensaverSettings(this@ProfileWorkerService).loadMaxSessions(),
                factory = BrowserSessionFactory { sessionId -> createSession(profileId, sessionId) },
            )
        }

        private suspend fun createSession(profileId: String, sessionId: String): BrowserSessionComponents =
            withContext(Dispatchers.Main.immediate) {
                val runtime = AndroidPageKitRuntime(
                    context = this@ProfileWorkerService,
                    adBlocker = adBlocker,
                    compressor = compressor,
                    pageCache = FilePageExpansionCache(
                        context = this@ProfileWorkerService,
                        namespace = "p${slot}_${profileId}_${sessionId}",
                    ),
                )
                BrowserSessionComponents(
                    api = DefaultPageKitApi(runtime),
                    destroy = { withContext(Dispatchers.Main.immediate) { runtime.destroy() } },
                )
            }

        private fun requireActive(profileId: String): BrowserSessionRegistry {
            require(activeProfileId == profileId) { "profile $profileId is not initialized in slot $slot" }
            return requireNotNull(registry) { "profile worker is not initialized" }
        }

        private suspend fun resetProfile() {
            registry?.closeAll()
            registry = null
            clearWebViewProfileData()
            filesDir.resolve("page-cache").listFiles()
                ?.filter { it.name.startsWith("p${slot}_") }
                ?.forEach { it.deleteRecursively() }
            applicationInfo.dataDir.let(::File).resolve("shared_prefs").listFiles()
                ?.filter { it.name.startsWith("pagekit_page_cache_p${slot}_") }
                ?.forEach { it.delete() }
            activeProfileId = null
        }

        private suspend fun clearWebViewProfileData() = withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            WebStorage.getInstance().deleteAllData()
            WebView(this@ProfileWorkerService).apply {
                clearCache(true)
                clearFormData()
                clearHistory()
                destroy()
            }
        }

        private fun ProfileWorkerRequest.requiredSessionId(): String =
            requireNotNull(sessionId) { "session_id is required" }

        private fun JsonObject.required(name: String) =
            requireNotNull(get(name)) { "missing argument: $name" }

        private fun JsonObject.string(name: String): String? =
            get(name)?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotEmpty)

        private fun JsonObject.requiredString(name: String): String =
            requireNotNull(string(name)) { "missing argument: $name" }

        private fun JsonObject.int(name: String): Int? = get(name)?.jsonPrimitive?.intOrNull
        private fun JsonObject.long(name: String): Long? = get(name)?.jsonPrimitive?.longOrNull
        private fun JsonObject.boolean(name: String): Boolean? = get(name)?.jsonPrimitive?.booleanOrNull

        private fun selectOk(result: String): Boolean = runCatching {
            Json.parseToJsonElement(result).jsonObject["ok"]?.jsonPrimitive?.content == "true"
        }.getOrDefault(false)
    }
}

class ProfileWorkerService1 : ProfileWorkerService()
class ProfileWorkerService2 : ProfileWorkerService()
class ProfileWorkerService3 : ProfileWorkerService()
