package com.fantasyanalyzer.data

import java.io.File

/** Saved ESPN responses, so the app can open instantly and skip data that can't change. */
interface JsonCache {
    /** The saved JSON for [key] if it's no older than [maxAgeMs]. */
    fun read(key: String, maxAgeMs: Long = Long.MAX_VALUE): String?
    fun write(key: String, json: String)
    /** When [key] was saved (epoch ms), or null if it wasn't. */
    fun savedAt(key: String): Long?
    fun clear()
}

/** One file per response under [dir] (app-private storage). */
class FileJsonCache(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) : JsonCache {
    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")

    override fun read(key: String, maxAgeMs: Long): String? {
        val f = file(key)
        if (!f.isFile) return null
        if (maxAgeMs != Long.MAX_VALUE && clock() - f.lastModified() > maxAgeMs) return null
        return try { f.readText() } catch (e: Exception) { null }
    }

    override fun write(key: String, json: String) {
        try {
            dir.mkdirs()
            // Write then rename, so a half-written file is never read back.
            val tmp = File(dir, file(key).name + ".tmp")
            tmp.writeText(json)
            tmp.renameTo(file(key))
        } catch (e: Exception) {
            // A cache that can't be written just means a slower next start.
        }
    }

    override fun savedAt(key: String): Long? = file(key).takeIf { it.isFile }?.lastModified()

    override fun clear() {
        dir.deleteRecursively()
    }
}
