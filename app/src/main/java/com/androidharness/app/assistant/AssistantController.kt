package com.androidharness.app.assistant

import android.content.Context
import android.app.KeyguardManager
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.graphics.Bitmap
import android.os.PowerManager
import android.os.SystemClock
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.agent.AgentMode
import com.androidharness.app.agent.ApprovalRequest
import com.androidharness.app.agent.QuestionRequest
import com.androidharness.app.agent.RunManager
import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.agent.ThinkingSpecs
import com.androidharness.app.agent.ThinkingTiming
import com.androidharness.app.core.ChatExperience
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.core.response.ChatRichResponse
import com.androidharness.app.core.response.ResponseAttachment
import com.androidharness.app.data.ChatModelChoice
import com.androidharness.app.tools.SharedScreen
import com.androidharness.app.tools.SharedScreens
import com.androidharness.app.tools.SuggestAppActionTool
import androidx.core.content.ContextCompat
import com.androidharness.app.data.ChatModelSelection
import com.androidharness.app.data.ChatModelState
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ConversationKind
import com.androidharness.app.data.SearchDepth
import com.androidharness.app.llm.HarnessProvider
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ModelsDev
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.llm.effectiveApiKey
import com.androidharness.app.local.LocalModelCatalog
import com.androidharness.app.ui.chat.FileAttachments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import com.androidharness.app.androiduse.PhoneScope
import com.androidharness.app.workspace.WorkspaceManager

internal data class AssistantUiState(
    val screen: ScreenSnapshot = ScreenSnapshot(),
    /** True once Android has answered (or declined) both the text and screenshot requests. */
    val screenSettled: Boolean = false,
    val sessionId: String? = null,
    /** Saved rows of this panel's chat, the same list the Chat screen reads. */
    val messages: List<ChatMessage> = emptyList(),
    /** The question just sent, shown until its saved row arrives. */
    val sending: String? = null,
    val busy: Boolean = false,
    val currentTurnId: String? = null,
    val streamingText: String? = null,
    val streamingThinking: String? = null,
    val streamingMessageId: String? = null,
    /** The live answer has been saved; it stays on screen under the same id until its row arrives. */
    val streamingCommitted: Boolean = false,
    val runningCalls: List<ToolCallData> = emptyList(),
    val thinkingTiming: ThinkingTiming = ThinkingTiming(),
    val reasoningTokens: Int? = null,
    val turnStartedAt: Long? = null,
    val approval: ApprovalRequest? = null,
    val question: QuestionRequest? = null,
    val questionAutoAt: Long? = null,
    val retryStatus: String? = null,
    val error: String? = null,
    /** Short name of the model that answers here. */
    val modelLabel: String? = null,
    /** The assistant's thinking setting as this model understands it. */
    val thinking: ThinkingSpecs.ResolvedThinking? = null,
    /** Local, one-use buttons. The transcript itself cannot launch an app. */
    val appHandoff: AppHandoffPlan? = null,
    val phoneReview: PhoneReview? = null,
) {
    val hasConversation: Boolean get() = messages.isNotEmpty() || sending != null || busy || error != null || appHandoff != null
}

/** A one-turn proposal carries the person's words, never model- or screen-authored instructions. */
internal class PhoneReview(val instruction: String, val target: String?, val source: Any?, val invocation: Int,
    val createdAt: Long) {
    fun remaining(now: Long) = (createdAt + 120_000L - now).coerceAtLeast(0L)
    fun isCurrent(pkg: String?, page: Any?, epoch: Int, now: Long) = source != null && source === page &&
        target == pkg && invocation == epoch && now >= createdAt && remaining(now) > 0
}

