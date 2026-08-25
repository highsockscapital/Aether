package com.highsockscapital.sunshine.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SunshineModKernelTest {
    @Test
    fun higherPriorityServiceOverridesCoreAndCanBeRemoved() = runBlocking {
        val registry = SunshineModServiceRegistry()
        registry.register(
            id = "skills",
            owner = "sunshine-core",
            methods = listOf(SunshineModServiceMethod("list")),
            handler = { _, _ -> JSONObject().put("owner", "core") },
        )
        val unregisterMod = registry.register(
            id = "skills",
            owner = "demo-mod",
            priority = 100,
            methods = listOf(SunshineModServiceMethod("list")),
            handler = { _, _ -> JSONObject().put("owner", "mod") },
        )

        assertEquals(
            "mod",
            registry.invoke("skills", "list", JSONObject()).getString("owner"),
        )
        assertEquals("demo-mod", registry.describe("skills")?.owner)

        unregisterMod()

        assertEquals(
            "core",
            registry.invoke("skills", "list", JSONObject()).getString("owner"),
        )
        assertTrue(
            registry.listJson()
                .getJSONArray("services")
                .getJSONObject(0)
                .getJSONArray("methods")
                .length() == 1
        )
    }

    @Test
    fun operationInterceptorsChainByPriorityAndCanCancel() = runBlocking {
        val registry = SunshineModOperationRegistry()
        registry.register(
            operation = "chat.new",
            owner = "first",
            priority = 10,
            interceptor = { payload, _ ->
                SunshineModOperationDecision(
                    payload = payload.put("order", "first"),
                )
            },
        )
        registry.register(
            operation = "*",
            owner = "second",
            priority = 20,
            interceptor = { payload, _ ->
                SunshineModOperationDecision(
                    payload = payload.put(
                        "order",
                        payload.getString("order") + ",second",
                    ),
                    cancelled = true,
                    reason = "stopped",
                )
            },
        )

        val result = registry.intercept(
            operation = "chat.new",
            payload = JSONObject(),
            context = JSONObject(),
        )

        assertEquals("first,second", result.payload.getString("order"))
        assertTrue(result.cancelled)
        assertEquals("stopped", result.reason)
    }
}
