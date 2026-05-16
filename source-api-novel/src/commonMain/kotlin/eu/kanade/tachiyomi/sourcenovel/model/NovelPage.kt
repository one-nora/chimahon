package eu.kanade.tachiyomi.sourcenovel.model

data class NovelPage(
    val novels: List<SNNovel>,
    val hasNextPage: Boolean,
) {
    fun isEmpty(): Boolean = novels.isEmpty()
    fun isNotEmpty(): Boolean = novels.isNotEmpty()
    val size: Int get() = novels.size

    companion object {
        fun empty(): NovelPage = NovelPage(emptyList(), false)
        fun lastPage(novels: List<SNNovel>): NovelPage = NovelPage(novels, false)
    }
}
