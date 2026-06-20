package ai.androidclaw.runtime.tools

import ai.androidclaw.data.MAX_PROVIDER_TIMEOUT_SECONDS
import ai.androidclaw.data.MIN_PROVIDER_TIMEOUT_SECONDS
import ai.androidclaw.data.ProviderAuthMode
import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.firstProviderEndpointPolicyError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant

internal fun ProviderType.toProviderPayload(settings: ProviderSettingsSnapshot): JsonObject =
    buildJsonObject {
        put("storageValue", storageValue)
        put("providerId", providerId)
        put("displayName", displayName)
        put("protocolFamily", protocolFamily.name)
        put("authMode", authMode.name)
        put("requiresRemoteSettings", requiresRemoteSettings)
        put("requiresApiKey", requiresApiKey)
        put("usesOpenAiCodexOAuth", usesOpenAiCodexOAuth)
        put("selected", settings.providerType == this@toProviderPayload)
        put(
            "endpointSettings",
            if (requiresRemoteSettings) {
                val endpointSettings = settings.endpointSettings(this@toProviderPayload)
                buildJsonObject {
                    put("baseUrl", endpointSettings.baseUrl)
                    put("modelId", endpointSettings.modelId)
                    put("timeoutSeconds", endpointSettings.timeoutSeconds)
                }
            } else {
                JsonNull
            },
        )
    }

internal fun ProviderType.toProviderCatalogPayload(settings: ProviderSettingsSnapshot): JsonObject {
    val currentEndpointSettings = if (requiresRemoteSettings) settings.endpointSettings(this) else null
    val defaultEndpointSettings = if (requiresRemoteSettings) defaultEndpointSettings() else null
    return buildJsonObject {
        put("storageValue", storageValue)
        put("providerId", providerId)
        put("displayName", displayName)
        put("protocolFamily", protocolFamily.name)
        put("authMode", authMode.name)
        put("selected", settings.providerType == this@toProviderCatalogPayload)
        put("requiresCredential", requiresApiKey || usesOpenAiCodexOAuth)
        put("requiresRemoteSettings", requiresRemoteSettings)
        put("requiresApiKey", requiresApiKey)
        put("usesOpenAiCodexOAuth", usesOpenAiCodexOAuth)
        put("secretValuesIncluded", false)
        put("oauthTokenValuesIncluded", false)
        put(
            "endpointCatalog",
            if (currentEndpointSettings != null && defaultEndpointSettings != null) {
                buildJsonObject {
                    put("defaultBaseUrl", defaultEndpointSettings.baseUrl)
                    put("defaultModelId", defaultEndpointSettings.modelId)
                    put("defaultTimeoutSeconds", defaultEndpointSettings.timeoutSeconds)
                    put("currentBaseUrl", currentEndpointSettings.baseUrl)
                    put("currentModelId", currentEndpointSettings.modelId)
                    put("currentTimeoutSeconds", currentEndpointSettings.timeoutSeconds)
                    put("usesDefaultBaseUrl", currentEndpointSettings.baseUrl == defaultEndpointSettings.baseUrl)
                    put("usesDefaultModelId", currentEndpointSettings.modelId == defaultEndpointSettings.modelId)
                    put(
                        "usesDefaultTimeoutSeconds",
                        currentEndpointSettings.timeoutSeconds == defaultEndpointSettings.timeoutSeconds,
                    )
                    put("usesDefaultTimeout", currentEndpointSettings.timeoutSeconds == defaultEndpointSettings.timeoutSeconds)
                    put("customBaseUrl", currentEndpointSettings.baseUrl != defaultEndpointSettings.baseUrl)
                    put("customModelId", currentEndpointSettings.modelId != defaultEndpointSettings.modelId)
                    put("customTimeout", currentEndpointSettings.timeoutSeconds != defaultEndpointSettings.timeoutSeconds)
                    put("baseUrlConfigured", currentEndpointSettings.baseUrl.isNotBlank())
                    put("modelIdConfigured", currentEndpointSettings.modelId.isNotBlank())
                    put(
                        "timeoutValid",
                        currentEndpointSettings.timeoutSeconds in MIN_PROVIDER_TIMEOUT_SECONDS..MAX_PROVIDER_TIMEOUT_SECONDS,
                    )
                }
            } else {
                JsonNull
            },
        )
    }
}

internal fun List<ProviderType>.toProviderCatalogMarkdown(
    settings: ProviderSettingsSnapshot,
    requestedProviderId: String?,
): String {
    val includedProviders = this
    return buildString {
        appendLine("# Provider catalog")
        appendLine()
        appendLine("- Current provider: `${settings.providerType.providerId}` (${settings.providerType.displayName.toHandoffLine()})")
        appendLine("- Requested provider filter: ${requestedProviderId?.toHandoffLine() ?: "none"}")
        appendLine("- Providers included: ${includedProviders.size}")
        appendLine("- Secret values included: false")
        appendLine("- OAuth token values included: false")
        appendLine()
        appendLine("## Providers")
        if (includedProviders.isEmpty()) {
            appendLine("_No providers included._")
        } else {
            includedProviders.forEach { providerType ->
                appendLine(providerType.toProviderCatalogMarkdownLine(settings = settings))
            }
        }
    }
}

internal fun ProviderType.toProviderCatalogMarkdownLine(settings: ProviderSettingsSnapshot): String =
    buildString {
        append("- `")
        append(displayName.toHandoffLine())
        append("` id=`")
        append(providerId)
        append("` selected=")
        append(settings.providerType == this@toProviderCatalogMarkdownLine)
        append(" mode=")
        append(authMode.name)
        append(" remote=")
        append(requiresRemoteSettings)
        if (requiresRemoteSettings) {
            val currentEndpointSettings = settings.endpointSettings(this@toProviderCatalogMarkdownLine)
            val defaultEndpointSettings = defaultEndpointSettings()
            append(" currentEndpoint=`")
            append(currentEndpointSettings.baseUrl.toHandoffLine())
            append("` defaultEndpoint=`")
            append(defaultEndpointSettings.baseUrl.toHandoffLine())
            append("` currentModel=`")
            append(currentEndpointSettings.modelId.toHandoffLine())
            append("` defaultModel=`")
            append(defaultEndpointSettings.modelId.toHandoffLine())
            append("` currentTimeoutSeconds=")
            append(currentEndpointSettings.timeoutSeconds)
            append(" defaultTimeoutSeconds=")
            append(defaultEndpointSettings.timeoutSeconds)
            append(" customEndpoint=")
            append(currentEndpointSettings.baseUrl != defaultEndpointSettings.baseUrl)
            append(" customModel=")
            append(currentEndpointSettings.modelId != defaultEndpointSettings.modelId)
        }
    }

internal fun ProviderType.toProviderConfigureExamplePayload(
    settings: ProviderSettingsSnapshot,
    requestedProviderId: String?,
    includeMarkdown: Boolean,
    exampleMarkdown: String?,
): JsonObject {
    val currentEndpointSettings = if (requiresRemoteSettings) settings.endpointSettings(this) else null
    val defaultEndpointSettings = if (requiresRemoteSettings) defaultEndpointSettings() else null
    return buildJsonObject {
        put("requestedProviderId", requestedProviderId?.let(::JsonPrimitive) ?: JsonNull)
        put("providerId", providerId)
        put("storageValue", storageValue)
        put("displayName", displayName)
        put("selected", settings.providerType == this@toProviderConfigureExamplePayload)
        put("protocolFamily", protocolFamily.name)
        put("authMode", authMode.name)
        put("requiresRemoteSettings", requiresRemoteSettings)
        put("configurable", requiresRemoteSettings)
        put("requiresCredential", requiresApiKey || usesOpenAiCodexOAuth)
        put("requiresApiKey", requiresApiKey)
        put("usesOpenAiCodexOAuth", usesOpenAiCodexOAuth)
        put("exampleOnly", true)
        put("executesConfiguration", false)
        put("secretValuesIncluded", false)
        put("apiKeyValuesIncluded", false)
        put("oauthTokenValuesIncluded", false)
        put("credentialValuesIncluded", false)
        put("includeMarkdown", includeMarkdown)
        put("currentEndpointSettings", currentEndpointSettings?.toProviderEndpointSettingsPayload() ?: JsonNull)
        put("defaultEndpointSettings", defaultEndpointSettings?.toProviderEndpointSettingsPayload() ?: JsonNull)
        put(
            "exampleArguments",
            defaultEndpointSettings?.let { endpointSettings ->
                buildJsonObject {
                    put("providerId", providerId)
                    put("baseUrl", endpointSettings.baseUrl)
                    put("modelId", endpointSettings.modelId)
                    put("timeoutSeconds", endpointSettings.timeoutSeconds)
                }
            } ?: JsonNull,
        )
        put(
            "suggestedTools",
            buildJsonArray {
                if (requiresRemoteSettings) {
                    add(JsonPrimitive("providers.configure"))
                } else {
                    add(JsonPrimitive("providers.select"))
                }
                if (requiresApiKey || usesOpenAiCodexOAuth) {
                    add(JsonPrimitive("providers.auth.status"))
                }
                add(JsonPrimitive("providers.catalog"))
            },
        )
        put("exampleMarkdown", exampleMarkdown?.let(::JsonPrimitive) ?: JsonNull)
    }
}

internal fun ProviderEndpointSettings.toProviderEndpointSettingsPayload(): JsonObject =
    buildJsonObject {
        put("baseUrl", baseUrl)
        put("modelId", modelId)
        put("timeoutSeconds", timeoutSeconds)
    }