/**
 * Runs the current project's Phone conversation. "Continue in app" opens the same
 * history; returning to the assistant does not restore an earlier phone-control grant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AssistantController(
    private val context: Context,
    private val c: AppContainer,
    private val onPhoneStart: (String, Boolean) -> Unit,
    private val onPhoneFinish: (String) -> Unit,
    private val onPhonePrompt: () -> Unit,
    private val pageSourceFor: (String) -> Any?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = mutable.asStateFlow()
    private val session = MutableStateFlow<String?>(null)
    private var starting = false
    private var pendingText = false
    private var pendingShot = false
    /** The last answer that streamed, kept on screen until its saved row shows up. */
    private var held: Held? = null
    /** Read the next answer aloud: the question was spoken. */
    private var speakNext = false
    private var runSeen = false
    /** When the current question was sent; its saved row replaces the pending bubble. */
    private var sentAt = 0L
    /** A run error the user already closed, so a late update does not bring it back. */
    private var dismissed: String? = null
    /** The last question typed or spoken here, to send again when it failed before reaching the chat. */
    private var lastAsked: String? = null
    /** Counts assistant calls, so a screen still loading is never taken from a later call. */
    private var generation = 0
    private var historyJob: Job? = null
    @Volatile private var historyEpoch = 0L
    private val historyWrites = Mutex()
    private var sharedScreenGeneration = -1
    /** A final transcription belongs to the summons that started listening, even if Android reuses this window. */
    val invocation get() = generation
    fun acceptsVoice(from: Int) = generation == from && c.runtimePolicy.assistantPanelVisible.value
    private var submissionJob: Job? = null
    private var phoneGrant: Any? = null
    private var phoneSession: String? = null
    private val handoffs = AppHandoffResolver(context)
    private var proposalTurn: Any? = null
    private var pendingApp: PendingApp? = null
    private var newConversationTurn: String? = null
    private data class PendingApp(val sessionId: String, val turnId: String, val plan: AppHandoffPlan)

    private class Held(val messageId: String, val text: String?, val thinking: String?)

    init {
        scope.launch {
            session.flatMapLatest { sid ->
                if (sid == null) flowOf(null) else combine(c.sessions.messagesFlow(sid), c.runManager.live(sid)) { rows, live -> rows to live }
            }.collect { pair ->
                val (rows, live) = pair ?: return@collect
                mirror(rows, live)
            }
        }
        scope.launch {
            combine(session.flatMapLatest { c.chatModels.observe(it) }, c.providers.catalogs, ModelsDev.revision) { models, catalogs, _ ->
                describeModel(models, catalogs)
            }.collect { (label, thinking) -> mutable.update { it.copy(modelLabel = label, thinking = thinking) } }
        }
    }

    /**
     * Same handoff as the Chat screen: when the stream stops before its saved row arrives,
     * the last text stays under the same id, so the answer never blinks out for a frame.
     */
    private fun mirror(rows: List<ChatMessage>, live: RunManager.LiveRunState) {
        mutable.update { st ->
            var text = live.streamingText
            var thinking = live.streamingThinking
            var streamId = live.liveMessageId
            var committed = false
            if (text == null && thinking == null) {
                val hold = held ?: live.lastCommittedId?.takeIf { id ->
                    st.streamingMessageId == id && (!st.streamingText.isNullOrBlank() || !st.streamingThinking.isNullOrBlank())
                }?.let { id -> Held(id, st.streamingText, st.streamingThinking).also { h -> held = h; releaseLater(h) } }
                if (hold != null && rows.none { it.id == hold.messageId }) {
                    text = hold.text
                    thinking = hold.thinking
                    streamId = hold.messageId
                    committed = true
                } else if (hold != null) held = null
            } else held = null
            val running = live.running
            if (running) runSeen = true
            st.copy(
                messages = rows,
                sending = st.sending?.takeIf { rows.none { row -> row.role == Role.USER && row.createdAt >= sentAt } },
                busy = running || starting,
                currentTurnId = live.turnId,
                streamingText = text,
                streamingThinking = thinking,
                streamingMessageId = streamId,
                streamingCommitted = committed,
                runningCalls = live.runningCalls,
                thinkingTiming = if (committed) st.thinkingTiming.stop(ThinkingTiming.now()) else live.thinkingTiming,
                reasoningTokens = if (committed) st.reasoningTokens else live.reasoningTokens,
                turnStartedAt = live.turnStartedAt,
                approval = live.pendingApproval,
                question = live.pendingQuestion,
                questionAutoAt = live.questionAutoAt,
                retryStatus = live.retryStatus,
                error = live.error?.takeIf { !running && it != dismissed } ?: st.error,
            )
        }
        if (newConversationTurn != null && !live.running && rows.lastOrNull { it.role == Role.USER }?.turnId == newConversationTurn) {
            val reset = live.error == null && canHandOff()
            newConversationTurn = null
            if (reset) { newQuestion(); return }
        }
        if (phoneGrant != null && (live.pendingApproval != null || live.pendingQuestion != null)) onPhonePrompt()
        if (speakNext && runSeen && !live.running && c.runtimePolicy.assistantPanelVisible.value) speakAnswer(rows)
        if (!live.running && !starting && !c.runtimePolicy.assistantPanelVisible.value) {
            session.value?.let(SharedScreens::clear)
            sharedScreenGeneration = -1
        }
    }

    /** A held answer must not outlive a slow or failed save. */
    private fun releaseLater(hold: Held) {
        scope.launch {
            delay(HOLD_TIMEOUT_MS)
            if (held === hold && mutable.value.messages.none { it.id == hold.messageId }) {
                held = null
                mutable.update { it.copy(streamingText = null, streamingThinking = null, streamingMessageId = null, streamingCommitted = false) }
            }
        }
    }

    private fun speakAnswer(rows: List<ChatMessage>) {
        val turn = rows.lastOrNull { it.role == Role.USER }?.turnId ?: return
        val answer = rows.lastOrNull { it.turnId == turn && it.role == Role.ASSISTANT && it.toolCallId == null && it.toolCalls.isEmpty() && it.text.isNotBlank() }
        val id = answer?.id ?: return
        speakNext = false
        scope.launch {
            if (c.settings.settings.first().assistantSpeakReplies) c.tts.enqueueAuto(id, ChatRichResponse.normalize(answer.text))
        }
    }

    /** The model picked for the assistant in settings, while its provider is still there; null follows Chat. */
    private fun ownModel(models: ChatModelState): ChatModelChoice? {
        val id = models.settings.assistantProviderId ?: return null
        val provider = models.providers.firstOrNull { it.id == id } ?: return null
        return ChatModelChoice(id, models.settings.assistantModel?.takeIf { it.isNotBlank() } ?: provider.model)
    }

    private fun describeModel(models: ChatModelState, catalogs: Map<String, List<ModelEntry>>): Pair<String?, ThinkingSpecs.ResolvedThinking?> {
        val choice = ownModel(models) ?: models.selection.active
        val provider = models.providers.firstOrNull { it.id == choice?.providerId } ?: return null to null
        val model = choice?.model?.takeIf { it.isNotBlank() } ?: provider.model
        val entry = catalogs[provider.id].orEmpty().firstOrNull { it.id == model } ?: ModelEntry(model)
        val thinking = runCatching {
            RequestOptions(thinking = models.settings.assistantThinking, maxOutputTokens = models.settings.maxOutputTokens,
                customReasoning = models.settings.reasoningConfigs, customThinkingLevels = models.selection.customThinkingLevels)
                .resolveThinking(provider.copy(model = model), entry)
        }.getOrNull()
        return (ChatExperience.modelLabel(model) ?: model) to thinking
    }

    /** Restore the conversation, not its old screen, proposed actions or control grant. */
    fun begin(wantsText: Boolean, wantsShot: Boolean) {
        dismissAppHandoff()
        generation++
        session.value?.let(SharedScreens::clear)
        pendingText = wantsText
        pendingShot = wantsShot
        quiet()
        mutable.update { AssistantUiState(screenSettled = !wantsText && !wantsShot, modelLabel = it.modelLabel, thinking = it.thinking) }
        session.value = null
        held = null
        lastAsked = null
        val current = ++historyEpoch
        val previous = historyJob
        historyJob = scope.launch {
            previous?.join()
            val restored = c.phoneHistory.load(c.workspace.currentProjectOnce().id)
            if (historyEpoch == current && restored != null) {
                session.value = restored
                mutable.update { it.copy(sessionId = restored) }
            }
        }
    }

    /**
     * Reads the screen's text away from the main thread: walking the structure can fetch more of
     * it from the other app, and that would stall the opening animation.
     */
    @Suppress("DEPRECATION")
    fun onAssist(structure: AssistStructure?, fallbackPackage: String?, content: AssistContent?) {
        val gen = generation
        scope.launch {
            val read = withContext(Dispatchers.Default) {
                val text = structure?.let { runCatching { ScreenText.fromStructure(it) }.getOrNull() }?.takeIf { it.isNotBlank() }
                val pkg = structure?.let { runCatching { it.activityComponent?.packageName }.getOrNull() } ?: fallbackPackage
                ScreenRead(pkg, ScreenText.appName(context, pkg), text, ScreenText.webUri(content))
            }
            if (gen != generation) return@launch
            pendingText = false
            mutable.update { it.copy(screen = it.screen.copy(packageName = read.packageName ?: it.screen.packageName,
                appName = read.appName ?: it.screen.appName, text = read.text, webUri = read.webUri),
                screenSettled = !pendingShot) }
        }
    }

    private class ScreenRead(val packageName: String?, val appName: String?, val text: String?, val webUri: String?)

    /** Kept as Android gave it; it is only copied to a normal bitmap when the model asks to see it. */
    fun onScreenshot(bitmap: Bitmap?) {
        pendingShot = false
        mutable.update { it.copy(screen = it.screen.copy(screenshot = bitmap), screenSettled = !pendingText) }
    }

    fun dismissError() {
        dismissed = mutable.value.error
        mutable.update { it.copy(error = null) }
    }

    fun showError(message: String) = mutable.update { it.copy(error = message) }

    fun newQuestion(forgetSavedSession: Boolean = true) {
        historyEpoch++
        dismissAppHandoff()
        quiet()
        session.value?.let(SharedScreens::clear)
        if (forgetSavedSession) stop()
        val previous = historyJob
        historyJob = scope.launch {
            previous?.join()
            if (forgetSavedSession) historyWrites.withLock { c.phoneHistory.clear(c.workspace.currentProjectOnce().id) }
        }
        session.value = null
        held = null
        lastAsked = null
        sharedScreenGeneration = -1
        mutable.update { AssistantUiState(screen = it.screen, screenSettled = it.screenSettled,
            modelLabel = it.modelLabel, thinking = it.thinking) }
    }

    fun stop() {
        dismissAppHandoff()
        cancelPhone()
        val sid = session.value ?: return
        speakNext = false
        scope.launch { runCatching { c.runManager.stopAndJoin(sid) } }
    }

    fun approve(remember: Boolean) { session.value?.let { c.runManager.approve(it, remember) } }

    fun deny() { session.value?.let(c.runManager::deny) }

    fun answer(expected: QuestionRequest, text: String): Boolean {
        val sid = session.value ?: return false
        val live = c.runManager.live(sid).value
        val turn = live.turnId ?: return false
        if (live.pendingQuestion !== expected) return false
        return c.runManager.respondToRemoteQuestion(sid, turn, expected.callId, text)
    }

    fun setThinking(level: ThinkingLevel) { scope.launch { c.settings.setAssistantThinking(level) } }

    /** Reads one answer aloud, or stops it when it is already speaking. */
    fun speak(messageId: String, text: String) = c.tts.toggle(messageId, text)

    /** Stops reading only when what is being read belongs to this panel. */
    fun quiet() {
        val speaking = c.tts.state.value.messageId ?: return
        if (mutable.value.messages.any { it.id == speaking }) c.tts.stop()
    }

    /** Answers the latest question again with deep research, the way Chat's "Dig deeper" does. */
    fun digDeeper(): Job? {
        val question = mutable.value.messages.lastOrNull { it.role == Role.USER && it.id != null } ?: return null
        return run(question.text, spoken = false, replacement = question, depth = SearchDepth.DEEP)
    }

    fun send(question: String, spoken: Boolean = false, refersToScreen: Boolean = false): Job? {
        val text = question.trim()
        if (text.equals("/new", ignoreCase = true)) {
            if (starting || mutable.value.busy) return null
            newQuestion()
            return historyJob
        }
        if (text.isNotEmpty()) {
            val sid = session.value
            val pending = mutable.value.question?.takeIf { it.phoneClarification }
            if (sid != null && pending != null && c.androidUse.isAgentTask(sid)) {
                return if (answer(pending, ResponseAttachment.visibleText(text)))
                    Job().apply { complete() } else null
            }
        }
        if (text.isEmpty() || starting || mutable.value.busy) return null
        if (c.androidUse.settings.autoManageUserTasks && c.runtimePolicy.state.value.runInBackground && canHandOff()) {
            val page = sourceOf(text)
            val target = page.target
            if (target != null && reviewCurrent(page) && canReviewScriptTarget(target)) {
                // On-demand tools have no live screen or input until the model declares this turn's scope.
                return run(text, spoken, null, null, phoneTarget = target, managed = true, source = page,
                    autoStart = true, panelActions = true, refersToScreen = refersToScreen)
            }
        }
        return run(text, spoken, null, null, refersToScreen = refersToScreen, panelActions = true)
    }

    /** Hiding the panel revokes its proposals. A callback arriving later cannot bind to a new panel. */
    fun dismissAppHandoff() {
        proposalTurn = null
        pendingApp = null
        newConversationTurn = null
        if (mutable.value.appHandoff != null || mutable.value.phoneReview != null)
            mutable.update { it.copy(appHandoff = null, phoneReview = null) }
    }

    private fun describe(plan: AppHandoffPlan): String {
        val ctx = ContextCompat.getContextForLanguage(context)
        val name = plan.requestedApp
        val query = plan.query.orEmpty()
        return when (plan.status) {
            AppHandoffStatus.READY -> plan.choices.singleOrNull()?.let {
                if (it.action == "map_search") ctx.getString(R.string.app_request_search, it.label, query)
                else ctx.getString(R.string.app_request_open, it.label)
            } ?: if (query.isNotBlank()) ctx.getString(R.string.app_request_choose_map, query)
                else ctx.getString(R.string.app_request_choose_open)
            AppHandoffStatus.APP_NOT_FOUND -> ctx.getString(R.string.app_request_missing, name) +
                if (plan.choices.isEmpty()) "" else " " + ctx.getString(R.string.app_request_alternative, query)
            AppHandoffStatus.OPEN_ONLY -> ctx.getString(R.string.app_request_open_only, plan.choices.firstOrNull()?.label ?: name) +
                if (plan.choices.any { it.action == "map_search" }) " " + ctx.getString(R.string.app_request_alternative, query) else ""
            AppHandoffStatus.AMBIGUOUS -> if (query.isEmpty()) ctx.getString(R.string.app_request_choose_open)
                else ctx.getString(R.string.app_request_choose_map, query)
            AppHandoffStatus.NO_HANDLER -> if (name.isNotBlank()) ctx.getString(R.string.app_request_no_entry, name) +
                if (plan.choices.isNotEmpty()) " " + ctx.getString(R.string.app_request_alternative, query) else ""
                else ctx.getString(R.string.app_request_no_map, query)
        }
    }

    private fun bindAppChoices(sid: String, turn: String, plan: AppHandoffPlan) {
        pendingApp = plan.takeIf { it.choices.isNotEmpty() }?.let { PendingApp(sid, turn, it) }
        mutable.update { it.copy(appHandoff = pendingApp?.plan) }
    }

    private fun canHandOff() = c.runtimePolicy.assistantPanelVisible.value &&
        context.getSystemService(PowerManager::class.java).isInteractive &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    val scriptDrafts get() = c.autoJsScriptDrafts
    fun usesAutoJs() = c.androidUse.settings.autoJsEnabled
    fun canReviewScriptTarget(target: String?): Boolean = !target.isNullOrBlank() &&
        runCatching { PhoneScope.requireApp(target, context.packageName) }.isSuccess

    /** Only the local button for the currently shown app can start the exact reviewed snapshot. */
    fun runReviewedScript(draft: AutoJsScriptDrafts.Draft, target: String) {
        val ctx = ContextCompat.getContextForLanguage(context)
        val epoch = invocation
        fun onTarget() = generation == epoch && canHandOff() && usesAutoJs() &&
            mutable.value.screen.packageName == target && canReviewScriptTarget(target)
        if (starting || mutable.value.busy || !onTarget() || c.androidUse.activeSession.value != null ||
            !scriptDrafts.claim(draft)) {
            showError(ctx.getString(R.string.autojs_trial_stale))
            return
        }
        dismissAppHandoff()
        quiet()
        val app = mutable.value.screen.appName ?: target
        val title = draft.savedName ?: ctx.getString(R.string.autojs_anonymous)
        val request = ctx.getString(R.string.autojs_trial_request, title, app)
        val identity = Any()
        val turn = UUID.randomUUID().toString()
        starting = true
        lastAsked = null
        mutable.update { it.copy(busy = true, error = null) }
        submissionJob = scope.launch {
            var granted: String? = null
            try {
                historyJob?.join()
                if (!onTarget()) return@launch
                check(c.settings.settings.first().runInBackground) { ctx.getString(R.string.assistant_phone_need_background) }
                val sid = session.value ?: createPanelSession(request, null, c.chatModels.observe(null).first())
                check(onTarget() && !c.runManager.isRunning(sid)) { ctx.getString(R.string.autojs_trial_stale) }
                session.value = sid
                mutable.update { it.copy(sessionId = sid) }
                c.sessions.addMessage(sid, ChatMessage(role = Role.USER, text = request), turn)
                check(onTarget() && !c.runManager.isRunning(sid)) { ctx.getString(R.string.autojs_trial_stale) }
                // No model or shell grant. The reviewed program is limited to the one foreground package.
                check(c.androidUse.beginReviewedScript(sid, target, draft.source) {
                    if (phoneGrant === identity) {
                        phoneGrant = null; phoneSession = null
                        onPhoneFinish(sid)
                    }
                }) { ctx.getString(R.string.assistant_phone_busy) }
                granted = sid
                phoneGrant = identity
                phoneSession = sid
                onPhoneStart(sid, false)
                val result = c.androidUse.runAutoJsScript(sid, draft.source, draft.savedName)
                val details = runCatching { Json.parseToJsonElement(result.output).jsonObject }.getOrNull()
                PhonePanel.progress?.takeIf { it.sid == sid }?.trialResult(result.ok,
                    (details?.get("finalScreenChecked") as? JsonPrimitive)?.booleanOrNull == true)
                val completed = details?.get("completedSteps")?.jsonPrimitive?.intOrNull
                val note = if (completed == null) ctx.getString(R.string.autojs_trial_error) else
                    ctx.getString(if (result.ok) R.string.autojs_trial_done else R.string.autojs_trial_stopped,
                        completed, draft.steps.size, app)
                val reason = if (result.ok) null else details?.get("message")?.jsonPrimitive?.contentOrNull
                c.sessions.addMessage(sid, ChatMessage(role = Role.ASSISTANT,
                    text = if (reason.isNullOrBlank()) note else "$note\n${reason.take(300)}"), turn)
            } catch (e: CancellationException) {
                granted?.let { sid -> withContext(NonCancellable) {
                    c.sessions.addMessage(sid, ChatMessage(role = Role.ASSISTANT, text = ctx.getString(R.string.autojs_trial_interrupted)), turn)
                } }
                throw e
            } catch (e: Exception) { if (generation == epoch) showError(e.message ?: ctx.getString(R.string.autojs_trial_error)) }
            finally {
                if (phoneGrant === identity) granted?.let(c.androidUse::end)
                starting = false
                mutable.update { it.copy(busy = session.value?.let(c.runManager::isRunning) == true) }
            }
        }
    }

    /** A UI click supplies the actual choice object; chip labels, model text and old cards confer no authority. */
    fun confirmAppHandoff(plan: AppHandoffPlan, choice: AppHandoffChoice): Boolean {
        val pending = pendingApp?.takeIf { it.plan === plan && it.sessionId == session.value &&
            plan.choices.any { c -> c === choice } && canHandOff() && !mutable.value.busy } ?: return false
        dismissAppHandoff()
        return dispatchAndRecord(pending, choice)
    }

    private fun dispatchAndRecord(pending: PendingApp, choice: AppHandoffChoice): Boolean {
        val plan = pending.plan
        val ctx = ContextCompat.getContextForLanguage(context)
        val sent = try { handoffs.dispatch(plan, choice); true } catch (_: Exception) { false }
        val result = when {
            !sent -> ctx.getString(R.string.app_request_failed, choice.label)
            choice.action == "map_search" -> ctx.getString(R.string.app_request_sent, choice.label, plan.query.orEmpty())
            else -> ctx.getString(R.string.app_request_opened, choice.label)
        }
        saveAppResult(pending, result)
        return sent
    }

    fun cancelAppHandoff(plan: AppHandoffPlan) {
        val pending = pendingApp?.takeIf { it.plan === plan && it.sessionId == session.value } ?: return
        dismissAppHandoff()
        saveAppResult(pending, ContextCompat.getContextForLanguage(context).getString(R.string.app_request_cancelled))
    }

    private fun saveAppResult(pending: PendingApp, text: String) {
        // The app may take the foreground immediately; save the receipt for the original conversation.
        scope.launch { withContext(NonCancellable) {
            c.sessions.addMessage(pending.sessionId, ChatMessage(role = Role.ASSISTANT, text = text), pending.turnId)
        } }
    }

    fun pendingCodePhoneTask() = c.codePhoneHandoff.current()

    private fun sourceOf(text: String): PhoneReview {
        val screen = mutable.value.screen
        val pkg = screen.packageName
        return PhoneReview(ResponseAttachment.visibleText(text), pkg, pkg?.let(pageSourceFor), generation, SystemClock.elapsedRealtime())
    }
    fun reviewCurrent(review: PhoneReview): Boolean {
        val pkg = mutable.value.screen.packageName
        return review.isCurrent(pkg, pkg?.let(pageSourceFor), generation, SystemClock.elapsedRealtime())
    }
    fun discardReview(review: PhoneReview) { if (mutable.value.phoneReview === review) dismissAppHandoff() }

    fun operatePhone(instruction: String, target: String, handoff: CodePhoneHandoff.Draft? = null, managed: Boolean = false,
                     source: PhoneReview? = null): Boolean {
        if (target.isBlank() || target != mutable.value.screen.packageName) return false
        if (!canHandOff() || (source != null && !reviewCurrent(source))) return false
        if (instruction.isBlank() || starting || mutable.value.busy) return false
        if (handoff != null) {
            if (!c.codePhoneHandoff.claim(handoff)) {
                showError(context.getString(R.string.android_use_draft_expired))
                return false
            }
            // Do not attach an earlier assistant conversation to this Code task.
            newQuestion(forgetSavedSession = false)
        }
        return run(instruction.trim(), false, null, null, phoneTarget = target, handoff = handoff, managed = managed,
            source = source) != null
    }

    private fun cancelPhone() {
        submissionJob?.cancel()
        submissionJob = null
        val sid = phoneSession ?: return
        phoneGrant = null
        phoneSession = null
        c.androidUse.end(sid)
        c.runManager.stop(sid)
        onPhoneFinish(sid)
    }

    /**
     * Asks the last question again after an error: a saved question is answered again in
     * place, like Chat's retry, and one that never reached the chat is simply sent again.
     */
    fun retry(): Job? {
        val saved = mutable.value.messages.lastOrNull { it.role == Role.USER && it.id != null }
        if (saved != null && saved.createdAt >= sentAt) return run(saved.text, false, saved, null)
        return lastAsked?.let { run(it, false, null, null) }
    }

    private fun run(text: String, spoken: Boolean, replacement: ChatMessage?, depth: SearchDepth?, refersToScreen: Boolean = false, phoneTarget: String? = null,
                    handoff: CodePhoneHandoff.Draft? = null, panelActions: Boolean = false, managed: Boolean = false,
                    source: PhoneReview? = null, autoStart: Boolean = false): Job? {
        if (text.isEmpty() || starting || mutable.value.busy) return null
        dismissAppHandoff()
        val token = if (panelActions) Any().also { proposalTurn = it } else null
        val origin = source ?: if (panelActions) sourceOf(text) else null
        starting = true
        quiet()
        speakNext = spoken
        runSeen = false
        dismissed = null
        if (replacement == null) lastAsked = text
        sentAt = System.currentTimeMillis() - 1_000
        mutable.update { it.copy(busy = true, error = null, sending = if (replacement == null) ResponseAttachment.visibleText(text) else null) }
        val invocation = generation
        return scope.launch {
            try {
                historyJob?.join()
                if (invocation != generation) return@launch
                val sid = start(text, replacement, depth, refersToScreen, phoneTarget, handoff, token, managed, origin, autoStart)
                mutable.update { it.copy(sessionId = sid) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                speakNext = false
                mutable.update { it.copy(error = e.message ?: context.getString(R.string.assistant_failed), sending = null) }
            } finally {
                starting = false
                mutable.update { it.copy(busy = session.value?.let(c.runManager::isRunning) == true,
                    sending = it.sending?.takeIf { _ -> it.messages.none { row -> row.role == Role.USER && row.createdAt >= sentAt } }) }
            }
        }.also { submissionJob = it }
    }

    private suspend fun createPanelSession(text: String, handoff: CodePhoneHandoff.Draft?, models: ChatModelState): String {
        val epoch = historyEpoch
        return withContext(Dispatchers.IO) {
            val own = ownModel(models)
            val projectId = if (handoff == null) c.workspace.currentProjectOnce().id else {
                val origin = c.sessions.session(handoff.source)
                val project = c.workspace.projectForChat(handoff.source).first()
                check(origin != null && !ConversationConfig.decode(origin.conversationJson).isChat &&
                    project != null && project.id == handoff.project && project.kind != WorkspaceManager.KIND_SSH) {
                    context.getString(R.string.android_use_local_only)
                }
                handoff.project
            }
            c.sessions.createSession(ResponseAttachment.visibleText(text).take(48), projectId, ConversationConfig(ConversationKind.PHONE, phoneOriginCodeId = handoff?.source))
                .also { c.chatModels.initialize(it) }
                .also { id -> if (handoff == null) historyWrites.withLock {
                    if (epoch != historyEpoch) throw CancellationException("The assistant started another conversation.")
                    c.phoneHistory.save(projectId, id)
                } }
                .also { sid ->
                    // With a model per chat, the chat opened from "Continue in app" keeps the assistant's model.
                    if (own != null && !models.settings.sameModelAcrossChats) {
                        val saved = runCatching { ChatModelSelection.decode(c.sessions.session(sid)?.modelSelectionJson) }.getOrNull()
                            ?: ChatModelSelection.from(models.settings, models.providers)
                        c.sessions.saveModelSelection(sid, saved.copy(active = own))
                    }
                }
        }
    }

    /** Uses the normal RunManager history, model settings and compaction for this Phone conversation. */
    private suspend fun start(text: String, replacement: ChatMessage?, depth: SearchDepth?, refersToScreen: Boolean, phoneTarget: String?,
                              handoff: CodePhoneHandoff.Draft?, suggestToken: Any?, managed: Boolean,
                              origin: PhoneReview?, autoStart: Boolean): String {
        val epoch = historyEpoch
        val models = c.chatModels.observe(session.value).first()
        models.error?.let { error(it) }
        val own = ownModel(models)
        // A model picked for the assistant stays put, like a chat with its own model.
        val sameModel = own == null && models.settings.sameModelAcrossChats && !models.agentChat
        val choice = own ?: models.selection.active ?: error(context.getString(R.string.assistant_no_model))
        val provider = models.providers.firstOrNull { it.id == choice.providerId } ?: error(context.getString(R.string.assistant_no_model))
        val model = choice.model.takeIf { it.isNotBlank() } ?: provider.model
        if (!sameModel && provider.id == HarnessProvider.ID && HarnessProvider.sanitize(model, HarnessProvider.customModelIds) != model)
            error(context.getString(R.string.ui_this_chat_s_model_is_no_longer))
        val apiKey = if (provider.id == HarnessProvider.ID) c.providers.harnessApiKey(models.providers)
            else provider.effectiveApiKey(c.providers.apiKey(provider.id))
        check(apiKey.isNotBlank()) { context.getString(R.string.assistant_no_key, provider.name) }

        val sid = session.value ?: createPanelSession(text, handoff, models)
        if (historyEpoch != epoch) throw CancellationException("The assistant started another conversation.")
        session.value = sid
        mutable.update { it.copy(sessionId = sid) }

        // Harness free models sit behind different wires per model; learn the right one once.
        if (provider.id == HarnessProvider.ID && c.providers.wire(model) == null && !HarnessProvider.isPooled(model) &&
            c.providers.customModels(provider.id).any { it.id == model }) {
            HarnessProvider.probeWire(model, c.providers.harnessApiKey())?.let { learned ->
                c.providers.pinWire(model, learned.name)
                HarnessProvider.pins = HarnessProvider.pins + (model to learned.name)
            }
        }
        val config = provider.copy(model = model)
        val resolved = c.providers.resolveHarnessConfig(config)
        check(sameModel || resolved.model == config.model) { context.getString(R.string.ui_this_chat_s_model_is_no_longer) }
        if (resolved.model != config.model) c.settings.setActiveModel(resolved.model)

        val current = mutable.value
        val share = sharedScreenGeneration != generation && replacement == null &&
            (current.screen.hasContent || !current.screenSettled)
        val images = replacement?.images ?: emptyList()
        if (share) {
            sharedScreenGeneration = generation
            val gen = generation
            val ready = scope.async {
                withTimeoutOrNull(SCREEN_WAIT_MS) { state.first { it.screenSettled } }
                mutable.value.screen.takeIf { gen == generation && it.hasContent }
            }
            val shared = SharedScreen {
                // Closing the panel before Android answered cancels the wait; that only means no screen.
                val screen = try { ready.await() } catch (e: CancellationException) { if (!ready.isCancelled) throw e; null }
                screen?.let { shown ->
                    SharedScreen.Content(ScreenText.describe(shown, context.getString(R.string.assistant_screen_context_note)),
                        shown.screenshot?.let { { withContext(Dispatchers.IO) { saveScreenshot(shown) } } },
                        shown.screenshot?.let { bitmap -> { readScreenshot(bitmap) } })
                }
            }
            SharedScreens.put(sid, shared)
        }
        val payload = if (phoneTarget != null || refersToScreen || share) {
            val body = buildString {
                append("Foreground at assistant invocation: ").append(phoneTarget ?: current.screen.appName ?: "unidentified").append(". ")
                if (refersToScreen) append("The person selected a suggestion about this screen. ")
                append("The shared invocation snapshot is static. Use only evidence permitted by the request; ")
                append("live controls and current results require a fresh observation.")
            }
            text + "\n\n" + FileAttachments.inlineBlock("Page at this request.txt", "text/plain",
                FileAttachments.humanBytes(body.toByteArray().size.toLong()), body, null)
        } else text

        val prefs = models.settings
        val local = if (LocalModelCatalog.isLocal(provider.id)) withContext(Dispatchers.IO) {
            c.localModels.limits(provider.id.removePrefix(LocalModelCatalog.PROVIDER_PREFIX))
        } else null
        // Finish resolving the ordinary run inputs before granting any device access.
        val thinking = c.settings.settings.first().assistantThinking
        val workspace = c.workspace.forSession(sid)
        // A rapid dismissal can precede session creation or credential lookup. Still answer the
        // submitted question, but never issue a *new* phone lease from a no-longer-visible page.
        val deviceTurn = phoneTarget != null && c.runtimePolicy.state.value.runInBackground && canHandOff() &&
            mutable.value.screen.packageName == phoneTarget && (origin == null || reviewCurrent(origin)) &&
            (!autoStart || c.androidUse.settings.autoManageUserTasks)
        if (deviceTurn) {
            val identity = Any()
            check(c.androidUse.begin(sid, phoneTarget, managed, ResponseAttachment.visibleText(text), onDemand = autoStart) {
                if (phoneGrant === identity) {
                    phoneGrant = null
                    phoneSession = null
                    onPhoneFinish(sid)
                }
            }) { context.getString(R.string.assistant_phone_busy) }
            phoneGrant = identity
            phoneSession = sid
            onPhoneStart(sid, autoStart)
        }
        val appTool = suggestToken?.let { token ->
            fun currentTurn(): String? = c.runManager.live(sid).value.turnId?.takeIf {
                proposalTurn === token && session.value == sid && canHandOff()
            }
            val propose: (suspend (AppHandoffRequest) -> String?)? = if (deviceTurn) null else ({ request ->
                withContext(Dispatchers.Main.immediate) {
                    val turn = currentTurn() ?: return@withContext null
                    val plan = withContext(Dispatchers.IO) { handoffs.resolve(request) }
                    if (currentTurn() != turn) return@withContext null
                    bindAppChoices(sid, turn, plan)
                    describe(plan)
                }
            })
            val review: (suspend () -> String?)? = if (deviceTurn || origin == null) null else {
                {
                    withContext(Dispatchers.Main.immediate) {
                        if (currentTurn() == null) return@withContext null
                        val page = if (origin.source == null && origin.invocation == generation &&
                            (origin.target == null || origin.target == mutable.value.screen.packageName)) sourceOf(text) else origin
                        if (page.source != null && !reviewCurrent(page)) return@withContext null
                        mutable.update { it.copy(phoneReview = page) }
                        "The user's task is queued for manual review. No phone action has run."
                    }
                }
            }
            SuggestAppActionTool(sid, reviewPhoneTask = review, newConversation = {
                withContext(Dispatchers.Main.immediate) {
                    val turn = currentTurn() ?: return@withContext null
                    newConversationTurn = turn
                    "A fresh panel conversation will open when this response finishes."
                }
            }, propose = propose)
        }
        try {
            return c.runManager.startRun(
                sessionId = sid, text = payload, imageRefs = images, config = config, apiKey = apiKey,
                permissionMode = prefs.permissionMode, mode = AgentMode.ACT,
                maxOutputTokens = local?.output ?: prefs.maxOutputTokens,
                maxContextTokens = local?.context ?: prefs.maxContextTokens,
                thinking = thinking, maxIterations = prefs.maxIterations,
                workspaceOverride = workspace, notifyOnFinish = false,
                notifyWhenHidden = {
                    !c.runtimePolicy.assistantPanelVisible.value && PhonePanel.progress?.let { it.sid == sid && it.active } != true
                },
                replacementMessageId = replacement?.id,
                onlyIfIdle = true,
                allowModelFallback = sameModel,
                searchDepthOverride = depth,
                androidUse = deviceTurn,
                appProposal = appTool,
            )
        } catch (failure: Throwable) {
            if (phoneTarget != null && phoneSession == sid) c.androidUse.end(sid)
            throw failure
        }
    }

    /** Bounded JPEG in the app's image store, so the chat can show it later too. */
    /** The screenshot's text, read on the device, when Read text in images is on. */
    private suspend fun readScreenshot(bitmap: Bitmap): String? {
        if (!c.settings.settings.first().readImageText || bitmap.isRecycled) return null
        return try { c.imageText.read(bitmap) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }

    private fun saveScreenshot(screen: ScreenSnapshot): ImageRef? {
        val given = screen.screenshot ?: return null
        val shot = if (given.config == Bitmap.Config.HARDWARE) given.copy(Bitmap.Config.ARGB_8888, false) ?: return null else given
        val scale = (MAX_EDGE.toFloat() / maxOf(shot.width, shot.height)).coerceAtMost(1f)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(shot, (shot.width * scale).toInt().coerceAtLeast(1),
            (shot.height * scale).toInt().coerceAtLeast(1), true) else shot
        val file = File(c.images.imagesDir, "assistant-screen-${UUID.randomUUID()}.jpg")
        return try {
            file.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
            ImageRef(file.name, "image/jpeg", caption = "Screenshot of ${screen.appName ?: "the screen"}",
                width = scaled.width, height = scaled.height, bytes = file.length(), origin = "assistant_screen")
        } catch (e: Exception) {
            file.delete(); null
        } finally {
            if (scaled !== shot) scaled.recycle()
            if (shot !== given) shot.recycle()
        }
    }

    fun close() {
        dismissAppHandoff()
        session.value?.let(SharedScreens::clear)
        quiet()
        scope.cancel()
    }

    fun panelHidden() {
        dismissAppHandoff()
        speakNext = false
        // Only the snapshot already shared with this unfinished turn can be read offscreen.
        if (!mutable.value.busy) {
            session.value?.let(SharedScreens::clear)
            sharedScreenGeneration = -1
        }
    }

    private companion object {
        const val MAX_EDGE = 1568
        const val SCREEN_WAIT_MS = 4_000L
        const val HOLD_TIMEOUT_MS = 2_000L
    }
}
