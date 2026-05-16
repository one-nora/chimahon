package eu.kanade.tachiyomi.sourcenovel.model

class SNChapterImpl : SNChapter {
    override var url: String = ""
    override var name: String = ""
    override var date_upload: Long = 0L
    override var chapter_number: Float = -1F
    override var scanlator: String? = null
}