internal fun ProviderType.toProviderConfigureExampleMarkdown(settings: ProviderSettingsSnapshot): String =
    buildString {
        appendLine("# Provider configure example")
        appendLine()
        appendLine("- Provider: `$providerId` (${displayName.toHandoffLine()})")
        appendLine("- Selected: ${settings.providerType == this@toProviderConfigureExampleMarkdown}")
        appendLine("- Configurable: $requiresRemoteSettings")
        appendLine("- Auth mode: ${authMode.name}")
        appendLine("- Example only: true")
        appendLine("- Executes configuration: false")
        appendLine("- Secret values included: false")
        appendLine("- API key values included: false")
        appendLine("- OAuth token values included: false")
        if (requiresRemoteSettings) {
            val defaultEndpointSettings = defaultEndpointSettings()
            appendLine()
            appendLine("```json")
            appendLine(
                buildJsonObject {
                    put("providerId", providerId)
                    put("baseUrl", defaultEndpointSettings.baseUrl)
                    put("modelId", defaultEndpointSettings.modelId)
                    put("timeoutSeconds", defaultEndpointSettings.timeoutSeconds)
                }.toString(),
            )
            appendLine("```")
        } else {
            appendLine()
            appendLine("This provider is local/offline and has no remote endpoint settings.")
        }
    }

internal fun ProviderType.toProviderExportPayload(
    settings: ProviderSettingsSnapshot,
    includeDefaults: Boolean,
): JsonObject {
    val currentEndpointSettings = if (requiresRemoteSettings) settings.endpointSettings(this) else null
    val comparisonEndpointSettings = if (requiresRemoteSettings) defaultEndpointSettings() else null
    val defaultEndpointSettings = if (includeDefaults) comparisonEndpointSettings else null
    return buildJsonObject {
        put("storageValue", storageValue)
        put("providerId", providerId)
        put("displayName", displayName)
        put("protocolFamily", protocolFamily.name)
        put("authMode", authMode.name)
        put("selected", settings.providerType == this@toProviderExportPayload)
        put("requiresCredential", requiresApiKey || usesOpenAiCodexOAuth)
        put("requiresRemoteSettings", requiresRemoteSettings)
        put("requiresApiKey", requiresApiKey)
        put("usesOpenAiCodexOAuth", usesOpenAiCodexOAuth)
        put("secretValuesIncluded", false)
        put("apiKeyValuesIncluded", false)
        put("oauthTokenValuesIncluded", false)
        put("credentialValuesIncluded", false)
        put("authStateIncluded", false)
        put(
            "endpointSettings",
            if (currentEndpointSettings != null) {
                buildJsonObject {
                    put("baseUrl", currentEndpointSettings.baseUrl)
                    put("modelId", currentEndpointSettings.modelId)
                    put("timeoutSeconds", currentEndpointSettings.timeoutSeconds)
                    put("customBaseUrl", currentEndpointSettings.baseUrl != comparisonEndpointSettings?.baseUrl)
                    put("customModelId", currentEndpointSettings.modelId != comparisonEndpointSettings?.modelId)
                    put("customTimeout", currentEndpointSettings.timeoutSeconds != comparisonEndpointSettings?.timeoutSeconds)
                }
            } else {
                JsonNull
            },
        )
        put(
            "defaultEndpointSettings",
            if (defaultEndpointSettings != null) {
                buildJsonObject {
                    put("baseUrl", defaultEndpointSettings.baseUrl)
                    put("modelId", defaultEndpointSettings.modelId)
                    put("timeoutSeconds", defaultEndpointSettings.timeoutSeconds)
                }
            } else {
                JsonNull
            },
        )
    }
}

internal fun List<ProviderType>.toProviderExportMarkdown(
    settings: ProviderSettingsSnapshot,
    requestedProviderId: String?,
    includeDefaults: Boolean,
): String {
    val includedProviders = this
    return buildString {
        appendLine("# Provider settings export")
        appendLine()
        appendLine("- Format: $PROVIDER_EXPORT_FORMAT")
        appendLine("- Version: $PROVIDER_EXPORT_VERSION")
        appendLine("- Current provider: `${settings.providerType.providerId}` (${settings.providerType.displayName.toHandoffLine()})")
        appendLine("- Requested provider filter: ${requestedProviderId?.toHandoffLine() ?: "none"}")
        appendLine("- Providers included: ${includedProviders.size}")
        appendLine("- Defaults included: $includeDefaults")
        appendLine("- Secret values included: false")
        appendLine("- OAuth token values included: false")
        appendLine()
        appendLine("## Providers")
        if (includedProviders.isEmpty()) {
            appendLine("_No providers included._")
        } else {
            includedProviders.forEach { providerType ->
                appendLine(providerType.toProviderExportMarkdownLine(settings = settings, includeDefaults = includeDefaults))
            }
        }
    }
}

internal fun ProviderType.toProviderExportMarkdownLine(
    settings: ProviderSettingsSnapshot,
    includeDefaults: Boolean,
): String =
    buildString {
        append("- `")
        append(displayName.toHandoffLine())
        append("` id=`")
        append(providerId)
        append("` selected=")
        append(settings.providerType == this@toProviderExportMarkdownLine)
        append(" mode=")
        append(authMode.name)
        append(" remote=")
        append(requiresRemoteSettings)
        if (requiresRemoteSettings) {
            val endpointSettings = settings.endpointSettings(this@toProviderExportMarkdownLine)
            append(" endpoint=`")
            append(endpointSettings.baseUrl.toHandoffLine())
            append("` model=`")
            append(endpointSettings.modelId.toHandoffLine())
            append("` timeoutSeconds=")
            append(endpointSettings.timeoutSeconds)
            if (includeDefaults) {
                val defaultEndpointSettings = defaultEndpointSettings()
                append(" defaultEndpoint=`")
                append(defaultEndpointSettings.baseUrl.toHandoffLine())
                append("` defaultModel=`")
                append(defaultEndpointSettings.modelId.toHandoffLine())
                append("` defaultTimeoutSeconds=")
                append(defaultEndpointSettings.timeoutSeconds)
            }
        }
    }

internal fun JsonObject.providerImportEntries(): ProviderImportEntriesParseResult {
    val directEntries = this["providers"]
    val exportEntries = (this["export"] as? JsonObject)?.get("providers")
    val payloadEntries = (this["payload"] as? JsonObject)?.get("providers")
    val entries =
        directEntries ?: exportEntries ?: payloadEntries ?: return ProviderImportEntriesParseResult.Failure(
            missingProviderImportEntriesResult(),
        )
    return (entries as? JsonArray)?.let(ProviderImportEntriesParseResult::Success)
        ?: ProviderImportEntriesParseResult.Failure(invalidProviderImportEntriesResult())
}

internal fun JsonObject.providerImportCurrentProvider(): ProviderType? {
    val sourceObject =
        (this["export"] as? JsonObject)
            ?: (this["payload"] as? JsonObject)
            ?: this
    val identifier =
        sourceObject.optionalText("currentProviderId")
            ?: sourceObject.optionalText("currentProviderStorageValue")
            ?: sourceObject.optionalText("currentProviderDisplayName")
            ?: return null
    return ProviderType.entries.firstOrNull { providerType ->
        providerType.matchesProviderIdentifier(identifier)
    }
}

internal fun JsonElement.toProviderImportCandidate(sourceIndex: Int): ProviderImportCandidateParseResult {
    val objectValue =
        this as? JsonObject ?: return providerImportSkipped(
            sourceIndex = sourceIndex,
            code = "providers.import.invalid_entry",
            summary = "Import entry must be a provider object.",
        )
    val providerIdentifier =
        objectValue.optionalText("providerId")
            ?: objectValue.optionalText("storageValue")
            ?: objectValue.optionalText("id")
            ?: objectValue.optionalText("displayName")
            ?: objectValue.optionalText("name")
            ?: return providerImportSkipped(
                sourceIndex = sourceIndex,
                code = "providers.import.invalid_missing_provider",
                summary = "Import entry skipped because provider id is missing.",
            )
    val providerType =
        ProviderType.entries.firstOrNull { providerType ->
            providerType.matchesProviderIdentifier(providerIdentifier)
        } ?: return providerImportSkipped(
            sourceIndex = sourceIndex,
            code = "providers.import.invalid_unknown_provider",
            summary = "Import entry skipped because provider $providerIdentifier is unknown.",
        )
    val endpointSettings =
        if (providerType.requiresRemoteSettings) {
            val endpointObject =
                objectValue["endpointSettings"] as? JsonObject ?: return providerImportSkipped(
                    sourceIndex = sourceIndex,
                    code = "providers.import.invalid_missing_endpoint",
                    summary = "Import entry skipped because remote provider endpointSettings are missing.",
                )
            val timeoutText = endpointObject.optionalText("timeoutSeconds")
            val timeoutSeconds =
                if (timeoutText == null) {
                    providerType.defaultTimeoutSeconds
                } else {
                    timeoutText.toIntOrNull()
                        ?: return providerImportSkipped(
                            sourceIndex = sourceIndex,
                            code = "providers.import.invalid_timeout",
                            summary = "Import entry skipped because endpoint timeoutSeconds is not numeric.",
                        )
                }
            ProviderEndpointSettings(
                baseUrl = endpointObject.optionalRawText("baseUrl") ?: providerType.defaultBaseUrl,
                modelId = endpointObject.optionalRawText("modelId") ?: providerType.defaultModelId,
                timeoutSeconds = timeoutSeconds,
            )
        } else {
            null
        }
    return ProviderImportCandidateParseResult.Candidate(
        ProviderImportCandidate(
            sourceIndex = sourceIndex,
            providerType = providerType,
            sourceProviderId = providerIdentifier,
            sourceSelected = objectValue.optionalBoolean("selected", defaultValue = false),
            endpointSettings = endpointSettings,
        ),
    )
}

