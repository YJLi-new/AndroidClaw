package ai.androidclaw.runtime.tools

import ai.androidclaw.data.model.EventLevel
import ai.androidclaw.runtime.providers.ModelRunMode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    @Test
    fun `unknown tool returns structured failure instead of throwing`() =
        runTest {
            val registry = ToolRegistry(emptyList())

            val result =
                registry.execute(
                    context = testToolContext("missing.tool"),
                    arguments = buildJsonObject {},
                )

            assertFalse(result.success)
            assertEquals("Unknown tool: missing.tool", result.summary)
            assertEquals("UNKNOWN_TOOL", result.errorCode)
            assertEquals("UNKNOWN_TOOL", result.payload["errorCode"]?.jsonPrimitive?.content)
        }

    @Test
    fun `unknown tool failure bounds requested name before returning or logging`() =
        runTest {
            val oversizedName = "missing." + "x".repeat(TOOL_REGISTRY_NAME_MAX_CHARS + 50)
            val loggedEvents = mutableListOf<Triple<EventLevel, String, String?>>()
            val registry =
                ToolRegistry(
                    tools = emptyList(),
                    eventLogger = { level, message, details ->
                        loggedEvents += Triple(level, message, details)
                    },
                )

            val result =
                registry.execute(
                    context = testToolContext(oversizedName),
                    arguments = buildJsonObject {},
                )

            assertFalse(result.success)
            val payloadToolName =
                result.payload["toolName"]
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            assertEquals(TOOL_REGISTRY_NAME_MAX_CHARS, payloadToolName.length)
            assertTrue(payloadToolName.endsWith("… [truncated]"))
            assertFalse(result.summary.contains(oversizedName))
            assertFalse(loggedEvents.single().second.contains(oversizedName))
            assertTrue(
                loggedEvents
                    .single()
                    .third
                    .orEmpty()
                    .contains("… [truncated]"),
            )
        }

    @Test
    fun `tool handler failure is converted into a structured result`() =
        runTest {
            val registry =
                ToolRegistry(
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "boom.tool",
                                        description = "Fails on purpose",
                                    ),
                            ) { _, _ ->
                                error("boom")
                            },
                        ),
                )

            val result =
                registry.execute(
                    context = testToolContext("boom.tool"),
                    arguments =
                        buildJsonObject {
                            put("hello", "world")
                        },
                )

            assertFalse(result.success)
            assertTrue(result.summary.contains("boom"))
            assertEquals("TOOL_EXECUTION_FAILED", result.errorCode)
            assertEquals("TOOL_EXECUTION_FAILED", result.payload["errorCode"]?.jsonPrimitive?.content)
        }

    @Test
    fun `tool handler summaries are bounded at registry boundary`() =
        runTest {
            val oversizedSummary = "s".repeat(TOOL_RESULT_SUMMARY_MAX_CHARS + 50)
            val registry =
                ToolRegistry(
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "large.tool",
                                        description = "Returns an oversized summary",
                                    ),
                            ) { _, _ ->
                                ToolExecutionResult.success(
                                    summary = oversizedSummary,
                                    payload = buildJsonObject {},
                                )
                            },
                        ),
                )

            val result =
                registry.execute(
                    context = testToolContext("large.tool"),
                    arguments = buildJsonObject {},
                )

            assertTrue(result.success)
            assertEquals(TOOL_RESULT_SUMMARY_MAX_CHARS, result.summary.length)
            assertTrue(result.summary.endsWith("… [truncated]"))
        }

    @Test
    fun `tool exception messages are bounded in summary and payload`() =
        runTest {
            val oversizedMessage = "e".repeat(TOOL_RESULT_SUMMARY_MAX_CHARS + 75)
            val registry =
                ToolRegistry(
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "huge-error.tool",
                                        description = "Throws an oversized message",
                                    ),
                            ) { _, _ ->
                                error(oversizedMessage)
                            },
                        ),
                )

            val result =
                registry.execute(
                    context = testToolContext("huge-error.tool"),
                    arguments = buildJsonObject {},
                )

            assertFalse(result.success)
            assertEquals(TOOL_RESULT_SUMMARY_MAX_CHARS, result.summary.length)
            assertTrue(result.summary.endsWith("… [truncated]"))
            assertEquals(
                result.summary,
                result.payload["message"]?.jsonPrimitive?.content,
            )
        }

    @Test
    fun `alias lookup resolves to canonical descriptor and executes handler`() =
        runTest {
            var executionCount = 0
            val registry =
                ToolRegistry(
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "sessions.list",
                                        aliases = listOf("session.list"),
                                        description = "List sessions",
                                    ),
                            ) { context, _ ->
                                executionCount += 1
                                assertEquals("session.list", context.requestedName)
                                assertEquals("sessions.list", context.canonicalName)
                                ToolExecutionResult.success(
                                    summary = "ok",
                                    payload = buildJsonObject {},
                                )
                            },
                        ),
                )

            val descriptor = registry.findDescriptor("session.list")
            val result =
                registry.execute(
                    context = testToolContext("session.list"),
                    arguments = buildJsonObject {},
                )

            assertTrue(registry.hasTool("session.list"))
            assertEquals(listOf("sessions.list"), registry.descriptors().map { it.name })
            assertNotNull(descriptor)
            assertEquals("sessions.list", descriptor?.name)
            assertTrue(result.success)
            assertEquals(1, executionCount)
        }

    @Test
    fun `origin policy hides and blocks destructive tools from model and scheduled model`() =
        runTest {
            var deleteExecutionCount = 0
            val registry =
                ToolRegistry(
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "sessions.list",
                                        description = "List sessions",
                                    ),
                            ) { _, _ ->
                                ToolExecutionResult.success("listed", buildJsonObject {})
                            },
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "sessions.delete",
                                        description = "Delete a session",
                                    ),
                            ) { _, _ ->
                                deleteExecutionCount += 1
                                ToolExecutionResult.success("deleted", buildJsonObject {})
                            },
                        ),
                )

            val interactiveDescriptors =
                registry
                    .descriptorsFor(
                        origin = ToolInvocationOrigin.Model,
                        runMode = ModelRunMode.Interactive,
                    ).map { descriptor -> descriptor.name }
            val scheduledDescriptors =
                registry
                    .descriptorsFor(
                        origin = ToolInvocationOrigin.ScheduledModel,
                        runMode = ModelRunMode.Scheduled,
                    ).map { descriptor -> descriptor.name }
            val modelDelete =
                registry.execute(
                    context =
                        testToolContext(
                            requestedName = "sessions.delete",
                            origin = ToolInvocationOrigin.Model,
                            runMode = ModelRunMode.Interactive,
                        ),
                    arguments = buildJsonObject {},
                )
            val slashDelete =
                registry.execute(
                    context =
                        testToolContext(
                            requestedName = "sessions.delete",
                            origin = ToolInvocationOrigin.SlashCommand,
                            runMode = ModelRunMode.Interactive,
                        ),
                    arguments = buildJsonObject {},
                )

            assertEquals(listOf("sessions.list"), interactiveDescriptors)
            assertEquals(listOf("sessions.list"), scheduledDescriptors)
            assertFalse(modelDelete.success)
            assertEquals("TOOL_ORIGIN_NOT_ALLOWED", modelDelete.errorCode)
            assertEquals("Destructive", modelDelete.payload["riskTier"]?.jsonPrimitive?.content)
            assertTrue(slashDelete.success)
            assertEquals(1, deleteExecutionCount)
        }

    @Test
    fun `scheduled model descriptors omit local write tools while interactive model keeps them`() {
        val registry =
            ToolRegistry(
                tools =
                    listOf(
                        testEntry(
                            ToolDescriptor(
                                name = "tasks.create",
                                description = "Create task",
                            ),
                        ),
                        testEntry(
                            ToolDescriptor(
                                name = "tasks.list",
                                description = "List tasks",
                            ),
                        ),
                    ),
            )

        val interactiveNames =
            registry
                .descriptorsFor(
                    origin = ToolInvocationOrigin.Model,
                    runMode = ModelRunMode.Interactive,
                ).map { descriptor -> descriptor.name }
        val scheduledNames =
            registry
                .descriptorsFor(
                    origin = ToolInvocationOrigin.ScheduledModel,
                    runMode = ModelRunMode.Scheduled,
                ).map { descriptor -> descriptor.name }

        assertEquals(listOf("tasks.create", "tasks.list"), interactiveNames)
        assertEquals(listOf("tasks.list"), scheduledNames)
    }

    @Test
    fun `redactArguments removes secret-like fields recursively and honors sensitive argument specs`() {
        val registry =
            ToolRegistry(
                tools =
                    listOf(
                        testEntry(
                            ToolDescriptor(
                                name = "providers.configure",
                                description = "Configure provider",
                                arguments =
                                    listOf(
                                        ToolArgumentSpec(name = "endpoint"),
                                        ToolArgumentSpec(name = "plainSecretField", sensitive = true),
                                    ),
                            ),
                        ),
                    ),
            )

        val redaction =
            registry.redactArguments(
                toolName = "providers.configure",
                arguments =
                    buildJsonObject {
                        put("endpoint", "https://example.test")
                        put("apiKey", "sk-secret")
                        put("plainSecretField", "hidden")
                        put(
                            "nested",
                            buildJsonObject {
                                put("refreshToken", "refresh-secret")
                                put("safe", "visible")
                            },
                        )
                    },
            )

        assertEquals(
            "https://example.test",
            redaction.arguments
                .getValue("endpoint")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            "[REDACTED]",
            redaction.arguments
                .getValue("apiKey")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            "[REDACTED]",
            redaction.arguments
                .getValue("plainSecretField")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            "[REDACTED]",
            redaction.arguments
                .getValue("nested")
                .jsonObject
                .getValue("refreshToken")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            "visible",
            redaction.arguments
                .getValue("nested")
                .jsonObject
                .getValue("safe")
                .jsonPrimitive
                .content,
        )
        assertEquals(setOf("apiKey", "plainSecretField", "nested.refreshToken"), redaction.redactedKeys.toSet())
    }

    @Test
    fun `input schema reflects typed enum and sensitive argument metadata`() {
        val descriptor =
            ToolDescriptor(
                name = "typed.tool",
                description = "Typed schema",
                arguments =
                    listOf(
                        ToolArgumentSpec(
                            name = "enabled",
                            type = ToolArgumentType.Boolean,
                        ),
                        ToolArgumentSpec(
                            name = "mode",
                            type = ToolArgumentType.String,
                            enumValues = listOf("safe", "full"),
                        ),
                        ToolArgumentSpec(
                            name = "apiKey",
                            sensitive = true,
                        ),
                    ),
            )

        val properties = descriptor.inputSchema.getValue("properties").jsonObject

        assertEquals(
            "boolean",
            properties
                .getValue("enabled")
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            listOf("safe", "full"),
            properties
                .getValue("mode")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { value -> value.jsonPrimitive.content },
        )
        assertEquals(
            "true",
            properties
                .getValue("apiKey")
                .jsonObject
                .getValue("x-sensitive")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `typed argument validation rejects invalid type and enum values`() =
        runTest {
            val registry =
                ToolRegistry(
                    tools =
                        listOf(
                            testEntry(
                                ToolDescriptor(
                                    name = "typed.tool",
                                    description = "Typed tool",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "enabled",
                                                type = ToolArgumentType.Boolean,
                                                validate = true,
                                            ),
                                            ToolArgumentSpec(
                                                name = "mode",
                                                enumValues = listOf("safe", "full"),
                                                validate = true,
                                            ),
                                            ToolArgumentSpec(
                                                name = "config",
                                                type = ToolArgumentType.Object,
                                                validate = true,
                                            ),
                                        ),
                                ),
                            ),
                        ),
                )

            val result =
                registry.execute(
                    context = testToolContext("typed.tool"),
                    arguments =
                        buildJsonObject {
                            put("enabled", "maybe")
                            put("mode", "turbo")
                            put("config", "not-object")
                        },
                )

            val invalidArguments =
                result.payload
                    .getValue("invalidArguments")
                    .jsonArray
                    .map { value -> value.jsonPrimitive.content }

            assertFalse(result.success)
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
            assertTrue(invalidArguments.any { value -> value.contains("enabled expected Boolean") })
            assertTrue(invalidArguments.any { value -> value.contains("mode expected one of [safe, full]") })
            assertTrue(invalidArguments.any { value -> value.contains("config expected Object") })
        }

    @Test
    fun `registry rejects oversized tool identity metadata`() {
        val nameError =
            assertThrows(IllegalArgumentException::class.java) {
                ToolRegistry(
                    tools =
                        listOf(
                            testEntry(
                                ToolDescriptor(
                                    name = "t".repeat(TOOL_REGISTRY_NAME_MAX_CHARS + 1),
                                    description = "Oversized name",
                                ),
                            ),
                        ),
                )
            }
        val aliasError =
            assertThrows(IllegalArgumentException::class.java) {
                ToolRegistry(
                    tools =
                        listOf(
                            testEntry(
                                ToolDescriptor(
                                    name = "alias.tool",
                                    description = "Oversized alias",
                                    aliases = listOf("a".repeat(TOOL_REGISTRY_NAME_MAX_CHARS + 1)),
                                ),
                            ),
                        ),
                )
            }

        assertTrue(nameError.message.orEmpty().contains("Tool name"))
        assertTrue(aliasError.message.orEmpty().contains("Tool alias"))
    }

    @Test
    fun `registry rejects oversized tool argument metadata`() {
        val argumentListError =
            assertThrows(IllegalArgumentException::class.java) {
                ToolRegistry(
                    tools =
                        listOf(
                            testEntry(
                                ToolDescriptor(
                                    name = "argument-list.tool",
                                    description = "Too many arguments",
                                    arguments =
                                        (1..(TOOL_REGISTRY_ARGUMENT_LIST_MAX_ITEMS + 1)).map { index ->
                                            ToolArgumentSpec(name = "arg$index")
                                        },
                                ),
                            ),
                        ),
                )
            }
        val argumentNameError =
            assertThrows(IllegalArgumentException::class.java) {
                ToolRegistry(
                    tools =
                        listOf(
                            testEntry(
                                ToolDescriptor(
                                    name = "argument-name.tool",
                                    description = "Oversized argument",
                                    arguments =
                                        listOf(
                                            ToolArgumentSpec(
                                                name = "a".repeat(TOOL_REGISTRY_ARGUMENT_NAME_MAX_CHARS + 1),
                                            ),
                                        ),
                                ),
                            ),
                        ),
                )
            }

        assertTrue(argumentListError.message.orEmpty().contains("at most $TOOL_REGISTRY_ARGUMENT_LIST_MAX_ITEMS"))
        assertTrue(argumentNameError.message.orEmpty().contains("argument name"))
    }

    @Test
    fun `missing required arguments returns invalid arguments failure`() =
        runTest {
            var handlerCalled = false
            val registry =
                ToolRegistry(
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "notifications.post",
                                        description = "Post notification",
                                        arguments =
                                            listOf(
                                                ToolArgumentSpec(
                                                    name = "title",
                                                    required = true,
                                                ),
                                            ),
                                    ),
                            ) { _, _ ->
                                handlerCalled = true
                                ToolExecutionResult.success(
                                    summary = "posted",
                                    payload = buildJsonObject {},
                                )
                            },
                        ),
                )

            val result =
                registry.execute(
                    context = testToolContext("notifications.post"),
                    arguments = buildJsonObject {},
                )

            assertFalse(result.success)
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
            assertEquals(
                listOf("title"),
                result.payload["missingArguments"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
            assertFalse(handlerCalled)
        }

    @Test
    fun `missing argument failure bounds provided argument metadata`() =
        runTest {
            val oversizedArgumentName = "arg-" + "a".repeat(TOOL_REGISTRY_ARGUMENT_NAME_MAX_CHARS + 25)
            val registry =
                ToolRegistry(
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "metadata.tool",
                                        description = "Checks argument metadata bounds",
                                        arguments =
                                            listOf(
                                                ToolArgumentSpec(
                                                    name = "title",
                                                    required = true,
                                                ),
                                            ),
                                    ),
                            ) { _, _ ->
                                ToolExecutionResult.success(
                                    summary = "should not run",
                                    payload = buildJsonObject {},
                                )
                            },
                        ),
                )
            val arguments =
                buildJsonObject {
                    repeat(TOOL_REGISTRY_ARGUMENT_LIST_MAX_ITEMS + 10) { index ->
                        put("$oversizedArgumentName-$index", "value")
                    }
                }

            val result =
                registry.execute(
                    context = testToolContext("metadata.tool"),
                    arguments = arguments,
                )

            assertFalse(result.success)
            val providedArguments = result.payload["providedArguments"]?.jsonArray.orEmpty()
            assertEquals(TOOL_REGISTRY_ARGUMENT_LIST_MAX_ITEMS, providedArguments.size)
            assertTrue(
                providedArguments.all { argument ->
                    argument.jsonPrimitive.content.length == TOOL_REGISTRY_ARGUMENT_NAME_MAX_CHARS
                },
            )
            assertTrue(
                providedArguments
                    .first()
                    .jsonPrimitive
                    .content
                    .endsWith("… [truncated]"),
            )
            assertEquals(
                "10",
                result.payload["providedArgumentsOmitted"]?.jsonPrimitive?.content,
            )
        }

    @Test
    fun `permission blocked tool returns structured permission failure`() =
        runTest {
            var handlerCalled = false
            val loggedLevels = mutableListOf<EventLevel>()
            val registry =
                ToolRegistry(
                    eventLogger = { level, _, _ ->
                        loggedLevels += level
                    },
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "notifications.post",
                                        description = "Post notification",
                                        requiredPermissions =
                                            listOf(
                                                ToolPermissionRequirement(
                                                    permission = "android.permission.POST_NOTIFICATIONS",
                                                    displayName = "Post notifications",
                                                ),
                                            ),
                                    ),
                                availabilityProvider = {
                                    ToolAvailability(
                                        status = ToolAvailabilityStatus.PermissionRequired,
                                        reason = "Grant notification permission.",
                                    )
                                },
                            ) { _, _ ->
                                handlerCalled = true
                                ToolExecutionResult.success(
                                    summary = "posted",
                                    payload = buildJsonObject {},
                                )
                            },
                        ),
                )

            val result =
                registry.execute(
                    context = testToolContext("notifications.post"),
                    arguments = buildJsonObject {},
                )

            assertFalse(result.success)
            assertEquals("PERMISSION_REQUIRED", result.errorCode)
            assertEquals("Grant notification permission.", result.summary)
            assertEquals(
                "Post notifications",
                result.payload["requiredPermissions"]
                    ?.jsonArray
                    ?.single()
                    ?.jsonObject
                    ?.get("displayName")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertFalse(handlerCalled)
            assertEquals(listOf(EventLevel.Warn), loggedLevels)
        }

    @Test
    fun `foreground required tool returns structured failure`() =
        runTest {
            val registry =
                ToolRegistry(
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "camera.capture",
                                        description = "Capture an image",
                                        foregroundRequired = true,
                                    ),
                                availabilityProvider = {
                                    ToolAvailability(
                                        status = ToolAvailabilityStatus.ForegroundRequired,
                                        reason = "Open the app to use camera.capture.",
                                    )
                                },
                            ) { _, _ ->
                                ToolExecutionResult.success(
                                    summary = "captured",
                                    payload = buildJsonObject {},
                                )
                            },
                        ),
                )

            val result =
                registry.execute(
                    context = testToolContext("camera.capture"),
                    arguments = buildJsonObject {},
                )

            assertFalse(result.success)
            assertEquals("FOREGROUND_REQUIRED", result.errorCode)
            assertEquals("ForegroundRequired", result.payload["availabilityStatus"]?.jsonPrimitive?.content)
        }

    @Test
    fun `descriptors resolve live availability`() =
        runTest {
            var availability = ToolAvailability()
            val registry =
                ToolRegistry(
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "dynamic.tool",
                                        description = "Dynamic availability",
                                    ),
                                availabilityProvider = { availability },
                            ) { _, _ ->
                                ToolExecutionResult.success(
                                    summary = "ok",
                                    payload = buildJsonObject {},
                                )
                            },
                        ),
                )

            assertEquals(ToolAvailabilityStatus.Available, registry.findDescriptor("dynamic.tool")?.availability?.status)

            availability =
                ToolAvailability(
                    status = ToolAvailabilityStatus.DisabledByConfig,
                    reason = "Disabled in settings.",
                )

            val descriptor = registry.findDescriptor("dynamic.tool")
            val result =
                registry.execute(
                    context = testToolContext("dynamic.tool"),
                    arguments = buildJsonObject {},
                )

            assertEquals(ToolAvailabilityStatus.DisabledByConfig, descriptor?.availability?.status)
            assertEquals("DISABLED_BY_CONFIG", result.errorCode)
        }

    @Test
    fun `successful execution logs start and completion with safe context metadata`() =
        runTest {
            val loggedEvents = mutableListOf<Triple<EventLevel, String, String?>>()
            val registry =
                ToolRegistry(
                    eventLogger = { level, message, details ->
                        loggedEvents += Triple(level, message, details)
                    },
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "tasks.list",
                                        description = "List tasks",
                                    ),
                            ) { context, _ ->
                                assertEquals("session-1", context.sessionId)
                                assertEquals("run-1", context.taskRunId)
                                assertEquals(ToolInvocationOrigin.ScheduledModel, context.origin)
                                ToolExecutionResult.success(
                                    summary = "ok",
                                    payload = buildJsonObject {},
                                )
                            },
                        ),
                )

            val result =
                registry.execute(
                    context =
                        ToolExecutionContext(
                            sessionId = "session-1",
                            taskRunId = "run-1",
                            origin = ToolInvocationOrigin.ScheduledModel,
                            runMode = ai.androidclaw.runtime.providers.ModelRunMode.Scheduled,
                            requestedName = "tasks.list",
                            canonicalName = "tasks.list",
                            requestId = "req-1",
                            activeSkillId = "skill-1",
                        ),
                    arguments = buildJsonObject {},
                )

            assertTrue(result.success)
            assertEquals(2, loggedEvents.size)
            assertEquals(EventLevel.Info, loggedEvents[0].first)
            assertTrue(loggedEvents[0].second.contains("started"))
            assertTrue(loggedEvents[0].third.orEmpty().contains("\"sessionId\":\"session-1\""))
            assertEquals(EventLevel.Info, loggedEvents[1].first)
            assertTrue(loggedEvents[1].second.contains("completed"))
            assertTrue(loggedEvents[1].third.orEmpty().contains("\"success\":true"))
        }

    @Test
    fun `tool event logging bounds context metadata before serialization`() =
        runTest {
            val oversizedId = "id-" + "x".repeat(TOOL_REGISTRY_NAME_MAX_CHARS + 100)
            val loggedEvents = mutableListOf<Triple<EventLevel, String, String?>>()
            val registry =
                ToolRegistry(
                    eventLogger = { level, message, details ->
                        loggedEvents += Triple(level, message, details)
                    },
                    tools =
                        listOf(
                            ToolRegistry.Entry(
                                descriptor =
                                    ToolDescriptor(
                                        name = "bounded.log",
                                        description = "Checks log metadata bounds",
                                    ),
                            ) { _, _ ->
                                ToolExecutionResult.success(
                                    summary = "ok",
                                    payload = buildJsonObject {},
                                )
                            },
                        ),
                )

            registry.execute(
                context =
                    ToolExecutionContext.internal(
                        requestedName = "bounded.log",
                        sessionId = oversizedId,
                        taskRunId = oversizedId,
                        requestId = oversizedId,
                        activeSkillId = oversizedId,
                    ),
                arguments = buildJsonObject {},
            )

            assertEquals(2, loggedEvents.size)
            loggedEvents.forEach { (_, _, details) ->
                val serializedDetails = details.orEmpty()
                assertFalse(serializedDetails.contains(oversizedId))
                assertTrue(serializedDetails.contains("… [truncated]"))
                assertTrue(serializedDetails.length < 1_500)
            }
        }
}

private fun testEntry(descriptor: ToolDescriptor): ToolRegistry.Entry =
    ToolRegistry.Entry(descriptor = descriptor) { _, _ ->
        ToolExecutionResult.success(
            summary = "ok",
            payload = buildJsonObject {},
        )
    }

private fun testToolContext(
    requestedName: String,
    origin: ToolInvocationOrigin = ToolInvocationOrigin.Internal,
    runMode: ModelRunMode? = null,
): ToolExecutionContext =
    ToolExecutionContext(
        sessionId = null,
        taskRunId = null,
        origin = origin,
        runMode = runMode,
        requestedName = requestedName,
        canonicalName = requestedName,
        requestId = null,
    )
