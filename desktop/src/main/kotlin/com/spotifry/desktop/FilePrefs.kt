package com.spotifry.desktop

import com.spotifry.music.data.MemoryPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * [com.spotifry.music.data.Prefs] kept in a JSON file. Each value is written with its type, so an Int stays an Int and
 * a Float a Float, which the backup file needs. The file is replaced in one move, so a crash never leaves half of it.
 */
class FilePrefs(private val file: File) : MemoryPrefs() {
    init {
        runCatching {
            val o = JSONObject(file.readText())
            o.keys().forEach { k ->
                val v = o.getJSONObject(k)
                map[k] = when (v.getString("type")) {
                    "boolean" -> v.getBoolean("value"); "int" -> v.getInt("value"); "long" -> v.getLong("value")
                    "float" -> v.getDouble("value").toFloat(); "string" -> v.getString("value")
                    "set" -> v.getJSONArray("value").let { a -> (0 until a.length()).map(a::getString).toSet() }
                    else -> null
                }?.let { it } ?: return@forEach
            }
        }
    }

    @Synchronized override fun saved(): Boolean = runCatching {
        val o = JSONObject()
        map.forEach { (k, v) ->
            val (type, value) = when (v) {
                is Boolean -> "boolean" to v; is Int -> "int" to v; is Long -> "long" to v; is Float -> "float" to v.toDouble()
                is String -> "string" to v; is Set<*> -> "set" to JSONArray(v); else -> return@forEach
            }
            o.put(k, JSONObject().put("type", type).put("value", value))
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp").also { it.writeText(o.toString()) }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }.isSuccess
}