internal fun providerImportSkipped(
    sourceIndex: Int,
    code: String,
    summary: String,
): ProviderImportCandidateParseResult.Skipped =
    ProviderImportCandidateParseResult.Skipped(
        ProviderImportSkippedEntry(
            sourceIndex = sourceIndex,
            code = code,
            summary = summary,
        ),
    )

internal fun missingProviderImportConfirmationResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Pass confirm=CONFIRM to import provider settings, or dryRun=true to preview without writing.",
        errorCode = "MISSING_PROVIDER_IMPORT_CONFIRMATION",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_PROVIDER_IMPORT_CONFIRMATION")
                put("field", "confirm")
            },
    )

internal fun missingProviderImportEntriesResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Provide a providers array or an export object containing providers to import.",
        errorCode = "MISSING_PROVIDER_IMPORT_ENTRIES",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_PROVIDER_IMPORT_ENTRIES")
                put("field", "providers")
            },
    )

internal fun invalidProviderImportEntriesResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Provider import entries must be an array.",
        errorCode = "INVALID_PROVIDER_IMPORT_ENTRIES",
        payload =
            buildJsonObject {
                put("errorCode", "INVALID_PROVIDER_IMPORT_ENTRIES")
                put("field", "providers")
            },
    )

internal fun ProviderImportCandidate.toProviderImportCandidatePayload(): JsonObject =
    buildJsonObject {
        put("sourceIndex", sourceIndex)
        put("sourceProviderId", sourceProviderId?.let(::JsonPrimitive) ?: JsonNull)
        put("storageValue", providerType.storageValue)
        put("providerId", providerType.providerId)
        put("displayName", providerType.displayName)
        put("protocolFamily", providerType.protocolFamily.name)
        put("authMode", providerType.authMode.name)
        put("sourceSelected", sourceSelected)
        put("requiresRemoteSettings", providerType.requiresRemoteSettings)
        put("requiresApiKey", providerType.requiresApiKey)
        put("usesOpenAiCodexOAuth", providerType.usesOpenAiCodexOAuth)
        put("secretValuesImported", false)
        put("apiKeyValuesImported", false)
        put("oauthTokenValuesImported", false)
        put("credentialValuesImported", false)
        put("authStateImported", false)
        put("secretValuesIncluded", false)
        put("oauthTokenValuesIncluded", false)
        put("endpointImportable", endpointSettings != null)
        put("endpointSettings", endpointSettings.toProviderEndpointPayload())
    }

internal fun ProviderImportCandidate.toProviderImportedPayload(settings: ProviderSettingsSnapshot): JsonObject =
    buildJsonObject {
        put("sourceIndex", sourceIndex)
        put("sourceProviderId", sourceProviderId?.let(::JsonPrimitive) ?: JsonNull)
        put("storageValue", providerType.storageValue)
        put("providerId", providerType.providerId)
        put("displayName", providerType.displayName)
        put("selected", settings.providerType == providerType)
        put("endpointImported", endpointSettings != null)
        put("endpointSettings", if (providerType.requiresRemoteSettings) settings.endpointSettings(providerType).toProviderEndpointPayload() else JsonNull)
        put("secretValuesImported", false)
        put("apiKeyValuesImported", false)
        put("oauthTokenValuesImported", false)
        put("credentialValuesImported", false)
        put("authStateImported", false)
        put("secretValuesIncluded", false)
        put("oauthTokenValuesIncluded", false)
    }

internal fun ProviderEndpointSettings?.toProviderEndpointPayload(): JsonElement =
    this?.let { settings ->
        buildJsonObject {
            put("baseUrl", settings.baseUrl)
            put("modelId", settings.modelId)
            put("timeoutSeconds", settings.timeoutSeconds)
        }
    } ?: JsonNull

internal fun ProviderImportSkippedEntry.toProviderImportSkippedPayload(): JsonObject =
    buildJsonObject {
        put("sourceIndex", sourceIndex)
        put("code", code)
        put("summary", summary)
    }

internal enum class ProviderCredentialClearTarget(
    val storageValue: String,
    val clearsApiKey: Boolean,
    val clearsOAuth: Boolean,
) {
    All(
        storageValue = "all",
        clearsApiKey = true,
        clearsOAuth = true,
    ),
    ApiKey(
        storageValue = "api_key",
        clearsApiKey = true,
        clearsOAuth = false,
    ),
    OAuth(
        storageValue = "oauth",
        clearsApiKey = false,
        clearsOAuth = true,
    ),
}

internal fun JsonObject.optionalProviderCredentialClearTarget(): ProviderCredentialClearTarget {
    val value =
        optionalText("credentialType")
            ?: optionalText("type")
            ?: optionalText("credential")
            ?: return ProviderCredentialClearTarget.All
    val normalizedValue =
        value
            .lowercase()
            .replace('-', '_')
            .replace(' ', '_')
    return when (normalizedValue) {
        "all",
        "auth",
        "credential",
        "credentials",
        "secret",
        "secrets",
        -> ProviderCredentialClearTarget.All
        "api",
        "api_key",
        "apikey",
        "key",
        -> ProviderCredentialClearTarget.ApiKey
        "oauth",
        "oauth_credential",
        "oauth_credentials",
        "oauth_token",
        "oauth_tokens",
        "openai_codex_oauth",
        "token",
        "tokens",
        -> ProviderCredentialClearTarget.OAuth
        else -> throw IllegalArgumentException("providers.auth.clear received an unsupported credentialType.")
    }
}

internal fun providerAuthClearUnsupportedTypeFailure(
    providerType: ProviderType,
    credentialType: String,
): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Provider ${providerType.displayName} does not use $credentialType credentials.",
        errorCode = "PROVIDER_AUTH_TYPE_UNSUPPORTED",
        payload =
            buildJsonObject {
                put("errorCode", "PROVIDER_AUTH_TYPE_UNSUPPORTED")
                put("toolName", "providers.auth.clear")
                put("providerId", providerType.providerId)
                put("credentialType", credentialType)
                put("authMode", providerType.authMode.name)
                put("requiresApiKey", providerType.requiresApiKey)
                put("usesOpenAiCodexOAuth", providerType.usesOpenAiCodexOAuth)
                put("secretValuesIncluded", false)
                put("oauthTokenValuesIncluded", false)
            },
    )

internal data class ProviderAuthState(
    val providerType: ProviderType,
    val status: String,
    val apiKeyConfigured: Boolean?,
    val oauthConfigured: Boolean?,
    val oauthExpired: Boolean?,
    val oauthProfileConfigured: Boolean?,
)

internal fun ProviderType.toProviderAuthExamplePayload(
    settings: ProviderSettingsSnapshot,
    authState: ProviderAuthState,
    requestedProviderId: String?,
    includeMarkdown: Boolean,
    exampleMarkdown: String?,
    secretStatusAvailable: Boolean,
): JsonObject {
    val credentialType = providerAuthExampleCredentialType()
    return buildJsonObject {
        put("requestedProviderId", requestedProviderId?.let(::JsonPrimitive) ?: JsonNull)
        put("providerId", providerId)
        put("storageValue", storageValue)
        put("displayName", displayName)
        put("selected", settings.providerType == this@toProviderAuthExamplePayload)
        put("protocolFamily", protocolFamily.name)
        put("authMode", authMode.name)
        put("requiresCredential", requiresApiKey || usesOpenAiCodexOAuth)
        put("requiresApiKey", requiresApiKey)
        put("usesOpenAiCodexOAuth", usesOpenAiCodexOAuth)
        put("credentialType", credentialType)
        put("secretStatusAvailable", secretStatusAvailable)
        put("authStatus", authState.status)
        put("configured", authState.configuredForProviderAuthExample()?.let(::JsonPrimitive) ?: JsonNull)
        put("apiKeyConfigured", authState.apiKeyConfigured?.let(::JsonPrimitive) ?: JsonNull)
        put("oauthConfigured", authState.oauthConfigured?.let(::JsonPrimitive) ?: JsonNull)
        put("oauthExpired", authState.oauthExpired?.let(::JsonPrimitive) ?: JsonNull)
        put("oauthProfileConfigured", authState.oauthProfileConfigured?.let(::JsonPrimitive) ?: JsonNull)
        put("exampleOnly", true)
        put("executesAuthentication", false)
        put("writesCredential", false)
        put("secretInputAccepted", false)
        put("secretValuesIncluded", false)
        put("apiKeyValuesIncluded", false)
        put("oauthTokenValuesIncluded", false)
        put("credentialValuesIncluded", false)
        put("includeMarkdown", includeMarkdown)
        put("statusExampleArguments", buildJsonObject { put("providerId", providerId) })
        put(
            "clearExampleArguments",
            if (requiresApiKey || usesOpenAiCodexOAuth) {
                buildJsonObject {
                    put("providerId", providerId)
                    put("credentialType", credentialType)
                    put("confirm", "CONFIRM")
                }
            } else {
                JsonNull
            },
        )
        put(
            "credentialSetup",
            buildJsonObject {
                put("credentialType", credentialType)
                put("setupMode", providerAuthExampleSetupMode())
                put("canSetCredentialWithTool", false)
                put("canInspectCredentialWithTool", true)
                put("canClearCredentialWithTool", requiresApiKey || usesOpenAiCodexOAuth)
                put("recommendedEntryPoint", providerAuthExampleEntryPoint())
                put(
                    "steps",
                    buildJsonArray {
                        providerAuthExampleSetupSteps().forEach { step ->
                            add(JsonPrimitive(step))
                        }
                    },
                )
            },
        )
        put(
            "suggestedTools",
            buildJsonArray {
                add(JsonPrimitive("providers.auth.status"))
                if (requiresRemoteSettings) {
                    add(JsonPrimitive("providers.configure.example"))
                }
                if (requiresApiKey || usesOpenAiCodexOAuth) {
                    add(JsonPrimitive("providers.auth.clear"))
                } else {
                    add(JsonPrimitive("providers.select"))
                }
                add(JsonPrimitive("providers.catalog"))
            },
        )
        put("exampleMarkdown", exampleMarkdown?.let(::JsonPrimitive) ?: JsonNull)
    }
}

