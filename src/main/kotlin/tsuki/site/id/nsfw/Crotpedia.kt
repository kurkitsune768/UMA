package tsuki.site.id.nsfw

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl
import tsuki.util.toRelativeUrl
import tsuki.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

/**
 * Crotpedia (WordPress, tema khusus).
 *
 * Pola URL yang terlihat di situs:
 *  - Daftar terbaru : /page/N/            (bagian "Update Terbaru")
 *  - Pencarian      : /page/N/?s=kata
 *  - Genre          : /baca/genre/{slug}/ , daftar genre di /genre-list/
 *  - Detail series  : /baca/series/{slug}/
 *  - Chapter        : /baca/{slug}-chapter-N-bahasa-indonesia/
 *
 * Selector sengaja berbasis pola href/atribut (bukan nama class) karena class CSS
 * situs tidak terlihat. Belum diuji langsung di aplikasi.
 */
@MangaSourceParser("CROTPEDIA", "Crotpedia", "id", ContentType.HENTAI)
internal class Crotpedia(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.CROTPEDIA, 20) {

    override val configKeyDomain = ConfigKey.Domain("crotpedia.net")

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = false,
    )

    private val seriesMarker = "/baca/series/"
    private val genreMarker = "/baca/genre/"
    private val chapterDateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)
    private val chapterDateRegex = Regex("""([A-Za-z]+\s+\d{1,2},\s*\d{4})""")
    private var genreCache: Set<MangaTag>? = null

    // ---------------------------------------------------------------- filter

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = genreCache ?: fetchGenres().also { genreCache = it },
    )

    private suspend fun fetchGenres(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/genre-list/").parseHtml()
        val result = LinkedHashSet<MangaTag>()
        for (a in doc.select("a[href*=$genreMarker]")) {
            val key = a.attr("href").substringAfter(genreMarker).trim('/').substringBefore('/')
            val title = a.text().trim().replace(Regex("""\s*\(\d+\)$"""), "")
            if (key.isNotEmpty() && title.isNotEmpty()) result.add(MangaTag(title, key, source))
        }
        return result
    }

    // ------------------------------------------------------------------ list

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val tag = filter.tags.firstOrNull()
        val url = when {
            !filter.query.isNullOrEmpty() ->
                "https://$domain/page/$page/?s=${filter.query.urlEncoded()}"
            tag != null -> "https://$domain$genreMarker${tag.key}/page/$page/"
            else -> "https://$domain/page/$page/"
        }
        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        val all = doc.allElements
        // Beranda memuat "Sedang Populer" dan "Rekomendasi" sebelum "Update Terbaru";
        // ambil hanya yang setelah judul "Update Terbaru" bila ada.
        val heading = doc.select("h1, h2, h3, h4").firstOrNull {
            it.ownText().trim().equals("Update Terbaru", ignoreCase = true)
        }
        val startIndex = heading?.let { all.indexOf(it) } ?: -1
        val anchors = doc.select("a[href*=$seriesMarker]").filter { a ->
            val href = a.attr("href")
            href.substringAfter(seriesMarker).trim('/').isNotEmpty() &&
                (startIndex < 0 || all.indexOf(a) > startIndex)
        }

        val seen = LinkedHashMap<String, Manga>()
        for (a in anchors) {
            val href = a.hrefAbs()
            if (seen.containsKey(href)) continue
            val title = anchors.firstOrNull { it.hrefAbs() == href && it.hasAttr("title") }
                ?.attr("title")?.trim()
                ?: a.text().replace(Regex("""\s*(Doujinshi|Manga|Manhwa)?\s*18\+\s*$"""), "").trim()
            if (title.isEmpty()) continue
            val cover = findCover(a)
            seen[href] = Manga(
                id = generateUid(href),
                url = href.toRelativeUrl(domain),
                publicUrl = href,
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = cover.orEmpty(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
        return seen.values.toList()
    }

    private fun Element.hrefAbs(): String =
        absUrl("href").ifEmpty { attr("href").toAbsoluteUrl(domain) }

    private fun findCover(anchor: Element): String? {
        val scopes = listOfNotNull(anchor, anchor.parent(), anchor.parent()?.parent())
        for (scope in scopes) {
            val img = scope.selectFirst("img") ?: continue
            val src = listOf("data-src", "data-lazy-src", "src")
                .map { img.attr(it) }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:") && !it.endsWith(".gif") }
            if (src != null) return src
        }
        return null
    }

    // --------------------------------------------------------------- details

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val title = doc.title().removeSuffix(" - CrotPedia").trim().ifEmpty { manga.title }

        val cover = doc.select("img").firstOrNull { img ->
            img.attr("alt").equals(title, ignoreCase = true) && !img.attr("src").endsWith(".gif")
        }?.attr("src")?.takeIf { it.isNotBlank() } ?: manga.coverUrl

        val tags = doc.select("a[href*=$genreMarker]").mapNotNullTo(LinkedHashSet()) { a ->
            val key = a.attr("href").substringAfter(genreMarker).trim('/').substringBefore('/')
            val name = a.text().trim()
            if (key.isNotEmpty() && name.isNotEmpty()) MangaTag(name, key, source) else null
        }

        // Blok info (sebelum "Chapter List"): "Alternative ... Published ... Author ... Total Chapter ..."
        val info = doc.body().text().substringBefore("Chapter List")
        val altTitle = Regex("""Alternative\s*(.+?)\s*Published""").find(info)?.groupValues?.get(1)?.trim()
        val author = Regex("""Author\s*(.+?)\s*Total Chapter""").find(info)?.groupValues?.get(1)?.trim()
        val state = when {
            info.contains("Completed", ignoreCase = true) -> MangaState.FINISHED
            info.contains("Ongoing", ignoreCase = true) -> MangaState.ONGOING
            else -> null
        }

        val chapters = parseChapters(doc)

        return manga.copy(
            title = title,
            altTitles = setOfNotNull(altTitle?.takeIf { it.isNotEmpty() && it != title }),
            coverUrl = cover,
            tags = tags,
            state = state,
            authors = setOfNotNull(author?.takeIf { it.isNotEmpty() }),
            contentRating = ContentRating.ADULT,
            chapters = chapters,
        )
    }

    private fun parseChapters(doc: Document): List<MangaChapter> {
        val seen = LinkedHashMap<String, MangaChapter>()
        val anchors = doc.select("a[href*=/baca/][href*=-chapter-]").filterNot {
            it.attr("href").contains(seriesMarker) || it.attr("href").contains(genreMarker)
        }
        for (a in anchors) {
            val href = a.hrefAbs()
            if (seen.containsKey(href)) continue
            val text = a.text().trim()
            if (text.isEmpty()) continue
            val dateText = chapterDateRegex.find(text)?.value
            val name = (if (dateText != null) text.removeSuffix(dateText) else text).trim()
            val number = Regex("""(\d+(?:[.-]\d+)?)""").find(name)?.value
                ?.replace('-', '.')?.toFloatOrNull() ?: 0f
            seen[href] = MangaChapter(
                id = generateUid(href),
                title = name.ifEmpty { "Chapter $number" },
                number = number,
                volume = 0,
                url = href.toRelativeUrl(domain),
                scanlator = null,
                uploadDate = dateText?.let { runCatching { chapterDateFormat.parse(it)?.time }.getOrNull() } ?: 0L,
                branch = null,
                source = source,
            )
        }
        // Situs menampilkan chapter terbaru di atas; aplikasi mengharapkan urutan terlama dulu.
        return seen.values.toList().asReversed()
    }

    // ----------------------------------------------------------------- pages

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val imageExt = Regex("""\.(jpe?g|png|webp|avif)(\?.*)?$""", RegexOption.IGNORE_CASE)

        fun Element.imageUrl(): String? = listOf("data-src", "data-lazy-src", "src")
            .map { attr(it).trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("data:") }

        val all = doc.select("img").mapNotNull { it.imageUrl() }
        // Utama: host pembaca. Cadangan: semua gambar halaman selain avatar/cover/aset situs.
        var urls = all.filter { "reader." in it && imageExt.containsMatchIn(it) }
        if (urls.isEmpty()) {
            urls = all.filter {
                imageExt.containsMatchIn(it) &&
                    "gravatar" !in it && "cover." !in it && "wp-content" !in it && "donasi" !in it
            }
        }
        return urls.distinct().map { url ->
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }
}
