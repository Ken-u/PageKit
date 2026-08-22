package com.kenjc.pagekit.profile

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ProfileWorkerRequest(
    val operation: String,
    val profileId: String,
    val sessionId: String? = null,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class ProfileWorkerResponse(
    val ok: Boolean,
    val result: JsonObject? = null,
    val error: String? = null,
)

object ProfileWorkerOperations {
    const val INIT = "profile_init"
    const val RESET = "profile_reset"
    const val SESSION_CREATE = "session_create"
    const val SESSION_LIST = "session_list"
    const val SESSION_CLOSE = "session_close"
    const val SESSION_CLOSE_IDLE = "session_close_idle"
    const val FETCH = "fetch"
    const val SEARCH = "search"
    const val EXPAND = "expand"
    const val SNAPSHOT = "snapshot"
    const val CLICK = "click"
    const val TYPE = "type"
    const val SELECT = "select"
    const val SCROLL = "scroll"
    const val ANNOTATE = "annotate"
    const val TITLE = "title"
    const val INSPECT = "inspect"
    const val ARM_SUBMIT = "arm_submit"
    const val NAVIGATE = "navigate"
    const val GO_BACK = "go_back"
    const val CURRENT_URL = "current_url"
}