internal fun ProviderType.toProviderAuthExampleMarkdown(
    settings: ProviderSettingsSnapshot,
    authState: ProviderAuthState,
    secretStatusAvailable: Boolean,
): String =
    buildString {
        appendLine("# Provider auth example")
        appendLine()
        appendLine("- Provider: `$providerId` (${displayName.toHandoffLine()})")
        appendLine("- Selected: ${settings.providerType == this@toProviderAuthExampleMarkdown}")
        appendLine("- Auth mode: ${authMode.name}")
        appendLine("- Credential type: ${providerAuthExampleCredentialType()}")
        appendLine("- Current auth status: ${authState.status}")
        appendLine("- Secret status available: $secretStatusAvailable")
        appendLine("- Example only: true")
        appendLine("- Executes authentication: false")
        appendLine("- Writes credential: false")
        appendLine("- Secret values included: false")
        appendLine("- API key values included: false")
        appendLine("- OAuth token values included: false")
        appendLine()
        appendLine("## Setup steps")
        providerAuthExampleSetupSteps().forEachIndexed { index, step ->
            appendLine("${index + 1}. ${step.toHandoffLine()}")
        }
        appendLine()
        appendLine("## Tool examples")
        appendLine("```json")
        appendLine(
            buildJsonObject {
                put("providerId", providerId)
            }.toString(),
        )
        appendLine("```")
        if (requiresApiKey || usesOpenAiCodexOAuth) {
            appendLine()
            appendLine("Credential clearing example:")
            appendLine("```json")
            appendLine(
                buildJsonObject {
                    put("providerId", providerId)
                    put("credentialType", providerAuthExampleCredentialType())
                    put("confirm", "CONFIRM")
                }.toString(),
            )
            appendLine("```")
        }
    }

internal fun ProviderAuthState.configuredForProviderAuthExample(): Boolean? =
    when (status) {
        "Configured", "NotRequired" -> true
        "Missing" -> false
        else -> null
    }

internal fun ProviderType.providerAuthExampleCredentialType(): String =
    when {
        requiresApiKey -> "api_key"
        usesOpenAiCodexOAuth -> "oauth"
        else -> "none"
    }

internal fun ProviderType.providerAuthExampleSetupMode(): String =
    when {
        requiresApiKey -> "api_key_entry"
        usesOpenAiCodexOAuth -> "openai_codex_oauth"
        else -> "not_required"
    }

internal fun ProviderType.providerAuthExampleEntryPoint(): String =
    when {
        requiresApiKey -> "Settings provider API-key field"
        usesOpenAiCodexOAuth -> "Settings OpenAI Codex sign-in flow"
        else -> "No credential setup required"
    }

internal fun ProviderType.providerAuthExampleSetupSteps(): List<String> =
    when {
        requiresApiKey ->
            listOf(
                "Open Settings and select $displayName if needed.",
                "Paste the provider API key into the API-key field.",
                "Run providers.auth.status for this provider to verify that credentials are configured.",
            )
        usesOpenAiCodexOAuth ->
            listOf(
                "Open Settings and choose the OpenAI Codex provider.",
                "Start the OpenAI Codex OAuth sign-in flow and complete the browser or device-code prompt.",
                "Run providers.auth.status for this provider to verify OAuth status and expiry metadata.",
            )
        else ->
            listOf(
                "No credential setup is required for $displayName.",
                "Use providers.select if you want to make this provider current.",
            )
    }

internal data class ProviderSetupRequirement(
    val code: String,
    val severity: String,
    val field: String?,
    val summary: String,
    val action: String,
    val suggestedTool: String,
)

internal data class ProviderSetupReadinessEntry(
    val providerType: ProviderType,
    val authState: ProviderAuthState,
    val requirements: List<ProviderSetupRequirement>,
) {
    val setupStatus: String
        get() = requirements.toProviderSetupStatus()

    val authReady: Boolean
        get() = authState.configuredForProviderAuthExample() == true && authState.oauthExpired != true

    val readyForUse: Boolean
        get() = requirements.isEmpty()
}

internal fun ProviderType.toProviderSetupPayload(
    settings: ProviderSettingsSnapshot,
    authState: ProviderAuthState,
    requirements: List<ProviderSetupRequirement>,
    requestedProviderId: String?,
    includeMarkdown: Boolean,
    setupMarkdown: String?,
    secretStatusAvailable: Boolean,
): JsonObject {
    val endpointSettings = if (requiresRemoteSettings) settings.endpointSettings(this) else null
    val defaultEndpointSettings = if (requiresRemoteSettings) defaultEndpointSettings() else null
    val authReady = authState.configuredForProviderAuthExample() == true && authState.oauthExpired != true
    val endpointReady = endpointSettings == null || endpointSettings.isReadyProviderEndpointSettings()
    val readyForUse = requirements.isEmpty()
    return buildJsonObject {
        put("requestedProviderId", requestedProviderId?.let(::JsonPrimitive) ?: JsonNull)
        put("providerId", providerId)
        put("storageValue", storageValue)
        put("displayName", displayName)
        put("selected", settings.providerType == this@toProviderSetupPayload)
        put("currentProviderId", settings.providerType.providerId)
        put("protocolFamily", protocolFamily.name)
        put("authMode", authMode.name)
        put("requiresRemoteSettings", requiresRemoteSettings)
        put("requiresCredential", requiresApiKey || usesOpenAiCodexOAuth)
        put("requiresApiKey", requiresApiKey)
        put("usesOpenAiCodexOAuth", usesOpenAiCodexOAuth)
        put("secretStatusAvailable", secretStatusAvailable)
        put("authStatus", authState.status)
        put("authReady", authReady)
        put("endpointReady", endpointReady)
        put("readyForUse", readyForUse)
        put("setupStatus", requirements.toProviderSetupStatus())
        put("setupStepCount", requirements.size)
        put("readOnly", true)
        put("exampleOnly", true)
        put("executesSetup", false)
        put("mutatesSettings", false)
        put("writesCredential", false)
        put("secretValuesIncluded", false)
        put("apiKeyValuesIncluded", false)
        put("oauthTokenValuesIncluded", false)
        put("credentialValuesIncluded", false)
        put("includeMarkdown", includeMarkdown)
        put(
            "endpointSettings",
            endpointSettings?.let { settings ->
                buildJsonObject {
                    put("baseUrl", settings.baseUrl)
                    put("modelId", settings.modelId)
                    put("timeoutSeconds", settings.timeoutSeconds)
                    put("baseUrlValid", settings.baseUrl.isValidProviderBaseUrl())
                    put("modelIdPresent", settings.modelId.isNotBlank())
                    put("timeoutValid", settings.timeoutSeconds in MIN_PROVIDER_TIMEOUT_SECONDS..MAX_PROVIDER_TIMEOUT_SECONDS)
                    put("usesDefaultBaseUrl", settings.baseUrl == defaultEndpointSettings?.baseUrl)
                    put("usesDefaultModelId", settings.modelId == defaultEndpointSettings?.modelId)
                    put("usesDefaultTimeoutSeconds", settings.timeoutSeconds == defaultEndpointSettings?.timeoutSeconds)
                }
            } ?: JsonNull,
        )
        put(
            "requirements",
            buildJsonArray {
                requirements.forEach { requirement ->
                    add(requirement.toProviderSetupRequirementPayload())
                }
            },
        )
        put(
            "suggestedTools",
            buildJsonArray {
                toProviderSetupSuggestedTools(
                    settings = settings,
                    requirements = requirements,
                ).forEach { toolName ->
                    add(JsonPrimitive(toolName))
                }
            },
        )
        put("setupMarkdown", setupMarkdown?.let(::JsonPrimitive) ?: JsonNull)
    }
}

