package com.androidharness.app.workspace

import androidx.core.content.ContextCompat
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.db.ProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class ProjectDeletionPreview(
    val project: ProjectEntity,
    val chatIds: Set<String>,
    val automationIds: Set<String>,
    val blocker: String?,
)

/** Review for emptying the permanent app workspace; the row itself always stays. */
data class AppWorkspaceResetPreview(
    val project: ProjectEntity,
    val chatIds: Set<String>,
    val automationIds: Set<String>,
    val folderBytes: Long,
    val blocker: String?,
)

class ProjectDeletion(private val c: AppContainer) {
    suspend fun preview(projectId: String): ProjectDeletionPreview {
        val context = ContextCompat.getContextForLanguage(c.appContext)
        val project = requireNotNull(c.workspace.projects.first().firstOrNull { it.id == projectId }) {
            context.getString(R.string.wp_project_unavailable)
        }
        val related = c.workspace.projectChats(projectId)
        val owned = related.filter { it.projectId == projectId }.map { it.id }.toSet()
        val shared = related.filter { it.projectId != projectId }
        val automations = c.automation.repository.tasks.value.filter {
            it.projectId == projectId || it.targetSessionId in owned || it.activeRun?.sessionId in owned
        }.map { it.id }.toSet()
        val blocker = when {
            project.kind == WorkspaceManager.KIND_APP -> context.getString(R.string.ui_the_app_workspace_cannot_be_deleted)
            shared.isNotEmpty() -> context.getString(R.string.wp_delete_shared_blocked, shared.joinToString { it.title })
            related.any { c.runManager.isRunning(it.id) || c.runManager.hasPendingFollowUp(it.id) } ->
                context.getString(R.string.wp_delete_running_blocked)
            else -> null
        }
        return ProjectDeletionPreview(project, owned, automations, blocker)
    }

    suspend fun delete(reviewed: ProjectDeletionPreview) = withContext(Dispatchers.IO) {
        c.automation.withIdleAutomations {
            c.runManager.withIdleProject(reviewed.project.id) {
                val current = preview(reviewed.project.id)
                check(current.blocker == null) { current.blocker.orEmpty() }
                check(current.chatIds == reviewed.chatIds && current.automationIds == reviewed.automationIds &&
                    current.project.copy(lastUsedAt = 0) == reviewed.project.copy(lastUsedAt = 0)) {
                    ContextCompat.getString(c.appContext, R.string.wp_review_changed)
                }
                // Complete cleanup even if navigation disposes the confirmation dialog.
                withContext(NonCancellable) {
                    c.workspace.deleteProjectAndChats(current.project.id, current.chatIds)
                    current.automationIds.forEach(c.automation::delete)
                    c.automation.repository.detachDeletedChats(current.chatIds)
                    c.settings.removeProjectReferences(current.project.id, current.chatIds)
                }
            }
        }
    }

    /**
     * Review for "reset app workspace" (issue #106). [preview] keeps refusing the app
     * workspace, because the app always needs one; this path empties it instead.
     */
    suspend fun previewAppWorkspace(projectId: String): AppWorkspaceResetPreview {
        val context = ContextCompat.getContextForLanguage(c.appContext)
        val project = requireNotNull(c.workspace.projects.first().firstOrNull { it.id == projectId }) {
            context.getString(R.string.ui_workspace_unavailable)
        }
        require(project.kind == WorkspaceManager.KIND_APP) { context.getString(R.string.wp_reset_app_only) }
        val related = c.workspace.projectChats(projectId)
        val owned = related.filter { it.projectId == projectId }.map { it.id }.toSet()
        val shared = related.filter { it.projectId != projectId }
        val automations = c.automation.repository.tasks.value.filter {
            it.projectId == projectId || it.targetSessionId in owned || it.activeRun?.sessionId in owned
        }.map { it.id }.toSet()
        val blocker = when {
            shared.isNotEmpty() ->
                context.getString(R.string.wp_reset_shared_blocked, shared.joinToString { it.title })
            related.any { c.runManager.isRunning(it.id) || c.runManager.hasPendingFollowUp(it.id) } ->
                context.getString(R.string.wp_reset_running_blocked)
            else -> null
        }
        val bytes = withContext(Dispatchers.IO) { c.workspace.appWorkspaceBytes() }
        return AppWorkspaceResetPreview(project, owned, automations, bytes, blocker)
    }

    /** Empties the app workspace once its chats and automations can be removed. */
    suspend fun resetAppWorkspace(reviewed: AppWorkspaceResetPreview) = withContext(Dispatchers.IO) {
        c.automation.withIdleAutomations {
            c.runManager.withIdleProject(reviewed.project.id) {
                val current = previewAppWorkspace(reviewed.project.id)
                check(current.blocker == null) { current.blocker.orEmpty() }
                check(current.chatIds == reviewed.chatIds && current.automationIds == reviewed.automationIds) {
                    ContextCompat.getString(c.appContext, R.string.wp_review_changed)
                }
                // Complete cleanup even if navigation disposes the confirmation dialog.
                withContext(NonCancellable) {
                    c.workspace.resetAppWorkspace(current.project.id, current.chatIds)
                    current.automationIds.forEach(c.automation::delete)
                    c.automation.repository.detachDeletedChats(current.chatIds)
                    c.settings.removeProjectReferences(current.project.id, current.chatIds)
                }
            }
        }
    }
}
