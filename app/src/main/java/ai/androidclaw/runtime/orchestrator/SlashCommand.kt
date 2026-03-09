package ai.androidclaw.runtime.orchestrator

data class SlashCommand(
    val name: String,
    val arguments: String,
) {
    companion object {
        fun parse(text: String): SlashCommand? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("/")) return null
            val spaceIndex = trimmed.indexOf(' ')
            return if (spaceIndex == -1) {
                SlashCommand(name = trimmed.removePrefix("/"), arguments = "")
            } else {
                SlashCommand(
                    name = trimmed.substring(1, spaceIndex),
                    arguments = trimmed.substring(spaceIndex + 1).trim(),
                )
            }
        }
    }
}
