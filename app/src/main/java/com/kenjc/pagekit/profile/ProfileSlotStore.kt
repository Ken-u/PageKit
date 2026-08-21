package com.kenjc.pagekit.profile

import android.content.Context
import com.kenjc.pagekit.session.BrowserSessionRegistry
import com.kenjc.pagekit.session.DEFAULT_PROFILE_ID

class ProfileSlotStore(context: Context) {
    private val preferences = context.getSharedPreferences("pagekit_profile_slots", Context.MODE_PRIVATE)
    private val lock = Any()

    fun mappings(): Map<Int, String> = synchronized(lock) {
        (1..3).mapNotNull { slot -> profileAt(slot)?.let { slot to it } }.toMap()
    }

    fun slotFor(profileId: String): Int? = synchronized(lock) {
        mappings().entries.firstOrNull { it.value == profileId }?.key
    }

    fun profileAt(slot: Int): String? =
        preferences.getString("slot_$slot", null)?.takeIf(String::isNotBlank)

    fun reserve(profileId: String): Int = synchronized(lock) {
        require(profileId != DEFAULT_PROFILE_ID) { "default is the main-process profile" }
        require(BrowserSessionRegistry.PROFILE_ID.matches(profileId)) { "invalid profile_id" }
        slotFor(profileId)?.let { return@synchronized it }
        val slot = (1..3).firstOrNull { profileAt(it) == null }
            ?: error("all 3 isolated profile slots are in use")
        check(preferences.edit().putString("slot_$slot", profileId).commit()) {
            "failed to persist profile slot"
        }
        slot
    }

    fun release(profileId: String): Int? = synchronized(lock) {
        val slot = slotFor(profileId) ?: return@synchronized null
        check(preferences.edit().remove("slot_$slot").commit()) { "failed to release profile slot" }
        slot
    }
}
