package com.deskwoot.sdk.model

import org.json.JSONObject

/** A collection of the help center. The list is flat: subcategories follow their parent. */
internal data class HelpCollection(
    val id: String,
    val slug: String?,
    val name: String,
    val description: String?,
    val articleCount: Int,
) {
    internal companion object {
        /** Entries without an id or a name, or without articles, are dropped as the web does. */
        fun parse(json: JSONObject): HelpCollection? {
            val id = json.text("id") ?: return null
            val name = json.text("name") ?: return null
            val count = json.int("articleCount") ?: 0
            if (count <= 0) return null
            return HelpCollection(id, json.text("slug"), name, json.text("description"), count)
        }
    }
}

/** An article in a list: suggested, a collection or search results. */
internal data class HelpArticleSummary(val id: String, val title: String, val slug: String, val description: String?) {
    internal companion object {
        fun parse(json: JSONObject): HelpArticleSummary? {
            val id = json.text("id") ?: return null
            val title = json.text("title") ?: return null
            val slug = json.text("slug") ?: return null
            return HelpArticleSummary(id, title, slug, json.text("description"))
        }
    }
}

internal data class HelpCategory(val id: String, val name: String, val slug: String?)

/**
 * One article with its body. [content] is the help center
 * editor's HTML, not Markdown; [locale] is the help center
 * language of the widget, only for the "open in help center" link.
 */
internal data class HelpArticle(
    val id: String,
    val title: String,
    val slug: String,
    val locale: String?,
    val content: String,
    val description: String?,
    val views: Int,
    val createdAt: Long?,
    val updatedAt: Long?,
    val authorName: String?,
    val category: HelpCategory?,
) {
    internal companion object {
        fun parse(json: JSONObject): HelpArticle? {
            val id = json.text("id") ?: return null
            val title = json.text("title") ?: return null
            val slug = json.text("slug") ?: return null
            val category = json.obj("category")?.let { c ->
                val cid = c.text("id")
                val name = c.text("name")
                if (cid != null && name != null) HelpCategory(cid, name, c.text("slug")) else null
            }
            return HelpArticle(
                id = id,
                title = title,
                slug = slug,
                locale = json.text("locale"),
                content = json.string("content") ?: "",
                description = json.text("description"),
                views = json.int("views") ?: 0,
                createdAt = IsoTime.parse(json.string("createdAt")),
                updatedAt = IsoTime.parse(json.string("updatedAt")),
                authorName = json.text("authorName"),
                category = category,
            )
        }
    }
}
