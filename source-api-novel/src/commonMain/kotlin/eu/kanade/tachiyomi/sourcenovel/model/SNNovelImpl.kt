package eu.kanade.tachiyomi.sourcenovel.model

class SNNovelImpl : SNNovel {
    override var url: String = ""
    override var title: String = ""
    override var author: String? = null
    override var artist: String? = null
    override var description: String? = null
    override var genre: String? = null
    override var status: Int = SNNovel.UNKNOWN
    override var thumbnail_url: String? = null
    override var initialized: Boolean = false
}
