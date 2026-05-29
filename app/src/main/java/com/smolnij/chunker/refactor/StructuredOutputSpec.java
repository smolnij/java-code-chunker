package com.smolnij.chunker.refactor;

import com.google.gson.JsonObject;

/**
 * Describes how the chat endpoint should constrain its reply.
 *
 * <p>Three supported modes map to OpenAI-compatible fields on
 * {@code /v1/chat/completions}:
 * <ul>
 *   <li>{@link Mode#JSON_SCHEMA} — sets
 *       {@code response_format = {"type":"json_schema","json_schema":{...}}};
 *       LM-Studio enforces the schema server-side.</li>
 *   <li>{@link Mode#JSON_OBJECT} — sets
 *       {@code response_format = {"type":"json_object"}};
 *       the reply is guaranteed valid JSON but not schema-checked.</li>
 *   <li>{@link Mode#TOOL_CALL} — registers a single tool whose parameters
 *       are the schema and forces the model to call it. The structured
 *       output is read from {@code choices[0].message.tool_calls[0].function.arguments}.
 *       Fallback for models without a native JSON mode.</li>
 * </ul>
 */
public record StructuredOutputSpec(String name, JsonObject jsonSchema, Mode preferredMode) {

    public enum Mode { JSON_SCHEMA, JSON_OBJECT, TOOL_CALL }

    public StructuredOutputSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name required");
        }
        if (jsonSchema == null) {
            throw new IllegalArgumentException("jsonSchema required");
        }
        if (preferredMode == null) {
            throw new IllegalArgumentException("preferredMode required");
        }
    }
}
