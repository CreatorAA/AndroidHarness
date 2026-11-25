package com.androidharness.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.androidharness.app.data.db.SessionEntity
import kotlinx.coroutines.flow.first

/** The quick panel's own pointer. Opening history in the main app does not take over the assistant. */
class PhonePanelHistory(
    private val context: Context,
    private val sessions: SessionRepository,
    private val settings: SettingsRepository,
) {
    private fun key(projectId: String) = stringPreferencesKey(KEY_PREFIX + projectId)

    suspend fun load(projectId: String): String? {
        val key = key(projectId)
        val saved = context.settingsStore.data.first()[key] ?: return null
        val archived = settings.settings.first().archivedSessions
        if (sessions.openSession(saved) { canResume(it, projectId, archived) } != null) return saved
        context.settingsStore.edit { if (it[key] == saved) it.remove(key) }
        return null
    }

    suspend fun save(projectId: String, sessionId: String) {
        if (canResume(sessions.session(sessionId), projectId, settings.settings.first().archivedSessions))
            context.settingsStore.edit { it[key(projectId)] = sessionId }
    }

    suspend fun clear(projectId: String) {
        context.settingsStore.edit { it.remove(key(projectId)) }
    }

    internal companion object {
        const val KEY_PREFIX = "phone_panel_session:"

        fun canResume(row: SessionEntity?, projectId: String, archived: Set<String>): Boolean {
            if (row == null || row.projectId != projectId || row.id in archived) return false
            val config = ConversationConfig.decode(row.conversationJson)
            return config.isPhone && config.phoneOriginCodeId == null
        }
    }
}
