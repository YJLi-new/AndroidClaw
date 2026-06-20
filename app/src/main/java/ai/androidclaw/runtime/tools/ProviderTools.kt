package ai.androidclaw.runtime.tools

import ai.androidclaw.data.MAX_PROVIDER_TIMEOUT_SECONDS
import ai.androidclaw.data.MIN_PROVIDER_TIMEOUT_SECONDS
import ai.androidclaw.data.ProviderAuthMode
import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.firstProviderEndpointPolicyError
import kotlinx.coroutines.flow.first
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

internal fun providerToolEntries(
    settingsDataStore: SettingsDataStore,
    providerSecretStore: ProviderSecretStore?,
    clock: Clock,
): List<ToolRegistry.Entry> =
    listOf(
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.list",
                    aliases = listOf("provider.list"),
                    description = "List available model providers and current non-secret endpoint settings.",
                ),
        ) { _, _ ->
            val settings = settingsDataStore.settings.first()
            ToolExecutionResult.success(
                summary = "Found ${ProviderType.entries.size} provider(s).",
                payload =
                    buildJsonObject {
                        put("currentProviderId", settings.providerType.providerId)
                        put("currentProviderDisplayName", settings.providerType.displayName)
                        put("providerCount", ProviderType.entries.size)
                        put(
                            "providers",
                            buildJsonArray {
                                ProviderType.entries.forEach { providerType ->
                                    add(providerType.toProviderPayload(settings))
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.current",
                    aliases = listOf("provider.current", "providers.status", "provider.status"),
                    description = "Return the selected model provider and its current non-secret endpoint settings.",
                ),
        ) { _, _ ->
            val settings = settingsDataStore.settings.first()
            ToolExecutionResult.success(
                summary = "Current provider is ${settings.providerType.displayName}.",
                payload =
                    buildJsonObject {
                        put("provider", settings.providerType.toProviderPayload(settings))
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.select",
                    aliases = listOf("provider.select", "providers.use", "provider.use"),
                    description = "Select the active model provider by id, storage value, or display name.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providerId",
                                required = false,
                                description = "Provider id, storage value, or display name.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val identifier =
                arguments.optionalText("providerId")
                    ?: arguments.optionalText("id")
                    ?: arguments.optionalText("name")
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "providers.select requires a non-empty providerId.",
                        errorCode = "INVALID_ARGUMENTS",
                        payload =
                            buildJsonObject {
                                put("errorCode", "INVALID_ARGUMENTS")
                                put("toolName", "providers.select")
                                put("field", "providerId")
                            },
                    )
            val providerType =
                ProviderType.entries.firstOrNull { providerType ->
                    providerType.matchesProviderIdentifier(identifier)
                } ?: return@Entry ToolExecutionResult.failure(
                    summary = "Provider $identifier was not found.",
                    errorCode = "PROVIDER_NOT_FOUND",
                    payload =
                        buildJsonObject {
                            put("errorCode", "PROVIDER_NOT_FOUND")
                            put("toolName", "providers.select")
                            put("providerId", identifier)
                        },
                )
            settingsDataStore.setProviderType(providerType)
            val settings = settingsDataStore.settings.first()
            ToolExecutionResult.success(
                summary = "Selected provider ${providerType.displayName}.",
                payload =
                    buildJsonObject {
                        put("provider", providerType.toProviderPayload(settings))
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.configure",
                    aliases = listOf("provider.configure", "providers.update", "provider.update"),
                    description = "Update non-secret endpoint settings for a configurable model provider.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providerId",
                                required = false,
                                description = "Provider id, storage value, or display name. Defaults to the current provider.",
                            ),
                            ToolArgumentSpec(
                                name = "baseUrl",
                                description = "Provider base URL.",
                            ),
                            ToolArgumentSpec(
                                name = "modelId",
                                description = "Provider model id.",
                            ),
                            ToolArgumentSpec(
                                name = "timeoutSeconds",
                                description = "Provider timeout in seconds.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.settings.first()
            val identifier =
                arguments.optionalText("providerId")
                    ?: arguments.optionalText("id")
                    ?: arguments.optionalText("name")
            val providerType =
                if (identifier == null) {
                    settings.providerType
                } else {
                    ProviderType.entries.firstOrNull { providerType ->
                        providerType.matchesProviderIdentifier(identifier)
                    } ?: return@Entry ToolExecutionResult.failure(
                        summary = "Provider $identifier was not found.",
                        errorCode = "PROVIDER_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "PROVIDER_NOT_FOUND")
                                put("toolName", "providers.configure")
                                put("providerId", identifier)
                            },
                    )
                }
            if (!providerType.requiresRemoteSettings) {
                return@Entry ToolExecutionResult.failure(
                    summary = "Provider ${providerType.displayName} does not have remote endpoint settings.",
                    errorCode = "PROVIDER_NOT_CONFIGURABLE",
                    payload =
                        buildJsonObject {
                            put("errorCode", "PROVIDER_NOT_CONFIGURABLE")
                            put("toolName", "providers.configure")
                            put("providerId", providerType.providerId)
                        },
                )
            }
            val hasPatch =
                arguments.optionalText("baseUrl") != null ||
                    arguments.optionalText("modelId") != null ||
                    arguments.optionalText("timeoutSeconds") != null
            if (!hasPatch) {
                return@Entry ToolExecutionResult.failure(
                    summary = "providers.configure requires baseUrl, modelId, or timeoutSeconds.",
                    errorCode = "INVALID_ARGUMENTS",
                    payload =
                        buildJsonObject {
                            put("errorCode", "INVALID_ARGUMENTS")
                            put("toolName", "providers.configure")
                            put("field", "baseUrl|modelId|timeoutSeconds")
                        },
                )
            }
            val existingEndpoint = settings.endpointSettings(providerType)
            val timeoutSeconds =
                try {
                    arguments.optionalProviderTimeoutSeconds() ?: existingEndpoint.timeoutSeconds
                } catch (error: IllegalArgumentException) {
                    return@Entry ToolExecutionResult.failure(
                        summary = error.message ?: "providers.configure received an invalid timeoutSeconds.",
                        errorCode = "INVALID_ARGUMENTS",
                        payload =
                            buildJsonObject {
                                put("errorCode", "INVALID_ARGUMENTS")
                                put("toolName", "providers.configure")
                                put("field", "timeoutSeconds")
                            },
                    )
                }
            val updatedSettings =
                ProviderEndpointSettings(
                    baseUrl = arguments.optionalText("baseUrl") ?: existingEndpoint.baseUrl,
                    modelId = arguments.optionalText("modelId") ?: existingEndpoint.modelId,
                    timeoutSeconds = timeoutSeconds,
                )
            updatedSettings.firstProviderEndpointPolicyError(providerType)?.let { issue ->
                return@Entry ToolExecutionResult.failure(
                    summary = issue.message,
                    errorCode = issue.code,
                    payload =
                        buildJsonObject {
                            put("errorCode", issue.code)
                            put("toolName", "providers.configure")
                            put("field", "baseUrl")
                            put("providerId", providerType.providerId)
                        },
                )
            }
            val updatedSnapshot =
                settings.withEndpointSettings(
                    providerType = providerType,
                    settings = updatedSettings,
                )
            settingsDataStore.saveProviderSettings(updatedSnapshot)
            val reloadedSettings = settingsDataStore.settings.first()
            ToolExecutionResult.success(
                summary = "Updated provider ${providerType.displayName} settings.",
                payload =
                    buildJsonObject {
                        put("provider", providerType.toProviderPayload(reloadedSettings))
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.configure.example",
                    aliases =
                        listOf(
                            "provider.configure.example",
                            "providers.config.example",
                            "provider.config.example",
                            "providers.endpoint.example",
                            "provider.endpoint.example",
                        ),
                    description = "Return non-secret provider endpoint configuration examples without mutating settings.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providerId",
                                required = false,
                                description = "Provider id, storage value, or display name. Defaults to the current provider.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit exampleMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.settings.first()
            val identifier =
                arguments.optionalText("providerId")
                    ?: arguments.optionalText("id")
                    ?: arguments.optionalText("name")
            val providerType =
                if (identifier == null) {
                    settings.providerType
                } else {
                    ProviderType.entries.firstOrNull { providerType ->
                        providerType.matchesProviderIdentifier(identifier)
                    } ?: return@Entry ToolExecutionResult.failure(
                        summary = "Provider $identifier was not found.",
                        errorCode = "PROVIDER_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "PROVIDER_NOT_FOUND")
                                put("toolName", "providers.configure.example")
                                put("providerId", identifier)
                            },
                    )
                }
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val exampleMarkdown =
                if (includeMarkdown) {
                    providerType.toProviderConfigureExampleMarkdown(settings = settings)
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary =
                    if (providerType.requiresRemoteSettings) {
                        "Prepared non-secret configure example for ${providerType.displayName}."
                    } else {
                        "Provider ${providerType.displayName} has no remote endpoint settings to configure."
                    },
                payload =
                    providerType.toProviderConfigureExamplePayload(
                        settings = settings,
                        requestedProviderId = identifier,
                        includeMarkdown = includeMarkdown,
                        exampleMarkdown = exampleMarkdown,
                    ),
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.reset",
                    aliases = listOf("provider.reset", "providers.defaults", "provider.defaults"),
                    description = "Reset a configurable provider's non-secret endpoint settings to defaults.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providerId",
                                required = false,
                                description = "Provider id, storage value, or display name. Defaults to the current provider.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.settings.first()
            val identifier =
                arguments.optionalText("providerId")
                    ?: arguments.optionalText("id")
                    ?: arguments.optionalText("name")
            val providerType =
                if (identifier == null) {
                    settings.providerType
                } else {
                    ProviderType.entries.firstOrNull { providerType ->
                        providerType.matchesProviderIdentifier(identifier)
                    } ?: return@Entry ToolExecutionResult.failure(
                        summary = "Provider $identifier was not found.",
                        errorCode = "PROVIDER_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "PROVIDER_NOT_FOUND")
                                put("toolName", "providers.reset")
                                put("providerId", identifier)
                            },
                    )
                }
            if (!providerType.requiresRemoteSettings) {
                return@Entry ToolExecutionResult.failure(
                    summary = "Provider ${providerType.displayName} does not have remote endpoint settings.",
                    errorCode = "PROVIDER_NOT_CONFIGURABLE",
                    payload =
                        buildJsonObject {
                            put("errorCode", "PROVIDER_NOT_CONFIGURABLE")
                            put("toolName", "providers.reset")
                            put("providerId", providerType.providerId)
                        },
                )
            }
            settingsDataStore.saveProviderSettings(
                settings.withEndpointSettings(
                    providerType = providerType,
                    settings = providerType.defaultEndpointSettings(),
                ),
            )
            val reloadedSettings = settingsDataStore.settings.first()
            ToolExecutionResult.success(
                summary = "Reset provider ${providerType.displayName} settings to defaults.",
                payload =
                    buildJsonObject {
                        put("provider", providerType.toProviderPayload(reloadedSettings))
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.auth.status",
                    aliases = listOf("provider.auth.status", "providers.auth", "provider.auth"),
                    description = "Report non-secret authentication status for one or all providers.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providerId",
                                required = false,
                                description = "Provider id, storage value, or display name. Omit to list all providers.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.settings.first()
            val identifier =
                arguments.optionalText("providerId")
                    ?: arguments.optionalText("id")
                    ?: arguments.optionalText("name")
            val providers =
                if (identifier == null) {
                    ProviderType.entries
                } else {
                    listOf(
                        ProviderType.entries.firstOrNull { providerType ->
                            providerType.matchesProviderIdentifier(identifier)
                        } ?: return@Entry ToolExecutionResult.failure(
                            summary = "Provider $identifier was not found.",
                            errorCode = "PROVIDER_NOT_FOUND",
                            payload =
                                buildJsonObject {
                                    put("errorCode", "PROVIDER_NOT_FOUND")
                                    put("toolName", "providers.auth.status")
                                    put("providerId", identifier)
                                },
                        ),
                    )
                }
            val authPayloads =
                providers.map { providerType ->
                    providerType.toProviderAuthPayload(
                        settings = settings,
                        providerSecretStore = providerSecretStore,
                        clock = clock,
                    )
                }
            ToolExecutionResult.success(
                summary =
                    if (identifier == null) {
                        "Loaded authentication status for ${providers.size} provider(s)."
                    } else {
                        "Loaded authentication status for ${providers.single().displayName}."
                    },
                payload =
                    buildJsonObject {
                        put("currentProviderId", settings.providerType.providerId)
                        put("secretStatusAvailable", providerSecretStore != null)
                        put(
                            "providers",
                            buildJsonArray {
                                authPayloads.forEach { payload ->
                                    add(payload)
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.auth.example",
                    aliases =
                        listOf(
                            "provider.auth.example",
                            "providers.credentials.example",
                            "provider.credentials.example",
                            "providers.login.example",
                            "provider.login.example",
                        ),
                    description = "Return non-secret provider authentication setup examples without mutating credentials.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providerId",
                                required = false,
                                description = "Provider id, storage value, or display name. Defaults to the current provider.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit exampleMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.settings.first()
            val identifier =
                arguments.optionalText("providerId")
                    ?: arguments.optionalText("id")
                    ?: arguments.optionalText("name")
            val providerType =
                if (identifier == null) {
                    settings.providerType
                } else {
                    ProviderType.entries.firstOrNull { providerType ->
                        providerType.matchesProviderIdentifier(identifier)
                    } ?: return@Entry ToolExecutionResult.failure(
                        summary = "Provider $identifier was not found.",
                        errorCode = "PROVIDER_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "PROVIDER_NOT_FOUND")
                                put("toolName", "providers.auth.example")
                                put("providerId", identifier)
                            },
                    )
                }
            val authState =
                providerType.toProviderAuthState(
                    providerSecretStore = providerSecretStore,
                    clock = clock,
                )
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val exampleMarkdown =
                if (includeMarkdown) {
                    providerType.toProviderAuthExampleMarkdown(
                        settings = settings,
                        authState = authState,
                        secretStatusAvailable = providerSecretStore != null,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary =
                    if (providerType.requiresApiKey || providerType.usesOpenAiCodexOAuth) {
                        "Prepared non-secret auth example for ${providerType.displayName}."
                    } else {
                        "Provider ${providerType.displayName} does not require credentials."
                    },
                payload =
                    providerType.toProviderAuthExamplePayload(
                        settings = settings,
                        authState = authState,
                        requestedProviderId = identifier,
                        includeMarkdown = includeMarkdown,
                        exampleMarkdown = exampleMarkdown,
                        secretStatusAvailable = providerSecretStore != null,
                    ),
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.setup",
                    aliases =
                        listOf(
                            "provider.setup",
                            "providers.quickstart",
                            "provider.quickstart",
                            "providers.ready",
                            "provider.ready",
                        ),
                    description = "Return a read-only provider setup readiness guide across auth and endpoint requirements.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providerId",
                                required = false,
                                description = "Provider id, storage value, or display name. Defaults to the current provider.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit setupMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.settings.first()
            val identifier =
                arguments.optionalText("providerId")
                    ?: arguments.optionalText("id")
                    ?: arguments.optionalText("name")
            val providerType =
                if (identifier == null) {
                    settings.providerType
                } else {
                    ProviderType.entries.firstOrNull { providerType ->
                        providerType.matchesProviderIdentifier(identifier)
                    } ?: return@Entry ToolExecutionResult.failure(
                        summary = "Provider $identifier was not found.",
                        errorCode = "PROVIDER_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "PROVIDER_NOT_FOUND")
                                put("toolName", "providers.setup")
                                put("providerId", identifier)
                            },
                    )
                }
            val authState =
                providerType.toProviderAuthState(
                    providerSecretStore = providerSecretStore,
                    clock = clock,
                )
            val requirements =
                providerType.toProviderSetupRequirements(
                    settings = settings,
                    authState = authState,
                    secretStatusAvailable = providerSecretStore != null,
                )
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val setupMarkdown =
                if (includeMarkdown) {
                    providerType.toProviderSetupMarkdown(
                        settings = settings,
                        authState = authState,
                        requirements = requirements,
                        requestedProviderId = identifier,
                        secretStatusAvailable = providerSecretStore != null,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary =
                    if (requirements.isEmpty()) {
                        "Provider ${providerType.displayName} is ready."
                    } else {
                        "Provider ${providerType.displayName} needs ${requirements.size} setup step(s)."
                    },
                payload =
                    providerType.toProviderSetupPayload(
                        settings = settings,
                        authState = authState,
                        requirements = requirements,
                        requestedProviderId = identifier,
                        includeMarkdown = includeMarkdown,
                        setupMarkdown = setupMarkdown,
                        secretStatusAvailable = providerSecretStore != null,
                    ),
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.setup.matrix",
                    aliases =
                        listOf(
                            "provider.setup.matrix",
                            "providers.setup.all",
                            "provider.setup.all",
                            "providers.readiness",
                            "providers.onboarding",
                        ),
                    description = "Return a read-only provider setup readiness matrix for all providers.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "includeRequirements",
                                description = "Set false to omit per-provider requirement details. Defaults to true.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit matrixMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.settings.first()
            val secretStatusAvailable = providerSecretStore != null
            val entries =
                ProviderType.entries.map { providerType ->
                    val authState =
                        providerType.toProviderAuthState(
                            providerSecretStore = providerSecretStore,
                            clock = clock,
                        )
                    ProviderSetupReadinessEntry(
                        providerType = providerType,
                        authState = authState,
                        requirements =
                            providerType.toProviderSetupRequirements(
                                settings = settings,
                                authState = authState,
                                secretStatusAvailable = secretStatusAvailable,
                            ),
                    )
                }
            val includeRequirements = arguments.optionalBoolean("includeRequirements", defaultValue = true)
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val matrixMarkdown =
                if (includeMarkdown) {
                    entries.toProviderSetupMatrixMarkdown(
                        settings = settings,
                        includeRequirements = includeRequirements,
                        secretStatusAvailable = secretStatusAvailable,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary =
                    "Prepared setup readiness matrix for ${entries.size} provider(s); " +
                        "${entries.count { entry -> entry.readyForUse }} ready.",
                payload =
                    entries.toProviderSetupMatrixPayload(
                        settings = settings,
                        includeRequirements = includeRequirements,
                        includeMarkdown = includeMarkdown,
                        matrixMarkdown = matrixMarkdown,
                        secretStatusAvailable = secretStatusAvailable,
                    ),
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.auth.clear",
                    aliases =
                        listOf(
                            "provider.auth.clear",
                            "providers.credentials.clear",
                            "provider.credentials.clear",
                            "providers.logout",
                            "provider.logout",
                            "providers.sign_out",
                            "provider.sign_out",
                        ),
                    description = "Clear stored provider API-key or OAuth credentials after explicit confirmation.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providerId",
                                required = false,
                                description = "Provider id, storage value, or display name. Defaults to the current provider.",
                            ),
                            ToolArgumentSpec(
                                name = "credentialType",
                                description = "Credential slot to clear: all, api_key, or oauth. Defaults to all.",
                            ),
                            ToolArgumentSpec(
                                name = "confirm",
                                description = "Must equal CONFIRM.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.settings.first()
            val identifier =
                arguments.optionalText("providerId")
                    ?: arguments.optionalText("id")
                    ?: arguments.optionalText("name")
            val providerType =
                if (identifier == null) {
                    settings.providerType
                } else {
                    ProviderType.entries.firstOrNull { providerType ->
                        providerType.matchesProviderIdentifier(identifier)
                    } ?: return@Entry ToolExecutionResult.failure(
                        summary = "Provider $identifier was not found.",
                        errorCode = "PROVIDER_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "PROVIDER_NOT_FOUND")
                                put("toolName", "providers.auth.clear")
                                put("providerId", identifier)
                            },
                    )
                }
            if (arguments.optionalText("confirm") != "CONFIRM") {
                return@Entry ToolExecutionResult.failure(
                    summary = "Confirm provider credential clearing with confirm=CONFIRM.",
                    errorCode = "CONFIRMATION_REQUIRED",
                    payload =
                        buildJsonObject {
                            put("errorCode", "CONFIRMATION_REQUIRED")
                            put("toolName", "providers.auth.clear")
                            put("field", "confirm")
                            put("providerId", providerType.providerId)
                        },
                )
            }
            val clearTarget =
                try {
                    arguments.optionalProviderCredentialClearTarget()
                } catch (error: IllegalArgumentException) {
                    return@Entry ToolExecutionResult.failure(
                        summary = error.message ?: "providers.auth.clear received an unsupported credentialType.",
                        errorCode = "INVALID_ARGUMENTS",
                        payload =
                            buildJsonObject {
                                put("errorCode", "INVALID_ARGUMENTS")
                                put("toolName", "providers.auth.clear")
                                put("field", "credentialType")
                            },
                    )
                }
            if (clearTarget == ProviderCredentialClearTarget.ApiKey && !providerType.requiresApiKey) {
                return@Entry providerAuthClearUnsupportedTypeFailure(
                    providerType = providerType,
                    credentialType = clearTarget.storageValue,
                )
            }
            if (clearTarget == ProviderCredentialClearTarget.OAuth && !providerType.usesOpenAiCodexOAuth) {
                return@Entry providerAuthClearUnsupportedTypeFailure(
                    providerType = providerType,
                    credentialType = clearTarget.storageValue,
                )
            }
            val secretStore =
                providerSecretStore
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "Provider credential storage is unavailable.",
                        errorCode = "PROVIDER_SECRET_STORE_UNAVAILABLE",
                        payload =
                            buildJsonObject {
                                put("errorCode", "PROVIDER_SECRET_STORE_UNAVAILABLE")
                                put("toolName", "providers.auth.clear")
                                put("providerId", providerType.providerId)
                                put("credentialType", clearTarget.storageValue)
                            },
                    )
            val beforeState =
                providerType.toProviderAuthState(
                    providerSecretStore = secretStore,
                    clock = clock,
                )
            val shouldClearApiKey = clearTarget.clearsApiKey && providerType.requiresApiKey
            val shouldClearOAuth = clearTarget.clearsOAuth && providerType.usesOpenAiCodexOAuth
            if (shouldClearApiKey) {
                secretStore.writeApiKey(providerType, null)
            }
            if (shouldClearOAuth) {
                secretStore.writeOAuthCredential(providerType, null)
            }
            val afterState =
                providerType.toProviderAuthState(
                    providerSecretStore = secretStore,
                    clock = clock,
                )
            val clearedCredentialCount =
                listOf(
                    shouldClearApiKey && beforeState.apiKeyConfigured == true,
                    shouldClearOAuth && beforeState.oauthConfigured == true,
                ).count { cleared -> cleared }
            ToolExecutionResult.success(
                summary =
                    when {
                        clearedCredentialCount > 0 ->
                            "Cleared $clearedCredentialCount credential(s) for ${providerType.displayName}."
                        shouldClearApiKey || shouldClearOAuth ->
                            "No stored ${clearTarget.storageValue} credentials were configured for ${providerType.displayName}."
                        else ->
                            "Provider ${providerType.displayName} has no credential slots to clear."
                    },
                payload =
                    buildJsonObject {
                        put("providerId", providerType.providerId)
                        put("displayName", providerType.displayName)
                        put("credentialType", clearTarget.storageValue)
                        put("confirmAccepted", true)
                        put("secretStatusAvailable", true)
                        put("secretValuesIncluded", false)
                        put("oauthTokenValuesIncluded", false)
                        put("authStatusBefore", beforeState.status)
                        put("authStatusAfter", afterState.status)
                        put("apiKeyClearAttempted", shouldClearApiKey)
                        put("oauthClearAttempted", shouldClearOAuth)
                        put("apiKeyWasConfigured", beforeState.apiKeyConfigured?.let(::JsonPrimitive) ?: JsonNull)
                        put("oauthWasConfigured", beforeState.oauthConfigured?.let(::JsonPrimitive) ?: JsonNull)
                        put("apiKeyCleared", shouldClearApiKey && beforeState.apiKeyConfigured == true)
                        put("oauthCredentialCleared", shouldClearOAuth && beforeState.oauthConfigured == true)
                        put("clearedCredentialCount", clearedCredentialCount)
                        put(
                            "provider",
                            providerType.toProviderAuthPayload(
                                settings = settings,
                                providerSecretStore = secretStore,
                                clock = clock,
                            ),
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.stats",
                    aliases = listOf("provider.stats"),
                    description = "Summarize provider inventory, endpoint customization, and non-secret auth status.",
                ),
        ) { _, _ ->
            val settings = settingsDataStore.settings.first()
            ToolExecutionResult.success(
                summary = "Summarized ${ProviderType.entries.size} provider(s).",
                payload =
                    settings.toProviderStatsPayload(
                        providerSecretStore = providerSecretStore,
                        clock = clock,
                    ),
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.catalog",
                    aliases =
                        listOf(
                            "provider.catalog",
                            "providers.models",
                            "provider.models",
                            "providers.endpoints",
                            "provider.endpoints",
                        ),
                    description = "Return provider default/current model and endpoint catalog without secret values.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providerId",
                                required = false,
                                description = "Optional provider id, storage value, or display name to include only one provider.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit catalogMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.settings.first()
            val identifier =
                arguments.optionalText("providerId")
                    ?: arguments.optionalText("id")
                    ?: arguments.optionalText("name")
            val includedProviders =
                if (identifier == null) {
                    ProviderType.entries
                } else {
                    listOf(
                        ProviderType.entries.firstOrNull { providerType ->
                            providerType.matchesProviderIdentifier(identifier)
                        } ?: return@Entry ToolExecutionResult.failure(
                            summary = "Provider $identifier was not found.",
                            errorCode = "PROVIDER_NOT_FOUND",
                            payload =
                                buildJsonObject {
                                    put("errorCode", "PROVIDER_NOT_FOUND")
                                    put("toolName", "providers.catalog")
                                    put("providerId", identifier)
                                },
                        ),
                    )
                }
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val catalogMarkdown =
                if (includeMarkdown) {
                    includedProviders.toProviderCatalogMarkdown(
                        settings = settings,
                        requestedProviderId = identifier,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary =
                    if (identifier == null) {
                        "Prepared provider catalog with ${includedProviders.size} provider(s)."
                    } else {
                        "Prepared provider catalog for ${includedProviders.single().displayName}."
                    },
                payload =
                    buildJsonObject {
                        put("providerCount", ProviderType.entries.size)
                        put("includedProviderCount", includedProviders.size)
                        put("omittedProviderCount", ProviderType.entries.size - includedProviders.size)
                        put("remoteProviderCount", includedProviders.count { provider -> provider.requiresRemoteSettings })
                        put("requestedProviderId", identifier?.let(::JsonPrimitive) ?: JsonNull)
                        put("currentProviderId", settings.providerType.providerId)
                        put("currentProviderDisplayName", settings.providerType.displayName)
                        put("includeMarkdown", includeMarkdown)
                        put("secretValuesIncluded", false)
                        put("oauthTokenValuesIncluded", false)
                        put(
                            "providers",
                            buildJsonArray {
                                includedProviders.forEach { providerType ->
                                    add(providerType.toProviderCatalogPayload(settings = settings))
                                }
                            },
                        )
                        put("catalogMarkdown", catalogMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.export",
                    aliases =
                        listOf(
                            "provider.export",
                            "providers.backup",
                            "provider.backup",
                            "providers.settings.export",
                            "provider.settings.export",
                        ),
                    description = "Export non-secret provider selection and endpoint settings.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providerId",
                                required = false,
                                description = "Optional provider id, storage value, or display name to include only one provider.",
                            ),
                            ToolArgumentSpec(
                                name = "includeDefaults",
                                description = "Set false to omit endpoint default values. Defaults to true.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit exportMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.settings.first()
            val identifier =
                arguments.optionalText("providerId")
                    ?: arguments.optionalText("id")
                    ?: arguments.optionalText("name")
            val includedProviders =
                if (identifier == null) {
                    ProviderType.entries
                } else {
                    listOf(
                        ProviderType.entries.firstOrNull { providerType ->
                            providerType.matchesProviderIdentifier(identifier)
                        } ?: return@Entry ToolExecutionResult.failure(
                            summary = "Provider $identifier was not found.",
                            errorCode = "PROVIDER_NOT_FOUND",
                            payload =
                                buildJsonObject {
                                    put("errorCode", "PROVIDER_NOT_FOUND")
                                    put("toolName", "providers.export")
                                    put("providerId", identifier)
                                },
                        ),
                    )
                }
            val includeDefaults = arguments.optionalBoolean("includeDefaults", defaultValue = true)
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val exportMarkdown =
                if (includeMarkdown) {
                    includedProviders.toProviderExportMarkdown(
                        settings = settings,
                        requestedProviderId = identifier,
                        includeDefaults = includeDefaults,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary =
                    if (identifier == null) {
                        "Prepared provider settings export with ${includedProviders.size} provider(s)."
                    } else {
                        "Prepared provider settings export for ${includedProviders.single().displayName}."
                    },
                payload =
                    buildJsonObject {
                        put("exportFormat", PROVIDER_EXPORT_FORMAT)
                        put("exportVersion", PROVIDER_EXPORT_VERSION)
                        put("generatedAtIso", clock.instant().toString())
                        put("providerCount", ProviderType.entries.size)
                        put("includedProviderCount", includedProviders.size)
                        put("omittedProviderCount", ProviderType.entries.size - includedProviders.size)
                        put("remoteProviderCount", includedProviders.count { provider -> provider.requiresRemoteSettings })
                        put("requestedProviderId", identifier?.let(::JsonPrimitive) ?: JsonNull)
                        put("currentProviderId", settings.providerType.providerId)
                        put("currentProviderStorageValue", settings.providerType.storageValue)
                        put("currentProviderDisplayName", settings.providerType.displayName)
                        put("includeDefaults", includeDefaults)
                        put("includeMarkdown", includeMarkdown)
                        put("secretValuesIncluded", false)
                        put("apiKeyValuesIncluded", false)
                        put("oauthTokenValuesIncluded", false)
                        put("credentialValuesIncluded", false)
                        put("authStateIncluded", false)
                        put(
                            "providers",
                            buildJsonArray {
                                includedProviders.forEach { providerType ->
                                    add(
                                        providerType.toProviderExportPayload(
                                            settings = settings,
                                            includeDefaults = includeDefaults,
                                        ),
                                    )
                                }
                            },
                        )
                        put("exportMarkdown", exportMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.import",
                    aliases =
                        listOf(
                            "provider.import",
                            "providers.restore",
                            "provider.restore",
                            "providers.settings.import",
                            "provider.settings.import",
                        ),
                    description = "Import non-secret provider endpoint settings from a providers.export payload.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providers",
                                description = "Array of exported provider entries, or pass export.providers.",
                            ),
                            ToolArgumentSpec(
                                name = "export",
                                description = "Optional providers.export payload containing a providers array.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum provider entries to scan. Defaults to 20, max 20.",
                            ),
                            ToolArgumentSpec(
                                name = "selectCurrentProvider",
                                description = "Set true to restore the exported current provider when present. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "includeLocalProviders",
                                description = "Set false to skip local/offline provider entries. Defaults to true.",
                            ),
                            ToolArgumentSpec(
                                name = "dryRun",
                                description = "Set true to preview importable settings without writing. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "confirm",
                                description = "Must be CONFIRM unless dryRun=true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val dryRun = arguments.optionalBoolean("dryRun", defaultValue = false)
            if (!dryRun && arguments.optionalText("confirm") != "CONFIRM") {
                return@Entry missingProviderImportConfirmationResult()
            }
            val rawEntries =
                when (val parsedEntries = arguments.providerImportEntries()) {
                    is ProviderImportEntriesParseResult.Failure -> return@Entry parsedEntries.result
                    is ProviderImportEntriesParseResult.Success -> parsedEntries.entries
                }
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = PROVIDER_IMPORT_DEFAULT_LIMIT,
                    ).coerceIn(0, PROVIDER_IMPORT_MAX_LIMIT)
            val selectCurrentProvider = arguments.optionalBoolean("selectCurrentProvider", defaultValue = false)
            val includeLocalProviders = arguments.optionalBoolean("includeLocalProviders", defaultValue = true)
            val scannedEntries = rawEntries.take(limit)
            val candidates = mutableListOf<ProviderImportCandidate>()
            val skipped = mutableListOf<ProviderImportSkippedEntry>()
            scannedEntries.forEachIndexed { sourceIndex, element ->
                when (val parsedCandidate = element.toProviderImportCandidate(sourceIndex = sourceIndex)) {
                    is ProviderImportCandidateParseResult.Candidate -> {
                        if (!includeLocalProviders && !parsedCandidate.candidate.providerType.requiresRemoteSettings) {
                            skipped +=
                                ProviderImportSkippedEntry(
                                    sourceIndex = sourceIndex,
                                    code = "providers.import.local_skipped",
                                    summary = "Local provider entry skipped because includeLocalProviders=false.",
                                )
                        } else {
                            candidates += parsedCandidate.candidate
                        }
                    }
                    is ProviderImportCandidateParseResult.Skipped -> skipped += parsedCandidate.skipped
                }
            }
            val settingsBefore = settingsDataStore.settings.first()
            val sourceCurrentProvider = arguments.providerImportCurrentProvider()
            val candidateProviderTypes = candidates.map(ProviderImportCandidate::providerType).toSet()
            val selectedProviderCandidate =
                if (selectCurrentProvider) {
                    sourceCurrentProvider
                        ?.takeIf { providerType -> providerType in candidateProviderTypes }
                        ?: candidates.firstOrNull { candidate -> candidate.sourceSelected }?.providerType
                } else {
                    null
                }
            var updatedSettings = settingsBefore
            candidates.forEach { candidate ->
                val endpointSettings = candidate.endpointSettings
                if (endpointSettings != null && candidate.providerType.requiresRemoteSettings) {
                    updatedSettings =
                        updatedSettings.withEndpointSettings(
                            providerType = candidate.providerType,
                            settings = endpointSettings,
                        )
                }
            }
            selectedProviderCandidate?.let { providerType ->
                updatedSettings = updatedSettings.copy(providerType = providerType)
            }
            if (!dryRun) {
                settingsDataStore.saveProviderSettings(updatedSettings)
            }
            val settingsAfter = if (dryRun) settingsBefore else settingsDataStore.settings.first()
            ToolExecutionResult.success(
                summary =
                    if (dryRun) {
                        "Prepared dry-run provider settings import with ${candidates.size} importable provider entr${if (candidates.size == 1) "y" else "ies"}."
                    } else {
                        "Imported provider settings for ${candidates.size} provider entr${if (candidates.size == 1) "y" else "ies"}; skipped ${skipped.size}."
                    },
                payload =
                    buildJsonObject {
                        put("importFormat", PROVIDER_IMPORT_FORMAT)
                        put("importVersion", PROVIDER_IMPORT_VERSION)
                        put("acceptedExportFormat", PROVIDER_EXPORT_FORMAT)
                        put("acceptedExportVersion", PROVIDER_EXPORT_VERSION)
                        put("providerLimit", limit)
                        put("importLimit", limit)
                        put("dryRun", dryRun)
                        put("selectCurrentProvider", selectCurrentProvider)
                        put("includeLocalProviders", includeLocalProviders)
                        put("secretValuesImported", false)
                        put("apiKeyValuesImported", false)
                        put("oauthTokenValuesImported", false)
                        put("credentialValuesImported", false)
                        put("authStateImported", false)
                        put("secretValuesIncluded", false)
                        put("oauthTokenValuesIncluded", false)
                        put("currentProviderIdBefore", settingsBefore.providerType.providerId)
                        put("currentProviderIdAfter", settingsAfter.providerType.providerId)
                        put("selectedProviderChanged", settingsBefore.providerType != settingsAfter.providerType)
                        put("sourceCurrentProviderId", sourceCurrentProvider?.providerId?.let(::JsonPrimitive) ?: JsonNull)
                        put("selectedProviderImported", selectedProviderCandidate?.providerId?.let(::JsonPrimitive) ?: JsonNull)
                        put("receivedProviderCount", rawEntries.size)
                        put("scannedProviderCount", scannedEntries.size)
                        put("omittedInputProviderCount", (rawEntries.size - scannedEntries.size).coerceAtLeast(0))
                        put("importableProviderCount", candidates.size)
                        put("importedProviderCount", if (dryRun) 0 else candidates.size)
                        put("endpointImportedProviderCount", if (dryRun) 0 else candidates.count { candidate -> candidate.endpointSettings != null })
                        put("skippedProviderCount", skipped.size)
                        put("invalidProviderCount", skipped.count { entry -> entry.code.startsWith("providers.import.invalid") })
                        put("localProviderSkippedCount", skipped.count { entry -> entry.code == "providers.import.local_skipped" })
                        put(
                            "candidateProviders",
                            buildJsonArray {
                                candidates.forEach { candidate ->
                                    add(candidate.toProviderImportCandidatePayload())
                                }
                            },
                        )
                        put(
                            "importedProviders",
                            buildJsonArray {
                                if (!dryRun) {
                                    candidates.forEach { candidate ->
                                        add(
                                            candidate.toProviderImportedPayload(
                                                settings = settingsAfter,
                                            ),
                                        )
                                    }
                                }
                            },
                        )
                        put(
                            "skippedProviders",
                            buildJsonArray {
                                skipped.forEach { skippedEntry ->
                                    add(skippedEntry.toProviderImportSkippedPayload())
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.doctor",
                    aliases =
                        listOf(
                            "provider.doctor",
                            "providers.check",
                            "provider.check",
                            "providers.health",
                            "provider.health",
                        ),
                    description = "Return actionable provider and OAuth diagnostics without secret values.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providerId",
                                required = false,
                                description = "Provider id, storage value, or display name. Defaults to the current provider.",
                            ),
                            ToolArgumentSpec(
                                name = "includeAll",
                                description = "Set true to inspect every provider. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum diagnostic issues to include. Defaults to 20.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit doctorMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.settings.first()
            val identifier =
                arguments.optionalText("providerId")
                    ?: arguments.optionalText("id")
                    ?: arguments.optionalText("name")
            val includeAll = arguments.optionalBoolean("includeAll", defaultValue = false)
            val inspectedProviders =
                when {
                    identifier != null ->
                        listOf(
                            ProviderType.entries.firstOrNull { providerType ->
                                providerType.matchesProviderIdentifier(identifier)
                            } ?: return@Entry ToolExecutionResult.failure(
                                summary = "Provider $identifier was not found.",
                                errorCode = "PROVIDER_NOT_FOUND",
                                payload =
                                    buildJsonObject {
                                        put("errorCode", "PROVIDER_NOT_FOUND")
                                        put("toolName", "providers.doctor")
                                        put("providerId", identifier)
                                    },
                            ),
                        )
                    includeAll -> ProviderType.entries
                    else -> listOf(settings.providerType)
                }
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = PROVIDER_DOCTOR_DEFAULT_LIMIT,
                    ).coerceIn(0, PROVIDER_DOCTOR_MAX_LIMIT)
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val providersWithAuth =
                inspectedProviders.map { providerType ->
                    providerType to
                        providerType.toProviderAuthState(
                            providerSecretStore = providerSecretStore,
                            clock = clock,
                        )
                }
            val issues =
                providersWithAuth.flatMap { (providerType, authState) ->
                    providerType.toProviderDoctorIssues(
                        settings = settings,
                        authState = authState,
                        secretStatusAvailable = providerSecretStore != null,
                    )
                }
            val includedIssues = issues.take(limit)
            val status = issues.toProviderDoctorStatus()
            val doctorMarkdown =
                if (includeMarkdown) {
                    includedIssues.toProviderDoctorMarkdown(
                        status = status,
                        currentProvider = settings.providerType,
                        inspectedProviderCount = inspectedProviders.size,
                        issueCount = issues.size,
                        limit = limit,
                        includeAll = includeAll,
                        requestedProviderId = identifier,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary =
                    when {
                        issues.isEmpty() ->
                            "Provider doctor found no issues across ${inspectedProviders.size} inspected provider(s)."
                        includedIssues.size == issues.size ->
                            "Provider doctor found ${issues.size} issue(s) across ${inspectedProviders.size} inspected provider(s)."
                        else ->
                            "Provider doctor found ${issues.size} issue(s) and included ${includedIssues.size}."
                    },
                payload =
                    buildJsonObject {
                        put("status", status)
                        put("providerCount", ProviderType.entries.size)
                        put("inspectedProviderCount", inspectedProviders.size)
                        put("omittedProviderCount", ProviderType.entries.size - inspectedProviders.size)
                        put("currentProviderId", settings.providerType.providerId)
                        put("currentProviderDisplayName", settings.providerType.displayName)
                        put("requestedProviderId", identifier?.let(::JsonPrimitive) ?: JsonNull)
                        put("includeAll", includeAll)
                        put("secretStatusAvailable", providerSecretStore != null)
                        put("secretValuesIncluded", false)
                        put("oauthTokenValuesIncluded", false)
                        put("issueCount", issues.size)
                        put("includedIssueCount", includedIssues.size)
                        put("omittedIssueCount", (issues.size - includedIssues.size).coerceAtLeast(0))
                        put("errorCount", issues.count { issue -> issue.severity == "Error" })
                        put("warningCount", issues.count { issue -> issue.severity == "Warning" })
                        put("limit", limit)
                        put("includeMarkdown", includeMarkdown)
                        put(
                            "stats",
                            settings.toProviderStatsPayload(
                                providerSecretStore = providerSecretStore,
                                clock = clock,
                            ),
                        )
                        put(
                            "providers",
                            buildJsonArray {
                                providersWithAuth.forEach { (providerType, authState) ->
                                    add(providerType.toProviderDoctorPayload(settings = settings, authState = authState))
                                }
                            },
                        )
                        put(
                            "issues",
                            buildJsonArray {
                                includedIssues.forEach { issue ->
                                    add(issue.toProviderDoctorPayload())
                                }
                            },
                        )
                        put("doctorMarkdown", doctorMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.handoff",
                    aliases =
                        listOf(
                            "provider.handoff",
                            "providers.snapshot",
                            "provider.snapshot",
                        ),
                    description = "Return a compact provider and auth-status handoff without secret values.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providerId",
                                required = false,
                                description = "Optional provider id, storage value, or display name to include only one provider.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit handoffMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val settings = settingsDataStore.settings.first()
            val identifier =
                arguments.optionalText("providerId")
                    ?: arguments.optionalText("id")
                    ?: arguments.optionalText("name")
            val includedProviders =
                if (identifier == null) {
                    ProviderType.entries
                } else {
                    listOf(
                        ProviderType.entries.firstOrNull { providerType ->
                            providerType.matchesProviderIdentifier(identifier)
                        } ?: return@Entry ToolExecutionResult.failure(
                            summary = "Provider $identifier was not found.",
                            errorCode = "PROVIDER_NOT_FOUND",
                            payload =
                                buildJsonObject {
                                    put("errorCode", "PROVIDER_NOT_FOUND")
                                    put("toolName", "providers.handoff")
                                    put("providerId", identifier)
                                },
                        ),
                    )
                }
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val authStates =
                includedProviders.map { providerType ->
                    providerType.toProviderAuthState(
                        providerSecretStore = providerSecretStore,
                        clock = clock,
                    )
                }
            val providersWithAuth = includedProviders.zip(authStates)
            val handoffMarkdown =
                if (includeMarkdown) {
                    providersWithAuth.toProviderHandoffMarkdown(
                        settings = settings,
                        requestedProviderId = identifier,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary =
                    if (identifier == null) {
                        "Prepared provider handoff with ${includedProviders.size} provider(s)."
                    } else {
                        "Prepared provider handoff for ${includedProviders.single().displayName}."
                    },
                payload =
                    buildJsonObject {
                        put("providerCount", ProviderType.entries.size)
                        put("includedProviderCount", includedProviders.size)
                        put("omittedProviderCount", ProviderType.entries.size - includedProviders.size)
                        put("requestedProviderId", identifier?.let(::JsonPrimitive) ?: JsonNull)
                        put("currentProviderId", settings.providerType.providerId)
                        put("currentProviderDisplayName", settings.providerType.displayName)
                        put("secretStatusAvailable", providerSecretStore != null)
                        put("includeMarkdown", includeMarkdown)
                        put("secretValuesIncluded", false)
                        put("oauthTokenValuesIncluded", false)
                        put(
                            "stats",
                            settings.toProviderStatsPayload(
                                providerSecretStore = providerSecretStore,
                                clock = clock,
                            ),
                        )
                        put(
                            "providers",
                            buildJsonArray {
                                providersWithAuth.forEach { (providerType, authState) ->
                                    add(
                                        providerType.toProviderHandoffPayload(
                                            settings = settings,
                                            authState = authState,
                                        ),
                                    )
                                }
                            },
                        )
                        put("handoffMarkdown", handoffMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "providers.get",
                    aliases = listOf("provider.get"),
                    description = "Return one provider by id, storage value, or display name.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "providerId",
                                required = false,
                                description = "Provider id, storage value, or display name.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val identifier =
                arguments.optionalText("providerId")
                    ?: arguments.optionalText("id")
                    ?: arguments.optionalText("name")
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "providers.get requires a non-empty providerId.",
                        errorCode = "INVALID_ARGUMENTS",
                        payload =
                            buildJsonObject {
                                put("errorCode", "INVALID_ARGUMENTS")
                                put("toolName", "providers.get")
                                put("field", "providerId")
                            },
                    )
            val providerType =
                ProviderType.entries.firstOrNull { providerType ->
                    providerType.matchesProviderIdentifier(identifier)
                } ?: return@Entry ToolExecutionResult.failure(
                    summary = "Provider $identifier was not found.",
                    errorCode = "PROVIDER_NOT_FOUND",
                    payload =
                        buildJsonObject {
                            put("errorCode", "PROVIDER_NOT_FOUND")
                            put("toolName", "providers.get")
                            put("providerId", identifier)
                        },
                )
            val settings = settingsDataStore.settings.first()
            ToolExecutionResult.success(
                summary = "Loaded provider ${providerType.displayName}.",
                payload =
                    buildJsonObject {
                        put("provider", providerType.toProviderPayload(settings))
                    },
            )
        },
    )


internal fun ProviderType.matchesProviderIdentifier(identifier: String): Boolean =
    providerId.equals(identifier, ignoreCase = true) ||
        storageValue.equals(identifier, ignoreCase = true) ||
        displayName.equals(identifier, ignoreCase = true) ||
        name.equals(identifier, ignoreCase = true)
