package eu.kanade.tachiyomi.sourcenovel.model

import kotlinx.serialization.Serializable

@Serializable
sealed class ChapterContent {

    @Serializable
    data class Text(val paragraphs: List<String>) : ChapterContent() {
        fun fullText(): String = paragraphs.joinToString("\n\n")
        fun wordCount(): Int = paragraphs.sumOf { it.split(Regex("\\s+")).size }
        fun isEmpty(): Boolean = paragraphs.isEmpty() || paragraphs.all { it.isBlank() }
    }

    @Serializable
    data class Images(val urls: List<String>) : ChapterContent() {
        fun pageCount(): Int = urls.size
        fun isEmpty(): Boolean = urls.isEmpty()
    }

    @Serializable
    data class Mixed(val items: List<ContentItem>) : ChapterContent() {
        fun textItems(): List<String> = items.filterIsInstance<ContentItem.Text>().map { it.text }
        fun imageItems(): List<String> = items.filterIsInstance<ContentItem.Image>().map { it.url }
        fun isEmpty(): Boolean = items.isEmpty()
    }

    companion object {
        fun text(paragraphs: List<String>): ChapterContent = Text(paragraphs)
        fun text(content: String): ChapterContent = Text(
            content.split(Regex("\n{2,}")).filter { it.isNotBlank() }
        )
        fun images(urls: List<String>): ChapterContent = Images(urls)
        fun mixed(items: List<ContentItem>): ChapterContent = Mixed(items)
    }
}

@Serializable
sealed class ContentItem {
    @Serializable
    data class Text(val text: String) : ContentItem()

    @Serializable
    data class Image(val url: String) : ContentItem()
}
