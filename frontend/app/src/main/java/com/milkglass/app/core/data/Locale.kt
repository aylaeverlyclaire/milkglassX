package com.milkglass.app.core.data

import androidx.compose.runtime.Immutable

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.floor

@Serializable
@Immutable
data class LocaleData(
    val weekdaysShort: List<String>,
    val weekdaysLong: List<String>,
    val commentTemplates: List<String>,
    val monthsShort: List<String>,
    val legalDocs: Map<String, JsonArray>,
    val messages: Map<String, String>,
)

@Immutable
data class LegalSection(val heading: String, val body: String)
@Immutable
data class LegalDoc(val title: String, val sections: List<LegalSection>)

class Translator(val locale: LocaleData, private val onMissing: (String) -> Unit = {}) {

    fun t(key: String): String = locale.messages[key] ?: run {
        onMissing(key)
        ""
    }

    fun t(key: String, params: Map<String, Any?>): String {
        val m = locale.messages[key] ?: run {
            onMissing(key)
            return ""
        }
        var s = m
        for ((k, v) in params) s = s.replace("{$k}", jsToString(v))
        if (UNFILLED.containsMatchIn(s)) {
            onMissing(key)
            s = UNFILLED.replace(s, "").trim()
        }
        return s
    }

    private companion object {
        val UNFILLED = Regex("""\{[A-Za-z_]+\}""")
    }

    fun t(key: String, vararg params: Pair<String, Any?>): String = t(key, params.toMap())

    fun legalDoc(id: String): LegalDoc? {
        val a = locale.legalDocs[id] ?: return null
        val title = a[0].jsonPrimitive.content
        val sections = a[1].jsonArray.map { row ->
            val r = row.jsonArray
            LegalSection(r[0].jsonPrimitive.content, r[1].jsonPrimitive.content)
        }
        return LegalDoc(title, sections)
    }
}

fun jsToString(v: Any?): String = when (v) {
    null -> "null"
    is Double -> if (v == floor(v) && abs(v) < 1e21) v.toLong().toString() else v.toString()
    is Float -> jsToString(v.toDouble())
    is JsonPrimitive -> v.contentOrNull ?: "null"
    else -> v.toString()
}
