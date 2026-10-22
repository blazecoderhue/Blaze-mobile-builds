package com.blaze.assistant.mediaapps.store

/**
 * v0.33.0: YouTube back in the Music and Video Stores, built in. Search results come from YouTube's own search
 * (InnerTube WEB, videos only); playing and downloading go through the same working path as "Download by link"
 * ([LinkSource]'s VISIONOS-first clients with the deep stream probe), with the usual quality picker, and expired
 * download urls refresh the same way. Uses the id "youtube" so downloads queued by older builds refresh through it.
 */
object YouTubeStoreSource : StoreSource {
    override val id = "youtube"
    override val name = "YouTube"
    override val kinds = setOf("audio", "video")
    override val userAgent: String get() = LinkSource.userAgent

    override suspend fun search(kind: String, query: String, next: String): StorePage {
        val q = query.trim()
        if (q.isEmpty() && next.isEmpty()) return StorePage(emptyList(), "")
        val page = LinkParse.ytSearch(LinkYt.search(q, next), id)
        if (page.items.isEmpty() && next.isEmpty()) throw StoreError("YouTube found nothing for \u201c$q\u201d.")
        return page
    }

    private fun key(vid: String) = if (vid.startsWith("yt:")) vid else "yt:$vid"

    override suspend fun detail(item: StoreItem, kind: String): StoreDetail {
        val d = LinkSource.detail(item.copy(source = LinkSource.id, id = key(item.id)), kind)
        return d.copy(title = d.title.ifBlank { item.title }, author = d.author.ifBlank { item.author })
    }

    override fun choices(kind: String, d: StoreDetail): List<Choice> = LinkSource.choices(kind, d)
    override fun defaultChoice(kind: String, choices: List<Choice>): Choice? = LinkSource.defaultChoice(kind, choices)
    override suspend fun refresh(itemId: String, kind: String): StoreDetail? = LinkSource.refresh(key(itemId), kind)
}