internal fun List<ProviderSetupReadinessEntry>.toProviderSetupMatrixPayload(
    settings: ProviderSettingsSnapshot,
    includeRequirements: Boolean,
    includeMarkdown: Boolean,
    matrixMarkdown: String?,
    secretStatusAvailable: Boolean,
): JsonObject {
    val statuses = map { entry -> entry.setupStatus }
    return buildJsonObject {
        put("providerCount", size)
        put("includedProviderCount", size)
        put("currentProviderId", settings.providerType.providerId)
        put("currentProviderDisplayName", settings.providerType.displayName)
        put("secretStatusAvailable", secretStatusAvailable)
        put("readyProviderCount", count { entry -> entry.readyForUse })
        put("needsAuthProviderCount", statuses.count { status -> status == "NEEDS_AUTH" })
        put("needsEndpointProviderCount", statuses.count { status -> status == "NEEDS_ENDPOINT" })
        put("needsAuthAndEndpointProviderCount", statuses.count { status -> status == "NEEDS_AUTH_AND_ENDPOINT" })
        put("needsSetupProviderCount", count { entry -> !entry.readyForUse })
        put("includeRequirements", includeRequirements)
        put("includeMarkdown", includeMarkdown)
        put("readOnly", true)
        put("exampleOnly", true)
        put("executesSetup", false)
        put("mutatesSettings", false)
        put("writesCredential", false)
        put("secretValuesIncluded", false)
        put("apiKeyValuesIncluded", false)
        put("oauthTokenValuesIncluded", false)
        put("credentialValuesIncluded", false)
        put(
            "setupStatusStats",
            buildJsonArray {
                statuses
                    .groupingBy { status -> status }
                    .eachCount()
                    .toList()
                    .sortedBy { (status, _) -> status }
                    .forEach { (status, count) ->
                        add(providerNamedCountPayload(nameField = "setupStatus", name = status, countField = "providerCount", count = count))
                    }
            },
        )
        put(
            "readyProviderIds",
            buildJsonArray {
                this@toProviderSetupMatrixPayload
                    .filter { entry -> entry.readyForUse }
                    .forEach { entry -> add(JsonPrimitive(entry.providerType.providerId)) }
            },
        )
        put(
            "suggestedTools",
            buildJsonArray {
                this@toProviderSetupMatrixPayload
                    .flatMap { entry ->
                        entry.providerType.toProviderSetupSuggestedTools(
                            settings = settings,
                            requirements = entry.requirements,
                        )
                    }.distinct()
                    .forEach { toolName -> add(JsonPrimitive(toolName)) }
            },
        )
        put(
            "providers",
            buildJsonArray {
                this@toProviderSetupMatrixPayload.forEach { entry ->
                    add(
                        entry.toProviderSetupMatrixProviderPayload(
                            settings = settings,
                            includeRequirements = includeRequirements,
                        ),
                    )
                }
            },
        )
        put("matrixMarkdown", matrixMarkdown?.let(::JsonPrimitive) ?: JsonNull)
    }
}

internal fun ProviderSetupReadinessEntry.toProviderSetupMatrixProviderPayload(
    settings: ProviderSettingsSnapshot,
    includeRequirements: Boolean,
): JsonObject {
    val endpointSettings = if (providerType.requiresRemoteSettings) settings.endpointSettings(providerType) else null
    val endpointReady = endpointSettings == null || endpointSettings.isReadyProviderEndpointSettings()
    return buildJsonObject {
        put("providerId", providerType.providerId)
        put("storageValue", providerType.storageValue)
        put("displayName", providerType.displayName)
        put("selected", settings.providerType == providerType)
        put("protocolFamily", providerType.protocolFamily.name)
        put("authMode", providerType.authMode.name)
        put("requiresRemoteSettings", providerType.requiresRemoteSettings)
        put("requiresCredential", providerType.requiresApiKey || providerType.usesOpenAiCodexOAuth)
        put("requiresApiKey", providerType.requiresApiKey)
        put("usesOpenAiCodexOAuth", providerType.usesOpenAiCodexOAuth)
        put("authStatus", authState.status)
        put("authReady", authReady)
        put("endpointReady", endpointReady)
        put("readyForUse", readyForUse)
        put("setupStatus", setupStatus)
        put("setupStepCount", requirements.size)
        put("secretValuesIncluded", false)
        put("apiKeyValuesIncluded", false)
        put("oauthTokenValuesIncluded", false)
        put(
            "suggestedTools",
            buildJsonArray {
                providerType
                    .toProviderSetupSuggestedTools(
                        settings = settings,
                        requirements = requirements,
                    ).forEach { toolName -> add(JsonPrimitive(toolName)) }
            },
        )
        put(
            "requirements",
            if (includeRequirements) {
                buildJsonArray {
                    requirements.forEach { requirement ->
                        add(requirement.toProviderSetupRequirementPayload())
                    }
                }
            } else {
                JsonNull
            },
        )
    }
}

internal fun List<ProviderSetupReadinessEntry>.toProviderSetupMatrixMarkdown(
    settings: ProviderSettingsSnapshot,
    includeRequirements: Boolean,
    secretStatusAvailable: Boolean,
): String =
    buildString {
        appendLine("# Provider setup matrix")
        appendLine()
        appendLine("- Current provider: `${settings.providerType.providerId}` (${settings.providerType.displayName.toHandoffLine()})")
        appendLine("- Providers included: ${this@toProviderSetupMatrixMarkdown.size}")
        appendLine("- Ready providers: ${this@toProviderSetupMatrixMarkdown.count { entry -> entry.readyForUse }}")
        appendLine("- Secret status available: $secretStatusAvailable")
        appendLine("- Requirement details included: $includeRequirements")
        appendLine("- Read-only: true")
        appendLine("- Executes setup: false")
        appendLine("- Mutates settings: false")
        appendLine("- Writes credential: false")
        appendLine("- Secret values included: false")
        appendLine("- OAuth token values included: false")
        appendLine()
        appendLine("## Providers")
        this@toProviderSetupMatrixMarkdown.forEach { entry ->
            val endpointSettings =
                if (entry.providerType.requiresRemoteSettings) {
                    settings.endpointSettings(entry.providerType)
                } else {
                    null
                }
            val endpointReady = endpointSettings == null || endpointSettings.isReadyProviderEndpointSettings()
            append("- `")
            append(entry.providerType.providerId)
            append("` selected=")
            append(settings.providerType == entry.providerType)
            append(" status=")
            append(entry.setupStatus)
            append(" auth=")
            append(entry.authState.status)
            append(" endpointReady=")
            append(endpointReady)
            append(" requirements=")
            append(entry.requirements.size)
            appendLine()
            if (includeRequirements) {
                entry.requirements.forEach { requirement ->
                    append("  - ")
                    append(requirement.code)
                    append(": ")
                    append(requirement.summary.toHandoffLine())
                    appendLine()
                }
            }
        }
    }

internal fun ProviderEndpointSettings.isReadyProviderEndpointSettings(): Boolean =
    baseUrl.isValidProviderBaseUrl() &&
        modelId.isNotBlank() &&
        timeoutSeconds in MIN_PROVIDER_TIMEOUT_SECONDS..MAX_PROVIDER_TIMEOUT_SECONDS

internal fun List<ProviderSetupRequirement>.toProviderSetupStatus(): String =
    when {
        isEmpty() -> "READY"
        any { requirement -> requirement.code.startsWith("provider.auth.") } &&
            any { requirement -> requirement.code.startsWith("provider.endpoint.") } -> "NEEDS_AUTH_AND_ENDPOINT"
        any { requirement -> requirement.code.startsWith("provider.auth.") } -> "NEEDS_AUTH"
        any { requirement -> requirement.code.startsWith("provider.endpoint.") } -> "NEEDS_ENDPOINT"
        else -> "NEEDS_SETUP"
    }

internal fun ProviderSetupRequirement.toProviderSetupRequirementPayload(): JsonObject =
    buildJsonObject {
        put("code", code)
        put("severity", severity)
        put("field", field?.let(::JsonPrimitive) ?: JsonNull)
        put("summary", summary)
        put("action", action)
        put("suggestedTool", suggestedTool)
    }

internal fun ProviderType.toProviderSetupSuggestedTools(
    settings: ProviderSettingsSnapshot,
    requirements: List<ProviderSetupRequirement>,
): List<String> =
    buildList {
        add("providers.auth.status")
        requirements
            .map { requirement -> requirement.suggestedTool }
            .filterTo(this) { toolName -> toolName.isNotBlank() }
        if (settings.providerType != this@toProviderSetupSuggestedTools) {
            add("providers.select")
        }
        add("providers.catalog")
    }.distinct()

