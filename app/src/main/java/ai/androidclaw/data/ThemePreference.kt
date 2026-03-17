package ai.androidclaw.data

enum class ThemePreference(
    val storageValue: String,
    val displayName: String,
) {
    System(
        storageValue = "system",
        displayName = "System",
    ),
    Light(
        storageValue = "light",
        displayName = "Light",
    ),
    Dark(
        storageValue = "dark",
        displayName = "Dark",
    ),
    ;

    companion object {
        fun fromStorage(value: String?): ThemePreference {
            return entries.firstOrNull { it.storageValue == value } ?: System
        }
    }
}
