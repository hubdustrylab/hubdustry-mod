package com.hubdustry.mod.library

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object LibraryQueryEncoder {
    fun encode(query: LibraryQuery): String {
        val fields = linkedMapOf<String, String>()
        query.kind?.let { fields["kind"] = it.name }
        if (query.text.isNotBlank()) fields["q"] = query.text
        if (query.tags.isNotEmpty()) fields["tags"] = query.tags.joinToString(",")
        if (query.ownerMe) fields["owner"] = "me"
        query.state?.let { fields["state"] = it.name }
        fields["sort"] = query.sort.name.lowercase()
        query.minRank?.let { fields["minRank"] = it.name }
        fields["offset"] = query.offset.toString()
        fields["limit"] = query.limit.toString()
        return fields.entries.joinToString("&") { (key, value) ->
            "${escape(key)}=${escape(value)}"
        }
    }

    private fun escape(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
