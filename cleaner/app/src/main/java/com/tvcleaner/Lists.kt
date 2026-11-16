package com.tvcleaner

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** One TV in the index of debloat lists. */
data class ListEntry(
    val id: String,
    val name: String,
    val match: Map<String, List<String>>?,
    val sdk: Int?,
    val file: String,
    val sha256: String,
)

data class ListIndex(
    val devices: List<ListEntry>,
    /** Other files by name ("labels", "protected"), as file path to checksum. */
    val files: Map<String, Pair<String, String>>,
)

data class ListApp(val pkg: String, val note: String?, val optional: Boolean)

data class ListGroup(val title: String, val apps: List<ListApp>)

data class TvList(
    val id: String,
    val name: String,
    val testedOs: String?,
    val sdk: Int?,
    val keep: Set<String>,
    val groups: List<ListGroup>,
)

/** The TV's own description of itself, from android.os.Build. */
data class TvInfo(val manufacturer: String, val brand: String, val model: String, val device: String, val sdk: Int)

/** Reads the lists published at github.com/man15h/tellyscope-devices (derived from cleanyourtv, MIT). */
object Lists {
    const val BASE = "https://raw.githubusercontent.com/man15h/tellyscope-devices/main/"
    private val FILE = Regex("(devices|lists)/[A-Za-z0-9][A-Za-z0-9._-]*\\.json")
    private val SHA = Regex("[0-9a-f]{64}")

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun parseIndex(text: String): ListIndex? = try {
        val o = JSONObject(text)
        if (o.optInt("schema") != 1) {
            null
        } else {
            val devices = mutableListOf<ListEntry>()
            val arr = o.optJSONArray("devices") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                val file = d.optString("file")
                val sha = d.optString("sha256")
                if (!FILE.matches(file) || !SHA.matches(sha)) continue
                devices += ListEntry(
                    id = d.optString("id"),
                    name = d.optString("name").ifBlank { d.optString("id") },
                    match = d.optJSONObject("match")?.let { m ->
                        m.keys().asSequence().associateWith { k -> m.optJSONArray(k).strings() }
                    },
                    sdk = if (d.isNull("sdk")) null else d.optInt("sdk"),
                    file = file,
                    sha256 = sha,
                )
            }
            val files = mutableMapOf<String, Pair<String, String>>()
            o.optJSONObject("lists")?.let { l ->
                for (k in l.keys()) {
                    val f = l.optJSONObject(k) ?: continue
                    val file = f.optString("file")
                    val sha = f.optString("sha256")
                    if (FILE.matches(file) && SHA.matches(sha)) files[k] = file to sha
                }
            }
            ListIndex(devices.sortedBy { it.name.lowercase() }, files)
        }
    } catch (_: Exception) {
        null
    }

    fun parseList(text: String): TvList? = try {
        val o = JSONObject(text)
        if (o.optInt("schema") != 1) {
            null
        } else {
            val tested = o.optJSONObject("tested")
            val groups = mutableListOf<ListGroup>()
            val ga = o.optJSONArray("groups") ?: JSONArray()
            for (i in 0 until ga.length()) {
                val g = ga.optJSONObject(i) ?: continue
                val apps = mutableListOf<ListApp>()
                val pa = g.optJSONArray("packages") ?: JSONArray()
                for (j in 0 until pa.length()) {
                    val p = pa.optJSONObject(j) ?: continue
                    val pkg = p.optString("pkg")
                    if (!Protect.PACKAGE.matches(pkg)) continue
                    apps += ListApp(pkg, p.optString("note").ifBlank { null }, p.optBoolean("optional"))
                }
                if (apps.isNotEmpty()) groups += ListGroup(cleanTitle(g.optString("title")), apps)
            }
            TvList(
                id = o.optString("id"),
                name = o.optString("name"),
                testedOs = tested?.optString("os")?.ifBlank { null },
                sdk = tested?.let { if (it.isNull("sdk")) null else it.optInt("sdk") },
                keep = o.optJSONArray("keep").strings().filter { Protect.PACKAGE.matches(it) }.toSet(),
                groups = groups,
            )
        }
    } catch (_: Exception) {
        null
    }

    /** "Preloaded apps (second unit; ...)" reads as "Preloaded apps". */
    fun cleanTitle(title: String): String {
        val t = title.substringBefore(" (").substringBefore(". ").trim()
        return t.ifBlank { "Other" }
    }

    /** Package to friendly name, from labels.json. */
    fun parseLabels(text: String): Map<String, String> = try {
        val out = mutableMapOf<String, String>()
        val groups = JSONObject(text).optJSONArray("groups") ?: JSONArray()
        for (i in 0 until groups.length()) {
            val labels = groups.optJSONObject(i)?.optJSONObject("labels") ?: continue
            for (k in labels.keys()) labels.optString(k).takeIf { it.isNotBlank() }?.let { out[k] = it }
        }
        out
    } catch (_: Exception) {
        emptyMap()
    }

    /** Package to why it must stay on (from "groups"), and package to a warning (from "warn"). */
    fun parseProtected(text: String): Pair<Map<String, String>, Map<String, String>> = try {
        val o = JSONObject(text)

        fun read(key: String, value: (String) -> String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            val arr = o.optJSONArray(key) ?: JSONArray()
            for (i in 0 until arr.length()) {
                val pk = arr.optJSONObject(i)?.optJSONObject("packages") ?: continue
                for (k in pk.keys()) if (Protect.PACKAGE.matches(k)) out[k] = value(pk.optString(k))
            }
            return out
        }
        read("groups") { "Needed by this TV" } to read("warn") { it }
    } catch (_: Exception) {
        emptyMap<String, String>() to emptyMap()
    }

    /** Lowercase letters and digits only, so "onn. 4K Box" and "onn4kbox" compare equal. */
    fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The list whose match rule fits this TV, or the single list named after its model and maker. */
    fun bestMatch(devices: List<ListEntry>, tv: TvInfo): ListEntry? {
        val props = mapOf("manufacturer" to tv.manufacturer, "brand" to tv.brand, "model" to tv.model, "device" to tv.device)
        devices.firstOrNull { e ->
            val rule = e.match
            !rule.isNullOrEmpty() && rule.all { (k, values) -> values.any { it.equals(props[k], ignoreCase = true) } }
        }?.let { return it }
        val model = squash(tv.model)
        if (model.length < 3) return null
        val fits = byMaker(devices, tv).filter { squash(it.name).contains(model) || squash(it.id).contains(model) }
        return fits.singleOrNull()
    }

    /** Lists from the same maker as this TV, by the first part of their id or name. */
    fun byMaker(devices: List<ListEntry>, tv: TvInfo): List<ListEntry> {
        val makers = setOf(squash(tv.manufacturer), squash(tv.brand)).filter { it.length >= 2 }
        return devices.filter { e ->
            val idMaker = squash(e.id.substringBefore('-'))
            val nameMaker = squash(e.name.substringBefore(' '))
            makers.any { it == idMaker || it == nameMaker }
        }
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }
}