internal fun ProviderType.toProviderSetupRequirements(
    settings: ProviderSettingsSnapshot,
    authState: ProviderAuthState,
    secretStatusAvailable: Boolean,
): List<ProviderSetupRequirement> =
    buildList {
        fun addRequirement(
            code: String,
            severity: String,
            field: String?,
            summary: String,
            action: String,
            suggestedTool: String,
        ) {
            add(
                ProviderSetupRequirement(
                    code = code,
                    severity = severity,
                    field = field,
                    summary = summary.toProviderDoctorText(),
                    action = action.toProviderDoctorText(),
                    suggestedTool = suggestedTool,
                ),
            )
        }

        when {
            !secretStatusAvailable && (requiresApiKey || usesOpenAiCodexOAuth) ->
                addRequirement(
                    code = "provider.auth.status_unknown",
                    severity = "Warning",
                    field = "credential",
                    summary = "Credential status for $displayName cannot be inspected.",
                    action = "Open Settings to verify credentials or wire provider credential storage.",
                    suggestedTool = "providers.auth.example",
                )
            requiresApiKey && authState.apiKeyConfigured != true ->
                addRequirement(
                    code = "provider.auth.api_key_missing",
                    severity = "Error",
                    field = "api_key",
                    summary = "Provider $displayName needs an API key.",
                    action = "Use the Settings API-key field, then run providers.auth.status.",
                    suggestedTool = "providers.auth.example",
                )
            usesOpenAiCodexOAuth && authState.oauthConfigured != true ->
                addRequirement(
                    code = "provider.auth.oauth_missing",
                    severity = "Error",
                    field = "oauth",
                    summary = "Provider $displayName needs OpenAI Codex OAuth sign-in.",
                    action = "Complete OpenAI Codex sign-in in Settings, then run providers.auth.status.",
                    suggestedTool = "providers.auth.example",
                )
        }
        if (authState.oauthExpired == true) {
            addRequirement(
                code = "provider.auth.oauth_expired",
                severity = "Error",
                field = "oauth",
                summary = "Provider $displayName has expired OAuth credentials.",
                action = "Repeat OpenAI Codex sign-in before using this provider.",
                suggestedTool = "providers.auth.example",
            )
        }
        if (requiresRemoteSettings) {
            val endpointSettings = settings.endpointSettings(this@toProviderSetupRequirements)
            if (!endpointSettings.baseUrl.isValidProviderBaseUrl()) {
                addRequirement(
                    code = "provider.endpoint.base_url_invalid",
                    severity = "Error",
                    field = "baseUrl",
                    summary = "Provider $displayName needs a valid HTTP(S) base URL.",
                    action = "Run providers.configure or inspect providers.configure.example.",
                    suggestedTool = "providers.configure.example",
                )
            }
            if (endpointSettings.modelId.isBlank()) {
                addRequirement(
                    code = "provider.endpoint.model_id_missing",
                    severity = "Error",
                    field = "modelId",
                    summary = "Provider $displayName needs a model id.",
                    action = "Run providers.configure with the model id or inspect providers.configure.example.",
                    suggestedTool = "providers.configure.example",
                )
            }
            if (endpointSettings.timeoutSeconds !in MIN_PROVIDER_TIMEOUT_SECONDS..MAX_PROVIDER_TIMEOUT_SECONDS) {
                addRequirement(
                    code = "provider.endpoint.timeout_invalid",
                    severity = "Error",
                    field = "timeoutSeconds",
                    summary = "Provider $displayName timeout is outside the supported range.",
                    action = "Run providers.configure with a bounded timeout or reset provider defaults.",
                    suggestedTool = "providers.configure.example",
                )
            }
        }
    }

internal fun ProviderType.toProviderSetupMarkdown(
    settings: ProviderSettingsSnapshot,
    authState: ProviderAuthState,
    requirements: List<ProviderSetupRequirement>,
    requestedProviderId: String?,
    secretStatusAvailable: Boolean,
): String =
    buildString {
        appendLine("# Provider setup guide")
        appendLine()
        appendLine("- Provider: `$providerId` (${displayName.toHandoffLine()})")
        appendLine("- Requested provider filter: ${requestedProviderId?.toHandoffLine() ?: "none"}")
        appendLine("- Current provider: `${settings.providerType.providerId}`")
        appendLine("- Status: ${requirements.toProviderSetupStatus()}")
        appendLine("- Ready for use: ${requirements.isEmpty()}")
        appendLine("- Auth status: ${authState.status}")
        appendLine("- Secret status available: $secretStatusAvailable")
        appendLine("- Read-only: true")
        appendLine("- Executes setup: false")
        appendLine("- Mutates settings: false")
        appendLine("- Writes credential: false")
        appendLine("- Secret values included: false")
        appendLine("- OAuth token values included: false")
        appendLine()
        appendLine("## Requirements")
        if (requirements.isEmpty()) {
            appendLine("- None")
        } else {
            requirements.forEach { requirement ->
                append("- ")
                append(requirement.severity)
                append(" `")
                append(requirement.code)
                append("`: ")
                append(requirement.summary.toHandoffLine())
                append(" Action: ")
                append(requirement.action.toHandoffLine())
                append(" Tool: `")
                append(requirement.suggestedTool)
                appendLine("`")
            }
        }
    }

internal data class ProviderDoctorIssue(
    val id: String,
    val severity: String,
    val code: String,
    val providerId: String,
    val displayName: String,
    val selected: Boolean,
    val authStatus: String,
    val summary: String,
    val action: String,
    val detail: String? = null,
)

internal data class ProviderImportCandidate(
    val sourceIndex: Int,
    val providerType: ProviderType,
    val sourceProviderId: String?,
    val sourceSelected: Boolean,
    val endpointSettings: ProviderEndpointSettings?,
)

internal data class ProviderImportSkippedEntry(
    val sourceIndex: Int,
    val code: String,
    val summary: String,
)

internal sealed interface ProviderImportEntriesParseResult {
    data class Success(
        val entries: JsonArray,
    ) : ProviderImportEntriesParseResult

    data class Failure(
        val result: ToolExecutionResult,
    ) : ProviderImportEntriesParseResult
}

internal sealed interface ProviderImportCandidateParseResult {
    data class Candidate(
        val candidate: ProviderImportCandidate,
    ) : ProviderImportCandidateParseResult

    data class Skipped(
        val skipped: ProviderImportSkippedEntry,
    ) : ProviderImportCandidateParseResult
}

internal fun ProviderType.toProviderHandoffPayload(
    settings: ProviderSettingsSnapshot,
    authState: ProviderAuthState,
): JsonObject {
    val endpointSettings = if (requiresRemoteSettings) settings.endpointSettings(this) else null
    val defaultEndpointSettings = if (requiresRemoteSettings) defaultEndpointSettings() else null
    return buildJsonObject {
        put("storageValue", storageValue)
        put("providerId", providerId)
        put("displayName", displayName)
        put("protocolFamily", protocolFamily.name)
        put("authMode", authMode.name)
        put("selected", settings.providerType == this@toProviderHandoffPayload)
        put("requiresCredential", requiresApiKey || usesOpenAiCodexOAuth)
        put("requiresRemoteSettings", requiresRemoteSettings)
        put("requiresApiKey", requiresApiKey)
        put("usesOpenAiCodexOAuth", usesOpenAiCodexOAuth)
        put("authStatus", authState.status)
        put("apiKeyConfigured", authState.apiKeyConfigured?.let(::JsonPrimitive) ?: JsonNull)
        put("oauthConfigured", authState.oauthConfigured?.let(::JsonPrimitive) ?: JsonNull)
        put("oauthExpired", authState.oauthExpired?.let(::JsonPrimitive) ?: JsonNull)
        put("oauthProfileConfigured", authState.oauthProfileConfigured?.let(::JsonPrimitive) ?: JsonNull)
        put("secretValuesIncluded", false)
        put("oauthTokenValuesIncluded", false)
        put(
            "endpointSettings",
            if (endpointSettings != null) {
                buildJsonObject {
                    put("baseUrl", endpointSettings.baseUrl)
                    put("modelId", endpointSettings.modelId)
                    put("timeoutSeconds", endpointSettings.timeoutSeconds)
                    put("customBaseUrl", endpointSettings.baseUrl != defaultEndpointSettings?.baseUrl)
                    put("customModelId", endpointSettings.modelId != defaultEndpointSettings?.modelId)
                    put("customTimeout", endpointSettings.timeoutSeconds != defaultEndpointSettings?.timeoutSeconds)
                }
            } else {
                JsonNull
            },
        )
    }
}

internal fun List<Pair<ProviderType, ProviderAuthState>>.toProviderHandoffMarkdown(
    settings: ProviderSettingsSnapshot,
    requestedProviderId: String?,
): String {
    val includedProviders = this
    return buildString {
        appendLine("# Provider handoff")
        appendLine()
        appendLine("- Current provider: `${settings.providerType.providerId}` (${settings.providerType.displayName.toHandoffLine()})")
        appendLine("- Requested provider filter: ${requestedProviderId?.toHandoffLine() ?: "none"}")
        appendLine("- Providers included: ${includedProviders.size}")
        appendLine("- Secret values included: false")
        appendLine("- OAuth token values included: false")
        appendLine()
        appendLine("## Included providers")
        if (includedProviders.isEmpty()) {
            appendLine("_No providers included._")
        } else {
            includedProviders.forEach { (providerType, authState) ->
                appendLine(providerType.toProviderHandoffMarkdownLine(settings = settings, authState = authState))
            }
        }
    }
}

internal fun ProviderType.toProviderHandoffMarkdownLine(
    settings: ProviderSettingsSnapshot,
    authState: ProviderAuthState,
): String =
    buildString {
        append("- `")
        append(displayName.toHandoffLine())
        append("` id=`")
        append(providerId)
        append("` selected=")
        append(settings.providerType == this@toProviderHandoffMarkdownLine)
        append(" auth=")
        append(authState.status)
        append(" mode=")
        append(authMode.name)
        if (requiresRemoteSettings) {
            val endpointSettings = settings.endpointSettings(this@toProviderHandoffMarkdownLine)
            append(" endpoint=`")
            append(endpointSettings.baseUrl.toHandoffLine())
            append("` model=`")
            append(endpointSettings.modelId.toHandoffLine())
            append("` timeoutSeconds=")
            append(endpointSettings.timeoutSeconds)
        }
    }

