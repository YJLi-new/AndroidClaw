package ai.androidclaw.ui.navigation

enum class TopLevelDestination(
    val route: String,
    val label: String,
    val glyph: String,
) {
    Chat(route = "chat", label = "Chat", glyph = "C"),
    Tasks(route = "tasks", label = "Tasks", glyph = "T"),
    Skills(route = "skills", label = "Skills", glyph = "S"),
    Settings(route = "settings", label = "Settings", glyph = "G"),
    Health(route = "health", label = "Health", glyph = "H"),
}

