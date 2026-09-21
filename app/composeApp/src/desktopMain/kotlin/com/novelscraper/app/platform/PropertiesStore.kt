package com.novelscraper.app.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A [KeyValueStore] kept as a `.properties` file (one per store name, e.g.
 * ~/.config/novelscraper/ns.properties). Reads come from memory. Writes update
 * memory at once and are saved on a background thread, like SharedPreferences'
 * apply(); each save writes the whole file to a temp file and moves it into place,
 * so a crash never leaves a half-written file. Files are readable by the user only
 * (the "ns" store holds the login cookie). String sets are stored as JSON arrays.
 */
class PropertiesStore(private val file: File) : KeyValueStore {
    private val props = Properties()

    init {
        if (file.isFile) runCatching { file.inputStream().use { props.load(it) } }
    }

    private fun get(key: String): String? = synchronized(props) { props.getProperty(key) }

    override fun getString(key: String, default: String?): String? = get(key) ?: default
    override fun getFloat(key: String, default: Float) = get(key)?.toFloatOrNull() ?: default
    override fun getInt(key: String, default: Int) = get(key)?.toIntOrNull() ?: default
    override fun getBoolean(key: String, default: Boolean) = get(key)?.toBooleanStrictOrNull() ?: default

    override fun getStringSet(key: String): Set<String>? = get(key)?.let { raw ->
        runCatching { Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }.toSet() }.getOrNull()
    }

    override fun putString(key: String, value: String) = put(key, value)
    override fun putFloat(key: String, value: Float) = put(key, value.toString())
    override fun putInt(key: String, value: Int) = put(key, value.toString())
    override fun putBoolean(key: String, value: Boolean) = put(key, value.toString())
    override fun putStringSet(key: String, value: Set<String>) =
        put(key, JsonArray(value.map(::JsonPrimitive)).toString())

    override fun remove(key: String) {
        synchronized(props) { props.remove(key) }
        scheduleSave()
    }

    private fun put(key: String, value: String) {
        synchronized(props) { props.setProperty(key, value) }
        scheduleSave()
    }

    private fun scheduleSave() {
        writer.execute(::save)
    }

    private fun save() {
        val snapshot = synchronized(props) { Properties().also { it.putAll(props) } }
        runCatching {
            file.parentFile.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.outputStream().use { snapshot.store(it, null) }
            restrictToOwner(tmp)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { Log.w("PropertiesStore", "couldn't save ${file.name}: ${it.message}") }
    }

    private fun restrictToOwner(f: File) {
        runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) }
    }

    companion object {
        // One writer for every store: saves run in order, off the UI thread.
        private val writer = Executors.newSingleThreadExecutor { r ->
            Thread(r, "settings-writer").apply { isDaemon = true }
        }

        /** Wait for pending saves (call before the app exits). */
        fun flush(timeoutMs: Long = 2000) {
            val done = java.util.concurrent.CountDownLatch(1)
            writer.execute { done.countDown() }
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }
}
