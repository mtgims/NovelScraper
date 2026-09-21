package com.novelscraper.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import retrofit2.HttpException

/** The server's error message (FastAPI's `{"detail": ...}` body), or null if the
 *  body is missing, not JSON, or has a blank detail. A non-string detail (a
 *  validation error list) comes back as its JSON text. */
fun HttpException.detail(): String? = try {
    response()?.errorBody()?.string()?.let { body ->
        when (val d = Json.parseToJsonElement(body).jsonObject["detail"]) {
            null -> null
            is JsonPrimitive -> d.content
            else -> d.toString()
        }?.ifBlank { null }
    }
} catch (_: Exception) { null }
