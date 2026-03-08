package ai.androidclaw.runtime.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    @Test
    fun `unknown tool returns structured failure instead of throwing`() = runTest {
        val registry = ToolRegistry(emptyList())

        val result = registry.execute(
            name = "missing.tool",
            arguments = buildJsonObject {},
        )

        assertFalse(result.success)
        assertEquals("Unknown tool: missing.tool", result.summary)
        assertEquals("UNKNOWN_TOOL", result.payload["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tool handler failure is converted into a structured result`() = runTest {
        val registry = ToolRegistry(
            tools = listOf(
                ToolRegistry.Entry(
                    descriptor = ToolDescriptor(
                        name = "boom.tool",
                        description = "Fails on purpose",
                    ),
                ) { _ ->
                    error("boom")
                },
            ),
        )

        val result = registry.execute(
            name = "boom.tool",
            arguments = buildJsonObject {
                put("hello", "world")
            },
        )

        assertFalse(result.success)
        assertTrue(result.summary.contains("boom"))
        assertEquals("TOOL_EXECUTION_FAILED", result.payload["error"]?.jsonPrimitive?.content)
    }
}
