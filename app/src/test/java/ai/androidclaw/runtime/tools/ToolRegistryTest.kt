package ai.androidclaw.runtime.tools

import ai.androidclaw.data.model.EventLevel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
}

private fun testToolContext(requestedName: String): ToolExecutionContext = ToolExecutionContext.internal(requestedName = requestedName)