internal fun ProviderType.toProviderDoctorPayload(
    settings: ProviderSettingsSnapshot,
    authState: ProviderAuthState,
): JsonObject {
    val endpointSettings = if (requiresRemoteSettings) settings.endpointSettings(this) else null
    return buildJsonObject {
        put("storageValue", storageValue)
        put("providerId", providerId)
        put("displayName", displayName)
        put("protocolFamily", protocolFamily.name)
        put("authMode", authMode.name)
        put("selected", settings.providerType == this@toProviderDoctorPayload)
        put("requiresCredential", requiresApiKey || usesOpenAiCodexOAuth)
        put("requiresRemoteSettings", requiresRemoteSettings)
        put("authStatus", authState.status)
        put("apiKeyConfigured", authState.apiKeyConfigured?.let(::JsonPrimitive) ?: JsonNull)
        put("oauthConfigured", authState.oauthConfigured?.let(::JsonPrimitive) ?: JsonNull)
        put("oauthExpired", authState.oauthExpired?.let(::JsonPrimitive) ?: JsonNull)
        put("oauthProfileConfigured", authState.oauthProfileConfigured?.let(::JsonPrimitive) ?: JsonNull)
        put("secretValuesIncluded", false)
        put("oauthTokenValuesIncluded", false)
        put(
            "endpointSettings",
            if (endpointSettings != null) {
                buildJsonObject {
                    put("baseUrl", endpointSettings.baseUrl)
                    put("modelId", endpointSettings.modelId)
                    put("timeoutSeconds", endpointSettings.timeoutSeconds)
                    put("baseUrlValid", endpointSettings.baseUrl.isValidProviderBaseUrl())
                    put("modelIdPresent", endpointSettings.modelId.isNotBlank())
                    put(
                        "timeoutValid",
                        endpointSettings.timeoutSeconds in MIN_PROVIDER_TIMEOUT_SECONDS..MAX_PROVIDER_TIMEOUT_SECONDS,
                    )
                }
            } else {
                JsonNull
            },
        )
    }
}

internal fun ProviderType.toProviderDoctorIssues(
    settings: ProviderSettingsSnapshot,
    authState: ProviderAuthState,
    secretStatusAvailable: Boolean,
): List<ProviderDoctorIssue> =
    buildList {
        fun addIssue(
            severity: String,
            code: String,
            summary: String,
            action: String,
            detail: String? = null,
        ) {
            add(
                ProviderDoctorIssue(
                    id = "$providerId:$code",
                    severity = severity,
                    code = code,
                    providerId = providerId,
                    displayName = displayName,
                    selected = settings.providerType == this@toProviderDoctorIssues,
                    authStatus = authState.status,
                    summary = summary.toProviderDoctorText(),
                    action = action.toProviderDoctorText(),
                    detail = detail?.toProviderDoctorText(),
                ),
            )
        }

        when (authState.status) {
            "Missing" ->
                addIssue(
                    severity = "Error",
                    code =
                        if (usesOpenAiCodexOAuth) {
                            "provider.auth.oauth_missing"
                        } else {
                            "provider.auth.api_key_missing"
                        },
                    summary = "Provider $displayName is missing required credentials.",
                    action =
                        if (usesOpenAiCodexOAuth) {
                            "Complete OpenAI Codex OAuth sign-in or select a different provider."
                        } else {
                            "Configure an API key for this provider or select a provider that does not require credentials."
                        },
                )
            "Unknown" ->
                if (!secretStatusAvailable && (requiresApiKey || usesOpenAiCodexOAuth)) {
                    addIssue(
                        severity = "Warning",
                        code = "provider.auth.status_unknown",
                        summary = "Provider $displayName credential status cannot be inspected.",
                        action = "Wire ProviderSecretStore before relying on provider readiness diagnostics.",
                    )
                }
        }
        if (authState.oauthExpired == true) {
            addIssue(
                severity = "Error",
                code = "provider.auth.oauth_expired",
                summary = "Provider $displayName has an expired OAuth credential.",
                action = "Refresh or repeat OpenAI Codex OAuth sign-in before using this provider.",
            )
        }
        if (usesOpenAiCodexOAuth && authState.oauthConfigured == true && authState.oauthProfileConfigured == false) {
            addIssue(
                severity = "Warning",
                code = "provider.auth.oauth_profile_missing",
                summary = "Provider $displayName OAuth credential lacks profile metadata.",
                action = "Reauthenticate if account identity is needed for support or diagnostics.",
            )
        }
        if (requiresRemoteSettings) {
            val endpointSettings = settings.endpointSettings(this@toProviderDoctorIssues)
            when {
                endpointSettings.baseUrl.isBlank() ->
                    addIssue(
                        severity = "Error",
                        code = "provider.endpoint.base_url_blank",
                        summary = "Provider $displayName has a blank base URL.",
                        action = "Run providers.configure with a valid HTTPS baseUrl or reset provider defaults.",
                    )
                !endpointSettings.baseUrl.isValidProviderBaseUrl() ->
                    addIssue(
                        severity = "Error",
                        code = "provider.endpoint.base_url_invalid",
                        summary = "Provider $displayName base URL is not a valid HTTP(S) URL.",
                        action = "Run providers.configure with a valid HTTPS baseUrl or reset provider defaults.",
                        detail = endpointSettings.baseUrl,
                    )
            }
            if (endpointSettings.modelId.isBlank()) {
                addIssue(
                    severity = "Error",
                    code = "provider.endpoint.model_id_blank",
                    summary = "Provider $displayName has a blank model id.",
                    action = "Run providers.configure with the modelId to use for chat completions.",
                )
            }
            if (endpointSettings.timeoutSeconds !in MIN_PROVIDER_TIMEOUT_SECONDS..MAX_PROVIDER_TIMEOUT_SECONDS) {
                addIssue(
                    severity = "Error",
                    code = "provider.endpoint.timeout_invalid",
                    summary = "Provider $displayName timeout is outside the supported range.",
                    action = "Run providers.configure with timeoutSeconds between $MIN_PROVIDER_TIMEOUT_SECONDS and $MAX_PROVIDER_TIMEOUT_SECONDS.",
                    detail = "timeoutSeconds=${endpointSettings.timeoutSeconds}",
                )
            }
        }
    }

internal fun List<ProviderDoctorIssue>.toProviderDoctorStatus(): String =
    when {
        any { issue -> issue.severity == "Error" } -> "ERROR"
        any { issue -> issue.severity == "Warning" } -> "WARN"
        else -> "OK"
    }

internal fun ProviderDoctorIssue.toProviderDoctorPayload(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("severity", severity)
        put("code", code)
        put("providerId", providerId)
        put("displayName", displayName)
        put("selected", selected)
        put("authStatus", authStatus)
        put("summary", summary)
        put("action", action)
        put("detail", detail?.let(::JsonPrimitive) ?: JsonNull)
    }

internal fun List<ProviderDoctorIssue>.toProviderDoctorMarkdown(
    status: String,
    currentProvider: ProviderType,
    inspectedProviderCount: Int,
    issueCount: Int,
    limit: Int,
    includeAll: Boolean,
    requestedProviderId: String?,
): String {
    val includedIssues = this
    return buildString {
        appendLine("# Provider doctor")
        appendLine()
        appendLine("- Status: $status")
        appendLine("- Current provider: `${currentProvider.providerId}` (${currentProvider.displayName.toHandoffLine()})")
        appendLine("- Requested provider filter: ${requestedProviderId?.toHandoffLine() ?: "none"}")
        appendLine("- Include all providers: $includeAll")
        appendLine("- Providers inspected: $inspectedProviderCount")
        appendLine("- Issues included: ${includedIssues.size} of $issueCount")
        appendLine("- Limit: $limit")
        appendLine("- Secret values included: false")
        appendLine("- OAuth token values included: false")
        appendLine()
        appendLine("## Issues")
        if (includedIssues.isEmpty()) {
            appendLine("_No provider issues found._")
        } else {
            includedIssues.forEach { issue ->
                appendLine(issue.toProviderDoctorMarkdownLine())
            }
        }
    }
}

internal fun ProviderDoctorIssue.toProviderDoctorMarkdownLine(): String =
    buildString {
        append("- ")
        append(severity)
        append(" `")
        append(displayName.toHandoffLine())
        append("` id=`")
        append(providerId.toHandoffLine())
        append("` code=")
        append(code)
        append(": ")
        append(summary.toHandoffLine())
        detail?.let { detail ->
            append(" detail=")
            append(detail.toHandoffLine())
        }
        append(" Action: ")
        append(action.toHandoffLine())
    }

internal fun String.toProviderDoctorText(): String = toHandoffLine().take(PROVIDER_DOCTOR_TEXT_MAX_CHARS)

internal fun String.isValidProviderBaseUrl(): Boolean {
    val settings =
        ProviderEndpointSettings(
            baseUrl = this,
            modelId = "",
            timeoutSeconds = ProviderType.OpenAiCompatible.defaultTimeoutSeconds,
        )
    return settings.firstProviderEndpointPolicyError(ProviderType.OpenAiCompatible) == null
}

