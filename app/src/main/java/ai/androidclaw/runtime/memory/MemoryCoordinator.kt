package ai.androidclaw.runtime.memory

import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.repository.MemoryRepository

class MemoryCoordinator(
    private val settingsDataStore: SettingsDataStore,
    private val memoryRepository: MemoryRepository,
) {
    suspend fun loadRelevantMemoryTexts(userMessage: String): List<String> {
        val memorySettings = settingsDataStore.memorySettingsSnapshot()
        if (!memorySettings.enabled) {
            return emptyList()
        }
        return memoryRepository
            .search(
                ownerUserId = memorySettings.installUserId,
                query = userMessage,
                limit = MemoryRepository.DEFAULT_SEARCH_LIMIT,
            ).map { it.text }
    }

    suspend fun captureTurn(
        sessionId: String,
        userMessage: String,
        assistantMessage: String,
        sourceMessageIds: List<String>,
    ) {
        val memorySettings = settingsDataStore.memorySettingsSnapshot()
        if (!memorySettings.enabled) {
            return
        }
        LocalMemoryExtractor
            .extractFacts(
                userMessage = userMessage,
                assistantMessage = assistantMessage,
            ).forEach { fact ->
                memoryRepository.remember(
                    ownerUserId = memorySettings.installUserId,
                    text = fact,
                    sourceSessionId = sessionId,
                    sourceMessageIds = sourceMessageIds,
                    sourceType = MemoryRepository.SOURCE_TYPE_AUTOMATIC,
                )
            }
    }
}

object LocalMemoryExtractor {
    fun extractFacts(
        userMessage: String,
        assistantMessage: String,
    ): List<String> =
        buildList {
            val cleanedUserMessage = cleanup(userMessage)
            explicitRememberFact(cleanedUserMessage)?.let(::add)
            addAll(preferenceFacts(cleanedUserMessage))
            identityFact(cleanedUserMessage)?.let(::add)
            locationFact(cleanedUserMessage)?.let(::add)
            workplaceFact(cleanedUserMessage)?.let(::add)
            addAll(assistantActionFacts(assistantMessage))
        }.map { it.take(MAX_FACT_CHARS).trim() }
            .filter { it.length >= MIN_FACT_CHARS }
            .distinctBy { it.lowercase() }

    private fun explicitRememberFact(text: String): String? {
        val match =
            Regex(
                pattern = """(?i)\b(?:remember that|please remember that|please remember|note that)\s+(.+)""",
            ).find(text) ?: return null
        return "User said to remember: ${match.groupValues[1].trimSentence()}"
    }

    private fun preferenceFacts(text: String): List<String> =
        Regex(
            pattern = """(?i)\b(?:i|we)\s+(prefer|like|love|use|want|need)\s+([^.!?]{3,160})""",
        ).findAll(text)
            .map { match ->
                val subject = if (match.value.trim().startsWith("we ", ignoreCase = true)) "User's team" else "User"
                "$subject ${match.groupValues[1].lowercase()}s ${match.groupValues[2].trimSentence()}"
            }.toList()

    private fun identityFact(text: String): String? {
        val match =
            Regex(
                pattern = """(?i)\bmy name is\s+([^.!?,]{2,80})""",
            ).find(text) ?: return null
        return "User's name is ${match.groupValues[1].trimSentence()}"
    }

    private fun locationFact(text: String): String? {
        val match =
            Regex(
                pattern = """(?i)\bi live in\s+([^.!?]{2,120})""",
            ).find(text) ?: return null
        return "User lives in ${match.groupValues[1].trimSentence()}"
    }

    private fun workplaceFact(text: String): String? {
        val match =
            Regex(
                pattern = """(?i)\bi work (?:at|for|with)\s+([^.!?]{2,120})""",
            ).find(text) ?: return null
        return "User works with ${match.groupValues[1].trimSentence()}"
    }

    private fun assistantActionFacts(text: String): List<String> =
        Regex(
            pattern = """(?i)\b(?:created|updated|deleted|enabled|disabled)\s+(?:the\s+)?(?:task|automation)\b[^.!?]{0,120}""",
        ).findAll(cleanup(text))
            .map { "Assistant ${it.value.trimSentence()}" }
            .toList()

    private fun cleanup(text: String): String =
        text
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(separator = " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun String.trimSentence(): String = trim().trimEnd('.', '!', '?', ',', ';', ':').trim()

    private const val MIN_FACT_CHARS = 10
    private const val MAX_FACT_CHARS = 240
}
