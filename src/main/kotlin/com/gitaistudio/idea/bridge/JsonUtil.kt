package com.gitaistudio.idea.bridge

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 命令级故障:bridge 把 message 当 Err 字符串回给前端,前端 call<T> 弹红 toast。 */
class DispatchError(message: String) : RuntimeException(message)

object JsonUtil {
    val gson: Gson = Gson()

    fun parse(s: String): JsonElement = JsonParser.parseString(s)

    fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().apply {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    fun JsonObject.put(key: String, value: Any?) {
        when (value) {
            null -> add(key, com.google.gson.JsonNull.INSTANCE)
            is JsonElement -> add(key, value)
            is String -> addProperty(key, value)
            is Number -> addProperty(key, value)
            is Boolean -> addProperty(key, value)
            else -> add(key, gson.toJsonTree(value))
        }
    }

    fun arr(items: Iterable<JsonElement>): JsonArray = JsonArray().apply { items.forEach { add(it) } }

    fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString

    fun JsonObject.int(key: String, default: Int): Int =
        get(key)?.takeIf { !it.isJsonNull }?.asInt ?: default

    fun JsonObject.bool(key: String, default: Boolean): Boolean =
        get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: default

    fun JsonObject.strArray(key: String): List<String> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.asString } ?: emptyList()
}