internal suspend fun ProviderSettingsSnapshot.toProviderStatsPayload(
    providerSecretStore: ProviderSecretStore?,
    clock: Clock,
): JsonObject {
    val providers = ProviderType.entries
    val remoteProviders = providers.filter { provider -> provider.requiresRemoteSettings }
    val authStates =
        providers.map { provider ->
            provider.toProviderAuthState(
                providerSecretStore = providerSecretStore,
                clock = clock,
            )
        }
    return buildJsonObject {
        put("providerCount", providers.size)
        put("currentProviderId", providerType.providerId)
        put("currentProviderDisplayName", providerType.displayName)
        put("currentProtocolFamily", providerType.protocolFamily.name)
        put("currentAuthMode", providerType.authMode.name)
        put("selectedRequiresCredential", providerType.requiresApiKey || providerType.usesOpenAiCodexOAuth)
        put("secretStatusAvailable", providerSecretStore != null)
        put("remoteProviderCount", remoteProviders.size)
        put("localProviderCount", providers.count { provider -> !provider.requiresRemoteSettings })
        put("requiresCredentialProviderCount", providers.count { provider -> provider.requiresApiKey || provider.usesOpenAiCodexOAuth })
        put("apiKeyProviderCount", providers.count { provider -> provider.requiresApiKey })
        put("openAiCodexOAuthProviderCount", providers.count { provider -> provider.usesOpenAiCodexOAuth })
        put(
            "protocolFamilyStats",
            providerTypeCountPayloads(
                nameField = "protocolFamily",
                selector = { provider -> provider.protocolFamily.name },
            ),
        )
        put(
            "authModeStats",
            providerTypeCountPayloads(
                nameField = "authMode",
                selector = { provider -> provider.authMode.name },
            ),
        )
        put(
            "authStatusStats",
            authStates.toProviderAuthStatusStatsPayload(),
        )
        put(
            "endpointStats",
            remoteProviders.toProviderEndpointStatsPayload(settings = this@toProviderStatsPayload),
        )
        put(
            "apiKeyStats",
            authStates.toProviderApiKeyStatsPayload(),
        )
        put(
            "oauthStats",
            authStates.toProviderOAuthStatsPayload(),
        )
    }
}

internal suspend fun ProviderType.toProviderAuthState(
    providerSecretStore: ProviderSecretStore?,
    clock: Clock,
): ProviderAuthState {
    val apiKeyConfigured =
        if (providerSecretStore == null || !requiresApiKey) {
            null
        } else {
            providerSecretStore.readApiKey(this) != null
        }
    val oauthCredential =
        if (providerSecretStore == null || !usesOpenAiCodexOAuth) {
            null
        } else {
            providerSecretStore.readOAuthCredential(this)
        }
    val oauthConfigured =
        when {
            providerSecretStore == null || !usesOpenAiCodexOAuth -> null
            else -> oauthCredential != null
        }
    val status =
        when {
            authMode == ProviderAuthMode.None -> "NotRequired"
            providerSecretStore == null -> "Unknown"
            requiresApiKey && apiKeyConfigured == true -> "Configured"
            usesOpenAiCodexOAuth && oauthConfigured == true -> "Configured"
            else -> "Missing"
        }
    val expiresAt = oauthCredential?.expiresAtEpochMillis?.let(Instant::ofEpochMilli)
    val oauthProfileConfigured =
        oauthCredential?.let { credential ->
            !credential.email.isNullOrBlank() ||
                !credential.profileName.isNullOrBlank() ||
                !credential.chatGptAccountId.isNullOrBlank()
        }
    return ProviderAuthState(
        providerType = this,
        status = status,
        apiKeyConfigured = apiKeyConfigured,
        oauthConfigured = oauthConfigured,
        oauthExpired = expiresAt?.isBefore(clock.instant()),
        oauthProfileConfigured = oauthProfileConfigured,
    )
}

internal fun providerTypeCountPayloads(
    nameField: String,
    selector: (ProviderType) -> String,
): JsonArray =
    buildJsonArray {
        ProviderType.entries
            .groupingBy(selector)
            .eachCount()
            .toList()
            .sortedBy { (name, _) -> name }
            .forEach { (name, count) ->
                add(providerNamedCountPayload(nameField = nameField, name = name, countField = "providerCount", count = count))
            }
    }

internal fun List<ProviderAuthState>.toProviderAuthStatusStatsPayload(): JsonArray =
    buildJsonArray {
        groupingBy { state -> state.status }
            .eachCount()
            .toList()
            .sortedBy { (status, _) -> status }
            .forEach { (status, count) ->
                add(providerNamedCountPayload(nameField = "status", name = status, countField = "providerCount", count = count))
            }
    }

internal fun List<ProviderType>.toProviderEndpointStatsPayload(settings: ProviderSettingsSnapshot): JsonObject =
    buildJsonObject {
        put("remoteProviderCount", size)
        put("customBaseUrlProviderCount", count { provider -> settings.endpointSettings(provider).baseUrl != provider.defaultBaseUrl })
        put("customModelIdProviderCount", count { provider -> settings.endpointSettings(provider).modelId != provider.defaultModelId })
        put(
            "customTimeoutProviderCount",
            count { provider ->
                settings.endpointSettings(provider).timeoutSeconds != provider.defaultEndpointSettings().timeoutSeconds
            },
        )
        put("blankModelIdProviderCount", count { provider -> settings.endpointSettings(provider).modelId.isBlank() })
    }

internal fun List<ProviderAuthState>.toProviderApiKeyStatsPayload(): JsonObject {
    val apiKeyStates = filter { state -> state.providerType.requiresApiKey }
    return buildJsonObject {
        put("apiKeyProviderCount", apiKeyStates.size)
        put("apiKeyConfiguredProviderCount", apiKeyStates.count { state -> state.apiKeyConfigured == true })
        put("apiKeyMissingProviderCount", apiKeyStates.count { state -> state.status == "Missing" })
        put("apiKeyUnknownProviderCount", apiKeyStates.count { state -> state.status == "Unknown" })
    }
}

internal fun List<ProviderAuthState>.toProviderOAuthStatsPayload(): JsonObject {
    val oauthStates = filter { state -> state.providerType.usesOpenAiCodexOAuth }
    return buildJsonObject {
        put("oauthProviderCount", oauthStates.size)
        put("oauthConfiguredProviderCount", oauthStates.count { state -> state.oauthConfigured == true })
        put("oauthMissingProviderCount", oauthStates.count { state -> state.status == "Missing" })
        put("oauthUnknownProviderCount", oauthStates.count { state -> state.status == "Unknown" })
        put("oauthExpiredProviderCount", oauthStates.count { state -> state.oauthExpired == true })
        put(
            "oauthProfileConfiguredProviderCount",
            oauthStates.count { state -> state.oauthProfileConfigured == true },
        )
    }
}

internal suspend fun ProviderType.toProviderAuthPayload(
    settings: ProviderSettingsSnapshot,
    providerSecretStore: ProviderSecretStore?,
    clock: Clock,
): JsonObject {
    val apiKeyConfigured =
        if (providerSecretStore == null || !requiresApiKey) {
            null
        } else {
            providerSecretStore.readApiKey(this) != null
        }
    val oauthCredential =
        if (providerSecretStore == null || !usesOpenAiCodexOAuth) {
            null
        } else {
            providerSecretStore.readOAuthCredential(this)
        }
    val oauthConfigured =
        when {
            providerSecretStore == null || !usesOpenAiCodexOAuth -> null
            else -> oauthCredential != null
        }
    val configured =
        when {
            providerSecretStore == null -> null
            authMode == ai.androidclaw.data.ProviderAuthMode.None -> true
            requiresApiKey -> apiKeyConfigured == true
            usesOpenAiCodexOAuth -> oauthConfigured == true
            else -> false
        }
    val status =
        when (configured) {
            null -> "Unknown"
            true -> if (authMode == ai.androidclaw.data.ProviderAuthMode.None) "NotRequired" else "Configured"
            false -> "Missing"
        }
    val expiresAt = oauthCredential?.expiresAtEpochMillis?.let(Instant::ofEpochMilli)
    val oauthProfileConfigured =
        oauthCredential?.let { credential ->
            !credential.email.isNullOrBlank() ||
                !credential.profileName.isNullOrBlank() ||
                !credential.chatGptAccountId.isNullOrBlank()
        }
    return buildJsonObject {
        put("storageValue", storageValue)
        put("providerId", providerId)
        put("displayName", displayName)
        put("authMode", authMode.name)
        put("selected", settings.providerType == this@toProviderAuthPayload)
        put("requiresCredential", requiresApiKey || usesOpenAiCodexOAuth)
        put("secretStatusAvailable", providerSecretStore != null)
        put("configured", configured?.let(::JsonPrimitive) ?: JsonNull)
        put("status", status)
        put("apiKeyConfigured", apiKeyConfigured?.let(::JsonPrimitive) ?: JsonNull)
        put("oauthConfigured", oauthConfigured?.let(::JsonPrimitive) ?: JsonNull)
        put("oauthExpiresAtIso", expiresAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("oauthExpired", expiresAt?.isBefore(clock.instant())?.let(::JsonPrimitive) ?: JsonNull)
        put("oauthProfileConfigured", oauthProfileConfigured?.let(::JsonPrimitive) ?: JsonNull)
    }
}

internal fun JsonObject.optionalProviderTimeoutSeconds(): Int? {
    val value = optionalText("timeoutSeconds") ?: return null
    return value.toIntOrNull()
        ?: throw IllegalArgumentException("providers.configure received a non-numeric timeoutSeconds.")
}



internal fun providerNamedCountPayload(
    nameField: String,
    name: String,
    countField: String,
    count: Int,
): JsonObject =
    buildJsonObject {
        put(nameField, name)
        put(countField, count)
    }

internal const val PROVIDER_DOCTOR_DEFAULT_LIMIT = 20
internal const val PROVIDER_DOCTOR_MAX_LIMIT = 50
internal const val PROVIDER_DOCTOR_TEXT_MAX_CHARS = 500
internal const val PROVIDER_EXPORT_FORMAT = "androidclaw.providers.export.v1"
internal const val PROVIDER_EXPORT_VERSION = 1
internal const val PROVIDER_IMPORT_FORMAT = "androidclaw.providers.import.v1"
internal const val PROVIDER_IMPORT_VERSION = 1
internal const val PROVIDER_IMPORT_DEFAULT_LIMIT = 20
internal const val PROVIDER_IMPORT_MAX_LIMIT = 20
