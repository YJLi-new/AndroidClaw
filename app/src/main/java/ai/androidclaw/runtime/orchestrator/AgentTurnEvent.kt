package ai.androidclaw.runtime.orchestrator

enum class AgentTurnFailureKind {
    Configuration,
    InvalidEndpoint,
    Offline,
    Authentication,
    Network,
    Timeout,
    Server,
    StreamInterrupted,
    Response,
    Runtime,
}

sealed interface AgentTurnEvent {
    data class AssistantTextDelta(
        val text: String,
    ) : AgentTurnEvent

    data class ToolStarted(
        val name: String,
    ) : AgentTurnEvent

    data class ToolFinished(
        val name: String,
        val success: Boolean,
        val summary: String,
    ) : AgentTurnEvent

    data class TurnCompleted(
        val result: AgentTurnResult,
    ) : AgentTurnEvent

    data class TurnFailed(
        val message: String,
        val retryable: Boolean,
        val kind: AgentTurnFailureKind,
    ) : AgentTurnEvent

    data object Cancelled : AgentTurnEvent
}
