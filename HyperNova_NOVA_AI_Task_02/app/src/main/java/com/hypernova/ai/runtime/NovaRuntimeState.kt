package com.hypernova.ai.runtime

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hypernova.ai.ui.NovaVisibleState
import java.util.Locale

object NovaRuntimeState {
    private const val MAX_MESSAGES = 40

    private val mutableState = MutableLiveData(NovaVisibleState.UNAVAILABLE)
    private val mutableSession = MutableLiveData(NovaRuntimeSnapshot())
    private val mutableConversation = MutableLiveData<List<NovaMessage>>(emptyList())
    private val mutableMuted = MutableLiveData(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    val state: LiveData<NovaVisibleState> = mutableState
    val session: LiveData<NovaRuntimeSnapshot> = mutableSession

    /** The running conversation, oldest first. */
    val conversation: LiveData<List<NovaMessage>> = mutableConversation

    /** Whether NOVA's spoken replies are silenced. */
    val muted: LiveData<Boolean> = mutableMuted

    private var latestSession = NovaRuntimeSnapshot()
    private val conversationLog = mutableListOf<NovaMessage>()

    fun publish(state: NovaVisibleState, followUpWindowMs: Long? = null) {
        dispatch {
            val activeWindow = followUpWindowMs
                ?.takeIf { state == NovaVisibleState.LISTENING && it > 0L }
            mutableState.value = state
            latestSession = latestSession.copy(
                visibleState = state,
                followUpWindowMs = activeWindow,
                followUpDeadlineElapsedRealtimeMs = activeWindow?.let {
                    SystemClock.elapsedRealtime() + it
                },
            )
            mutableSession.value = latestSession
            syncConversation()
        }
    }

    fun publishTranscript(turnId: String?, text: String) = dispatch {
        latestSession = NovaRuntimeSnapshot(
            visibleState = latestSession.visibleState,
            turnId = turnId,
            transcript = text,
        )
        mutableSession.value = latestSession
        syncConversation()
    }

    fun publishProgress(turnId: String?, text: String, routeTier: String?) = dispatch {
        latestSession = latestSession.copy(
            turnId = turnId ?: latestSession.turnId,
            progressText = text,
            routeTier = routeTier ?: latestSession.routeTier,
        )
        mutableSession.value = latestSession
        syncConversation()
    }

    fun publishRoute(turnId: String?, routeTier: String?) = dispatch {
        latestSession = latestSession.copy(
            turnId = turnId ?: latestSession.turnId,
            routeTier = routeTier ?: latestSession.routeTier,
        )
        mutableSession.value = latestSession
        syncConversation()
    }

    fun publishEvidence(turnId: String?, cards: List<NovaEvidenceCard>) = dispatch {
        latestSession = latestSession.copy(
            turnId = turnId ?: latestSession.turnId,
            evidenceCards = cards.take(4),
        )
        mutableSession.value = latestSession
        syncConversation()
    }

    fun publishAction(
        turnId: String?,
        domain: String?,
        name: String?,
        result: String?,
        blocked: Boolean,
        errorMessage: String?,
    ) = dispatch {
        latestSession = latestSession.copy(
            turnId = turnId ?: latestSession.turnId,
            progressText = null,
            actionDomain = domain ?: latestSession.actionDomain,
            actionName = name,
            actionResult = result,
            errorMessage = errorMessage,
            blocked = blocked,
        )
        mutableSession.value = latestSession
        syncConversation()
    }

    fun publishResult(turnId: String?, text: String?, success: Boolean) = dispatch {
        latestSession = latestSession.copy(
            turnId = turnId ?: latestSession.turnId,
            progressText = null,
            actionResult = text,
            errorMessage = if (success) null else text,
            blocked = !success,
        )
        mutableSession.value = latestSession
        syncConversation()
    }

    fun publishResponse(turnId: String?, text: String) = dispatch {
        latestSession = latestSession.copy(
            turnId = turnId ?: latestSession.turnId,
            progressText = null,
            spokenText = text,
        )
        mutableSession.value = latestSession
        syncConversation()
    }

    /** Publish a proactive fault without inheriting stale fields from the preceding driver turn. */
    fun publishVehicleAlert(code: String?, text: String, active: Boolean) = dispatch {
        latestSession = NovaRuntimeSnapshot(
            visibleState = latestSession.visibleState,
            actionDomain = "vehicle",
            actionName = code ?: "fault",
            actionResult = text,
            // Once cleared, preserve the confirmation as the last response. While active, the
            // detailed error remains authoritative even after its shorter TTS warning finishes.
            spokenText = text.takeUnless { active },
            errorMessage = text.takeIf { active },
            blocked = active,
        )
        mutableSession.value = latestSession
        syncConversation()
    }

    fun publishError(turnId: String?, message: String) = dispatch {
        latestSession = latestSession.copy(
            turnId = turnId ?: latestSession.turnId,
            progressText = null,
            errorMessage = message,
            blocked = true,
        )
        mutableSession.value = latestSession
        syncConversation()
    }

    /** Silence or restore NOVA's spoken replies. Owned by the runtime service. */
    fun publishMuted(muted: Boolean) = dispatch {
        if (mutableMuted.value != muted) mutableMuted.value = muted
    }

    /** Drop the running conversation, for example when the driver cancels and starts over. */
    fun clearConversation() = dispatch {
        if (conversationLog.isNotEmpty()) {
            conversationLog.clear()
            mutableConversation.value = emptyList()
        }
    }

    /**
     * Fold the latest snapshot into the conversation.
     *
     * The control channel reports a turn many times over (transcript, progress, action, result,
     * spoken reply), so each side of a turn is written under a stable key and updated in place.
     * Appending on every report would fill the screen with near-duplicates of one exchange, and a
     * fault the gateway re-sends would print once per repeat.
     */
    private fun syncConversation() {
        val session = latestSession
        // A proactive alert carries no turn of its own; its fault code identifies it instead.
        val turnKey = session.turnId
            ?: session.actionName?.let { "alert:$it" }
            ?: "current"

        var changed = false

        session.transcript?.takeIf { it.isNotBlank() }?.let { spoken ->
            changed = upsert(
                NovaMessage(
                    key = "$turnKey:driver",
                    role = NovaMessageRole.DRIVER,
                    text = spoken,
                ),
            ) || changed
        }

        // Keep a blocked/fault entry authoritative and visually stable while its shorter spoken
        // warning is playing. Normal successful turns still prefer exactly what the driver heard.
        val reply = if (session.blocked) {
            session.errorMessage
                ?: session.spokenText
                ?: session.actionResult
                ?: session.progressText
        } else {
            session.spokenText
                ?: session.errorMessage
                ?: session.actionResult
                ?: session.progressText
        }
        if (!reply.isNullOrBlank()) {
            val tone = when {
                session.blocked || session.visibleState == NovaVisibleState.ERROR ->
                    NovaMessageTone.ERROR
                session.visibleState == NovaVisibleState.SUCCESS -> NovaMessageTone.SUCCESS
                else -> NovaMessageTone.NEUTRAL
            }
            changed = upsert(
                NovaMessage(
                    key = "$turnKey:nova",
                    role = NovaMessageRole.NOVA,
                    text = reply,
                    tone = tone,
                    pending = session.visibleState == NovaVisibleState.PROCESSING ||
                        session.visibleState == NovaVisibleState.EXECUTING,
                    action = actionCard(session, reply),
                    evidence = session.evidenceCards,
                ),
            ) || changed
        }

        if (!changed) return
        while (conversationLog.size > MAX_MESSAGES) conversationLog.removeAt(0)
        mutableConversation.value = conversationLog.toList()
    }

    /** Returns true when the log actually changed, so an unchanged repeat publishes nothing. */
    private fun upsert(message: NovaMessage): Boolean {
        val index = conversationLog.indexOfLast { it.key == message.key }
        if (index < 0) {
            conversationLog.add(message)
            return true
        }
        if (conversationLog[index] == message) return false
        conversationLog[index] = message
        return true
    }

    /**
     * The structured summary beside a reply. It is skipped when the reply text is already the
     * action result, because then the card would only repeat the sentence above it.
     */
    private fun actionCard(session: NovaRuntimeSnapshot, reply: String): NovaActionCard? {
        val domain = session.actionDomain?.takeIf { it.isNotBlank() } ?: return null
        val result = session.actionResult?.takeIf { it.isNotBlank() } ?: return null
        if (result == reply) return null
        return NovaActionCard(
            domain = domain,
            label = domain.uppercase(Locale.ROOT),
            title = result,
        )
    }

    private fun dispatch(update: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update()
        } else {
            // LiveData.postValue coalesces rapid events. Queue every update explicitly so the
            // transcript, action, result and visible state remain ordered.
            mainHandler.post(update)
        }
    }
}
