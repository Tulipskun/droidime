package com.tulipskun.droidime

import org.json.JSONArray
import org.json.JSONObject

object ToolCatalog {
    fun definitions(): JSONArray = JSONArray().apply {
        put(tool("tap", "Tap a pixel coordinate on the current screen.", schema(
            "x" to pixel("horizontal pixel offset from the left edge, e.g. 540"),
            "y" to pixel("vertical pixel offset from the top edge, e.g. 600"),
            required = listOf("x", "y")
        )))
        put(tool("long_press", "Hold a screen coordinate for a duration. Defaults to 3 seconds.", schema(
            "x" to pixel("horizontal pixel offset from the left edge, e.g. 540"),
            "y" to pixel("vertical pixel offset from the top edge, e.g. 600"),
            "duration_ms" to integer("hold duration in milliseconds, default 3000, minimum 200"),
            required = listOf("x", "y")
        )))
        put(tool("swipe", "Swipe between pixel screen coordinates.", schema(
            "x1" to pixel("start horizontal pixel offset, e.g. 108"),
            "y1" to pixel("start vertical pixel offset, e.g. 1920"),
            "x2" to pixel("end horizontal pixel offset, e.g. 972"),
            "y2" to pixel("end vertical pixel offset, e.g. 480"),
            "duration_ms" to integer("duration in milliseconds"),
            required = listOf("x1", "y1", "x2", "y2", "duration_ms")
        )))
        put(tool("gesture", "Execute a custom touch path.", schema(
            "points" to pointArray(),
            required = listOf("points")
        )))
        put(tool("multi_touch", "Execute multiple concurrent touch paths.", schema(
            "pointers" to pointerArray(),
            required = listOf("pointers")
        )))
        put(tool("key_event", "Press, hold, or press multiple Android keys simultaneously. Use keycodes for chords such as Ctrl+A.", schema(
            "keycode" to integer("single Android keycode; backwards-compatible with the old form"),
            "keycodes" to integerArray("Android keycodes pressed together in order, e.g. [113, 29] for Ctrl+A"),
            "hold_ms" to integer("how long to hold the keys in milliseconds, default 0"),
            required = emptyList()
        )))
        put(tool("input_text", "Type text into the focused field.", schema(
            "text" to JSONObject().put("type", "string"),
            required = listOf("text")
        )))
        put(tool("screenshot", "Capture the current screen as PNG.", schema()))
        put(tool("click_sequence", "Tap several screen coordinates back to back in ONE call, with a fixed gap between taps. Use this for deterministic multi-tap paths (open menu, pick item, confirm) instead of separate tap calls, so the whole path costs one round trip.", schema(
            "sequence" to JSONObject()
                .put("type", "array")
                .put("minItems", 1)
                .put("description", "ordered list of [x, y] pixel pairs, e.g. [[540, 600], [540, 1440]]")
                .put(
                    "items",
                    JSONObject()
                        .put("type", "array")
                        .put("minItems", 2)
                        .put("maxItems", 2)
                        .put(
                            "items",
                            JSONObject()
                                .put("type", "integer")
                                .put("minimum", 0)
                        )
                ),
            "delay_ms" to integer("gap between taps in milliseconds, default 50"),
            required = listOf("sequence")
        )))
        put(tool("act_and_observe", "Run one action, wait for the UI to settle, then return a screenshot. Use only when you need the screen right after acting; prefer a bare action plus a separate screenshot when you do not.", schema(
            "action" to JSONObject()
                .put("type", "object")
                .put("description", "the action to run, e.g. {\"name\":\"tap\",\"x\":540,\"y\":1200}"),
            "settle_ms" to integer("how long to wait before capturing, default 400"),
            "include_image" to JSONObject().put("type", "boolean").put("description", "return a screenshot, default true"),
            required = listOf("action")
        )))
        put(tool("screen_size", "Return the screen width and height in pixels. Call this once before choosing coordinates.", schema()))
        put(tool("ui_hierarchy", "Dump the accessibility hierarchy as XML: text, resource ids and pixel bounds for every visible node. Cheaper than a screenshot and precise enough to tap by bounds.", schema()))
    }

    private fun pointArray(): JSONObject =
        JSONObject()
            .put("type", "array")
            .put(
                "items",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("x", pixel("horizontal pixel offset from the left edge, e.g. 540"))
                            .put("y", pixel("vertical pixel offset from the top edge, e.g. 600"))
                            .put("delay_ms", integer("delay after point"))
                    )
                    .put("required", JSONArray(listOf("x", "y", "delay_ms")))
            )

    private fun pointerArray(): JSONObject =
        JSONObject()
            .put("type", "array")
            .put(
                "items",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("id", integer("pointer id"))
                            .put("points", pointArray())
                    )
                    .put("required", JSONArray(listOf("id", "points")))
            )

    private fun integer(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)

    private fun integerArray(description: String): JSONObject =
        JSONObject()
            .put("type", "array")
            .put("items", JSONObject().put("type", "integer"))
            .put("minItems", 1)
            .put("description", description)
    private fun pixel(description: String): JSONObject =
        JSONObject()
            .put("type", "integer")
            .put("minimum", 0)
            .put("description", description)

    private fun schema(
        vararg properties: Pair<String, JSONObject>,
        required: List<String> = emptyList()
    ): JSONObject {
        val result = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject())

        val objectProperties = result.getJSONObject("properties")
        properties.forEach { objectProperties.put(it.first, it.second) }

        if (required.isNotEmpty()) {
            result.put("required", JSONArray(required))
        }

        return result
    }

    private fun tool(
        name: String,
        description: String,
        inputSchema: JSONObject
    ): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put("inputSchema", inputSchema)
}
