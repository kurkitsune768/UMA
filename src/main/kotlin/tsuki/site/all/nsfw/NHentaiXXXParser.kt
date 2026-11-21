package tsuki.site.all.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.GalleryAdultsParser

import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder

import tsuki.util.attrAsRelativeUrl
import tsuki.util.generateUid
import tsuki.util.mapToSet
import tsuki.util.parseHtml
import tsuki.util.removeSuffix
import tsuki.util.requireElementById
import tsuki.util.requireSrc
import tsuki.util.selectFirstOrThrow
import tsuki.util.src
import tsuki.util.toAbsoluteUrl
import tsuki.util.toTitleCase
import tsuki.util.urlEncoded

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("NHENTAIXXX", "NHentai.xxx", type = ContentType.HENTAI)
internal class NHentaiXXXParser(context: MangaLoaderContext) :
    GalleryAdultsParser(context, MangaParserSource.NHENTAIXXX, "nhentai.xxx", 25) {

    val supportedImageFormats = setOf("jpg", "webp", "jpeg", "png", "bmp")

    override val selectGallery = "div.galleries_box .gallery_item, #related-container .gallery_item"
    override val selectGalleryLink = "a"
    override val selectGalleryTitle = ".caption"
    override val pathTagUrl = "/tags/popular?page="
    override val selectTitle = "h1"
    override val selectTags = "div.tags_items"
    override val selectTag = ".tags:contains(Tags)"
    override val selectAuthor = ".tags:contains(Artists) span.tag_name"
    override val selectLanguageChapter =
        ".tags:contains(Languages) a:not([href=\"/language/translated/\"]) span.tag_name"
    override val idImg = "fimg"

    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.POPULARITY_TODAY)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isMultipleTagsSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions() = super.getFilterOptions().copy(
        availableLocales = setOf(Locale.ENGLISH, Locale.JAPANESE, Locale.CHINESE),
    )

    private val languageIds = mapOf("english" to 1, "japanese" to 2, "chinese" to 3)

    // "Popular Now" di beranda: hanya 5 komik, tanpa halaman berikutnya
    private val selectPopularNow = ".content_box:has(h2:contains(Popular Now)) .gallery_item"

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val tags = filter.tags
        val lang = filter.locale
        val query = filter.query
        val hasSearch = !query.isNullOrEmpty() || tags.isNotEmpty()

        if (order == SortOrder.POPULARITY_TODAY && !hasSearch) {
            if (page > 1) return emptyList()
            val doc = webClient.httpGet("https://$domain/").parseHtml()
            return toMangaList(doc.select(selectPopularNow), lang)
        }

        val popular = order == SortOrder.POPULARITY ||
            order == SortOrder.RELEVANCE ||
            order == SortOrder.POPULARITY_TODAY

        val url = buildString {
            append("https://")
            append(domain)

            when {
                // Bahasa saja (tanpa query/tag) -> halaman bahasa (urutan populer lewat path)
                lang != null && !hasSearch -> {
                    append("/language/")
                    append(lang.toLanguagePath())
                    append("/")
                    if (popular) append("popular/")
                    append("?")
                }

                // Query dan/atau tag -> search. Bahasa disaring dari data-languages di hasilnya,
                // karena search nhentai.xxx tidak menerima filter bahasa.
                else -> {
                    append("/search/?key=")

                    val parts = tags.map { it.title }.toMutableList()
                    if (!query.isNullOrEmpty()) parts.add(query.urlEncoded())
                    append(parts.joinToString("+"))

                    if (popular) append("&sort=popular")
                }
            }

            if (page > 1) {
                append(if (last() == '?') "page=" else "&page=")
                append(page.toString())
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        return toMangaList(doc.select(selectGallery), if (hasSearch) lang else null)
    }

    private suspend fun toMangaList(items: List<Element>, lang: Locale?): List<Manga> {
        return if (lang == null) {
            items.map { parseGalleryItem(it) }
        } else {
            filterByLanguage(items, lang.toLanguagePath())
        }
    }

    /**
     * Simpan hanya komik berbahasa [language]. Bahasa dibaca dari atribut data-languages
     * (1 = English, 2 = Japanese, 3 = Chinese). Kalau atributnya tidak ada, baru buka
     * halaman detail dan baca daftar bahasanya.
     */
    private suspend fun filterByLanguage(items: List<Element>, language: String): List<Manga> = coroutineScope {
        val languageId = languageIds[language]
        items.chunked(5).flatMap { chunk ->
            chunk.map { el ->
                async {
                    val ids = el.attr("data-languages").split(' ').mapNotNull { it.toIntOrNull() }
                    val manga = parseGalleryItem(el)
                    val keep = if (languageId != null && ids.isNotEmpty()) {
                        languageId in ids
                    } else {
                        val branch = runCatching { getDetails(manga).chapters?.firstOrNull()?.branch }.getOrNull()
                        branch?.contains(language, ignoreCase = true) == true
                    }
                    manga.takeIf { keep }
                }
            }.awaitAll().filterNotNull()
        }
    }

    override fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(selectGallery).map { parseGalleryItem(it) }
    }

    private fun parseGalleryItem(div: Element): Manga {
        val href = div.selectFirstOrThrow(selectGalleryLink).attrAsRelativeUrl("href")
        return Manga(
            id = generateUid(href),
            title = div.select(selectGalleryTitle).text().cleanupTitle(),
            altTitles = emptySet(),
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.ADULT,
            coverUrl = div.selectFirstOrThrow(selectGalleryImg).src(),
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            largeCoverUrl = null,
            description = null,
            chapters = null,
            source = source,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val totalPages = doc.selectFirstOrThrow(selectTotalPage).text().toInt()
        val firstPageUrl = doc.requireElementById(idImg).requireSrc()
        return (1..totalPages).map {
            val url = replacePageNumber(firstPageUrl, it)
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String {
        return if (hasError(page.url)) {
            findAlternativeUrl(page.url) ?: page.url
        } else {
            page.url
        }
    }

    private suspend fun hasError(url: String): Boolean {
        return try {
            webClient.httpHead(url)
            false
        } catch (_: Exception) {
            true
        }
    }

    private suspend fun findAlternativeUrl(originalUrl: String): String? {
        val lastSegment = originalUrl.substringAfterLast("/")
        val oldFormat = lastSegment.substringAfterLast(".", "")

        return supportedImageFormats.firstNotNullOfOrNull { format ->
            val newUrl = originalUrl.replace(".$oldFormat", ".$format")
            try {
                webClient.httpHead(newUrl)
                newUrl
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun Element.parseTags() = select("a").mapToSet {
        val key = it.attr("href").removeSuffix('/').substringAfterLast('/')
        val name = it.selectFirst(".tag_name")?.text() ?: it.text()
        MangaTag(
            key = key,
            title = name.toTitleCase(sourceLocale),
            source = source,
        )
    }

    private fun replacePageNumber(url: String, newPageNumber: Int): String {
        val lastSegment = url.substringAfterLast("/")
        val extension = lastSegment.substringAfterLast(".", "")

        return if (extension.isNotEmpty()) {
            url.substringBeforeLast("/") + "/$newPageNumber.$extension"
        } else {
            url.substringBeforeLast("/") + "/$newPageNumber"
        }
    }
}
