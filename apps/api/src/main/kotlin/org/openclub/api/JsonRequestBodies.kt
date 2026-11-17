package org.openclub.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Upper bound for every JSON request body accepted by the administration API. */
internal const val MAX_JSON_BODY_BYTES = 4096

/**
 * Reads a bounded `application/json` request body that must be exactly one JSON
 * object containing all [required] keys and nothing outside [required] and
 * [optional]. Every violation throws [IllegalArgumentException], which routes map
 * to 400. Bytes beyond the limit are never buffered, and the body is never logged.
 * Call this only after session, Origin and CSRF checks.
 */
internal suspend fun ApplicationCall.receiveJsonObject(
    required: Set<String>,
    optional: Set<String> = emptySet(),
): JsonObject {
    require(isJsonContentType(request.headers.getAll(HttpHeaders.ContentType))) { "Expected application/json" }
    return parseJsonObject(readBounded(receiveChannel(), MAX_JSON_BODY_BYTES), required, optional)
}

/** Exactly one Content-Type header whose media type is application/json (parameters allowed). */
internal fun isJsonContentType(values: List<String>?): Boolean {
    val value = values?.singleOrNull() ?: return false
    return runCatching { ContentType.parse(value).withoutParameters() == ContentType.Application.Json }
        .getOrDefault(false)
}

/** Reads at most [limit] bytes; a longer or empty body is rejected without reading further. */
internal suspend fun readBounded(channel: ByteReadChannel, limit: Int): ByteArray {
    val bytes = ByteArray(limit + 1)
    var size = 0
    while (size < bytes.size) {
        val count = channel.readAvailable(bytes, size, bytes.size - size)
        if (count == -1) break
        size += count
    }
    require(size in 1..limit) { "Body must be 1..$limit bytes" }
    return bytes.copyOf(size)
}

/** Parses UTF-8 JSON that must be an object with the allowed key set. */
internal fun parseJsonObject(bytes: ByteArray, required: Set<String>, optional: Set<String> = emptySet()): JsonObject {
    val fields = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        ?: throw IllegalArgumentException("Expected a JSON object")
    require(fields.keys.containsAll(required) && fields.keys.all { it in required || it in optional }) {
        "Unexpected or missing fields"
    }
    return fields
}

/** A field that must be a JSON string (not a number, boolean or null). */
internal fun JsonObject.requiredString(key: String): String {
    val value = this[key] as? JsonPrimitive ?: throw IllegalArgumentException("Missing field")
    require(value.isString) { "Expected a string" }
    return value.content
}
