package ireader.core.source.model

@Suppress("unused")
sealed class Filter<V>(val name: String, val initialValue: V) {
    var value = initialValue

    open fun isDefaultValue(): Boolean = initialValue == value
    fun reset() { value = initialValue }
    open fun isValid(): Boolean = true

    class Note(name: String) : Filter<Unit>(name, Unit)

    open class Text(name: String, value: String = "") : Filter<String>(name, value) {
        override fun isValid(): Boolean = value.length <= 200
    }

    open class Check(
        name: String,
        val allowsExclusion: Boolean = false,
        value: Boolean? = null,
    ) : Filter<Boolean?>(name, value)

    open class Select(
        name: String,
        val options: Array<String>,
        value: Int = 0,
    ) : Filter<Int>(name, value) {
        override fun isValid(): Boolean = value in options.indices
        fun getSelectedOption(): String? = options.getOrNull(value)
    }

    open class Group(name: String, val filters: List<Filter<*>>) : Filter<Unit>(name, Unit)

    open class Sort(
        name: String,
        val options: Array<String>,
        value: Selection? = null,
    ) : Filter<Sort.Selection?>(name, value) {
        data class Selection(val index: Int, val ascending: Boolean)
    }

    class Title(name: String = "Title") : Text(name)
    class Author(name: String = "Author") : Text(name)
    class Artist(name: String = "Artist") : Text(name)
    class Genre(name: String, allowsExclusion: Boolean = false) : Check(name, allowsExclusion)
}

typealias FilterList = List<Filter<*>>

sealed class Command<V>(val name: String, val initialValue: V) {
    var value = initialValue

    open fun isDefaultValue(): Boolean = value == initialValue
    fun reset() { value = initialValue }
    open fun getValueDescription(): String = value.toString()

    open class Fetchers(open val url: String = "", open val html: String = "") : Command<String>(url, html) {
        fun hasUrl(): Boolean = url.isNotBlank()
        fun hasHtml(): Boolean = html.isNotBlank()
        fun isValid(): Boolean = hasUrl() || hasHtml()
    }

    open class Note(name: String) : Command<Unit>(name, Unit)
    open class Text(name: String, value: String = "", val hint: String = "") : Command<String>(name, value)

    open class Select(
        name: String,
        open val options: Array<String>,
        value: Int = 0,
        open val description: String = "",
    ) : Command<Int>(name, value) {
        override fun getValueDescription(): String = options.getOrNull(value) ?: "Unknown"
    }

    open class Toggle(name: String, value: Boolean = false, open val description: String = "") : Command<Boolean>(name, value)
    open class Range(name: String, value: Int = 0, val min: Int = 0, val max: Int = 100, val step: Int = 1) : Command<Int>(name, value)

    object Detail {
        open class Fetch(override val url: String = "", override val html: String = "") : Fetchers(url, html)
    }

    object Content {
        open class Fetch(override val url: String = "", override val html: String = "") : Fetchers(url, html)
    }

    object Chapter {
        class Note(name: String) : Command.Note(name)
        open class Text(name: String, value: String = "") : Command.Text(name, value)
        open class Select(name: String, override val options: Array<String>, value: Int = 0) : Command.Select(name, options, value)
        open class Fetch(override val url: String = "", override val html: String = "") : Fetchers(url, html)
    }

    object Explore {
        open class Fetch(override val url: String = "", override val html: String = "") : Fetchers(url, html)
    }
}

typealias CommandList = List<Command<*>>
