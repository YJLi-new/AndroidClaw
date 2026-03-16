package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.runtime.providers.ModelMessage
import ai.androidclaw.runtime.providers.ModelMessageRole

data class ContextWindowDiagnostics(
    val budgetUnits: Int,
    val reservedUnits: Int,
    val availableMessageUnits: Int,
    val usedMessageUnits: Int,
    val selectedMessageCount: Int,
    val droppedMessageCount: Int,
)

data class ContextWindowSelection(
    val messageHistory: List<ModelMessage>,
    val truncated: Boolean,
    val summaryInserted: Boolean,
    val diagnostics: ContextWindowDiagnostics,
)

class ContextWindowManager(
    private val promptBudgetUnits: Int = DEFAULT_PROMPT_BUDGET_UNITS,
) {
    fun select(
        systemPrompt: String,
        persistedHistory: List<ModelMessage>,
        summaryText: String? = null,
    ): ContextWindowSelection {
        if (persistedHistory.isEmpty()) {
            val reservedUnits = estimateTextUnits(systemPrompt)
            return ContextWindowSelection(
                messageHistory = emptyList(),
                truncated = false,
                summaryInserted = false,
                diagnostics = ContextWindowDiagnostics(
                    budgetUnits = promptBudgetUnits,
                    reservedUnits = reservedUnits,
                    availableMessageUnits = availableMessageUnits(reservedUnits),
                    usedMessageUnits = 0,
                    selectedMessageCount = 0,
                    droppedMessageCount = 0,
                ),
            )
        }

        val reservedUnits = estimateTextUnits(systemPrompt)
        val availableMessageUnits = availableMessageUnits(reservedUnits)
        val unitsByIndex = persistedHistory.indices.associateWith { index -> estimateMessageUnits(persistedHistory[index]) }
        val toolClosure = buildToolClosure(persistedHistory)
        val requiredIndices = buildRequiredIndices(persistedHistory)
        val selectedIndices = linkedSetOf<Int>()
        var usedUnits = 0

        for (index in persistedHistory.indices.reversed()) {
            val group = (toolClosure[index] ?: setOf(index)).sorted()
            val additionalIndices = group.filterNot(selectedIndices::contains)
            if (additionalIndices.isEmpty()) {
                continue
            }
            val additionalUnits = additionalIndices.sumOf { unitsByIndex.getValue(it) }
            val shouldForceInclude = additionalIndices.any(requiredIndices::contains)
            if (usedUnits + additionalUnits <= availableMessageUnits || shouldForceInclude) {
                additionalIndices.forEach(selectedIndices::add)
                usedUnits += additionalUnits
            }
        }

        requiredIndices.sorted().forEach { requiredIndex ->
            if (selectedIndices.add(requiredIndex)) {
                usedUnits += unitsByIndex.getValue(requiredIndex)
            }
        }

        val selectedOrderedIndices = selectedIndices.sorted().toMutableList()
        val truncated = selectedOrderedIndices.size < persistedHistory.size
        val selectedMessages = selectedOrderedIndices.map(persistedHistory::get).toMutableList()
        var summaryInserted = false
        if (truncated && !summaryText.isNullOrBlank()) {
            val summaryMessage = ModelMessage(
                role = ModelMessageRole.System,
                content = "Session summary: ${summaryText.trim()}",
            )
            val summaryUnits = estimateMessageUnits(summaryMessage)
            while (usedUnits + summaryUnits > availableMessageUnits) {
                val removableIndexPosition = selectedOrderedIndices.indexOfFirst { index ->
                    index !in requiredIndices
                }
                if (removableIndexPosition == -1) {
                    break
                }
                val removedIndex = selectedOrderedIndices.removeAt(removableIndexPosition)
                selectedMessages.removeAt(removableIndexPosition)
                selectedIndices.remove(removedIndex)
                usedUnits -= unitsByIndex.getValue(removedIndex)
            }
            if (usedUnits + summaryUnits <= availableMessageUnits || selectedMessages.isEmpty()) {
                selectedMessages.add(0, summaryMessage)
                usedUnits += summaryUnits
                summaryInserted = true
            }
        }

        return ContextWindowSelection(
            messageHistory = selectedMessages,
            truncated = truncated,
            summaryInserted = summaryInserted,
            diagnostics = ContextWindowDiagnostics(
                budgetUnits = promptBudgetUnits,
                reservedUnits = reservedUnits,
                availableMessageUnits = availableMessageUnits,
                usedMessageUnits = usedUnits,
                selectedMessageCount = selectedMessages.size,
                droppedMessageCount = persistedHistory.size - selectedIndices.size,
            ),
        )
    }

    internal fun estimateTextUnits(text: String): Int {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return 0
        }
        return BASE_MESSAGE_UNITS + ((normalized.length + CHARACTERS_PER_UNIT - 1) / CHARACTERS_PER_UNIT)
    }

    private fun estimateMessageUnits(message: ModelMessage): Int {
        val structuredUnits = when (message.role) {
            ModelMessageRole.Assistant -> message.toolCalls.size * TOOL_CALL_UNITS
            ModelMessageRole.Tool -> TOOL_RESULT_UNITS
            else -> 0
        }
        return estimateTextUnits(message.content) + structuredUnits
    }

    private fun buildRequiredIndices(history: List<ModelMessage>): Set<Int> {
        val latestUserIndex = history.indexOfLast { it.role == ModelMessageRole.User }
        if (latestUserIndex == -1) {
            return emptySet()
        }
        val required = linkedSetOf(latestUserIndex)
        val latestAssistantBeforeUser = (latestUserIndex - 1 downTo 0).firstOrNull { index ->
            history[index].role == ModelMessageRole.Assistant
        }
        latestAssistantBeforeUser?.let(required::add)
        return required
    }

    private fun buildToolClosure(history: List<ModelMessage>): Map<Int, Set<Int>> {
        val indicesByToolCallId = linkedMapOf<String, MutableSet<Int>>()
        history.forEachIndexed { index, message ->
            message.toolCallId?.let { toolCallId ->
                indicesByToolCallId.getOrPut(toolCallId) { linkedSetOf() }.add(index)
            }
            if (message.role == ModelMessageRole.Assistant) {
                message.toolCalls.forEach { toolCall ->
                    indicesByToolCallId.getOrPut(toolCall.id) { linkedSetOf() }.add(index)
                }
            }
        }

        return buildMap {
            history.forEachIndexed { index, message ->
                val relatedIndices = linkedSetOf<Int>()
                message.toolCallId?.let { toolCallId ->
                    relatedIndices.addAll(indicesByToolCallId[toolCallId].orEmpty())
                }
                if (message.role == ModelMessageRole.Assistant) {
                    message.toolCalls.forEach { toolCall ->
                        relatedIndices.addAll(indicesByToolCallId[toolCall.id].orEmpty())
                    }
                }
                if (relatedIndices.isNotEmpty()) {
                    put(index, relatedIndices)
                }
            }
        }
    }

    private fun availableMessageUnits(reservedUnits: Int): Int {
        return (promptBudgetUnits - reservedUnits).coerceAtLeast(0)
    }

    private companion object {
        const val DEFAULT_PROMPT_BUDGET_UNITS = 12_000
        const val BASE_MESSAGE_UNITS = 8
        const val CHARACTERS_PER_UNIT = 4
        const val TOOL_CALL_UNITS = 24
        const val TOOL_RESULT_UNITS = 16
    }
}
