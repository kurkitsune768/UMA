package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
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
import tsuki.util.json.mapJSON
import tsuki.util.json.mapJSONNotNull
import tsuki.util.parseJson

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.HttpStatusException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.EnumSet

/*
 * CATATAN (baca sebelum build lagi):
 * File ini ditulis ulang dengan meniru persis struktur PhenixScans.kt
 * (contoh parser yang sudah terbukti berhasil compile di project ini),
 * jadi sudah jauh lebih akurat dibanding draft pertama.
 *
 * Dua hal yang MASIH tebakan dan paling mungkin bikin build gagal lagi:
 * 1. Di fetchAllManga(), response GitHub berupa JSON ARRAY (bukan objek),
 *    jadi saya baca body-nya langsung lewat "webClient.httpGet(...).body?.string()"
 *    (mengasumsikan httpGet mengembalikan okhttp3.Response). Kalau tipe
 *    return-nya beda, error compile bakal nunjuk persis ke baris itu.
 * 2. Nama field exact di constructor Manga/MangaChapter (altTitles,
 *    contentRating, dll) - saya copy persis dari PhenixScans jadi
 *    seharusnya sudah benar, tapi kalau versi Mikoroku butuh field
 *    tambahan/beda, compiler bakal kasih tahu.
 *
 * Kalau masih ada error compile, kirim lagi log-nya - kita perbaiki lagi.
 */

private const val GITHUB_RAW = "https://raw.githubusercontent.com/moemaomao/mymangadata/main/"
private const val MANGA_LIST_URL = GITHUB_RAW + "all-manga.json"
private const val CHAPTER_FEED_BASE = "https://www.mikodrive.my.id/feeds/posts/default"
private const val PAGE_SIZE = 24
private const val CACHE_MS = 30 * 60 * 1000L
private const val FIRESTORE_SUMMARY_URL =
    "https://firestore.googleapis.com/v1/projects/widget-comment/databases/(default)/documents/meta/mangaSummary" +
        "?key=AIzaSyCxufJubuS95zQnvctINvzj-H8WSIgsse4"

private const val FIRESTORE_COLLECTION_URL =
    "https://firestore.googleapis.com/v1/projects/widget-comment/databases/(default)/documents/manga" +
        "?key=AIzaSyCxufJubuS95zQnvctINvzj-H8WSIgsse4"
private val FIRESTORE_FIELDS = listOf(
    "title", "cover", "img", "status", "updatedAt", "latestUpdate", "isDraft", "githubSlug",
)

private class Entry(val manga: Manga, val updatedAt: Long)

private class SummaryItem(
    val slug: String,
    val githubSlug: String,
    val title: String,
    val cover: String?,
    val status: String,
    val updatedAt: Long,
)

private val CHAPTER_NUM_REGEX = Regex("""chapter\s*(\d+(\.\d+)?)""", RegexOption.IGNORE_CASE)
private val IMG_SRC_REGEX = Regex("""<img[^>]+(?:src|data-src)=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)

@MangaSourceParser("MIKOROKU", "Mikoroku", "id")
internal class Mikoroku(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MIKOROKU, PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("mikoroku.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
    )

    private val cacheMutex = Mutex()

    @Volatile
    private var cache: List<Entry>? = null

    @Volatile
    private var cacheTime = 0L

    // Nomor halaman dari app bisa mulai dari 0 atau 1 tergantung library.
    // Halaman pertama selalu diminta lebih dulu, jadi kalau pernah muncul page == 0 berarti mulai dari 0.
    @Volatile
    private var zeroBasedPages = false

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = loadEntries()
            .flatMap { it.manga.tags }
            .distinctBy { it.key }
            .sortedBy { it.title }
            .toSet()
        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.PAUSED,
                MangaState.ABANDONED,
            ),
        )
    }

    // ================= DAFTAR MANGA (GitHub JSON) =================

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val all = loadEntries()

        val query = filter.query.orEmpty()
        val tagKeys = filter.tags.map { it.key }
        val states = filter.states

        val filtered = all.filter { e ->
            (query.isBlank() || matchesQuery(e.manga, query)) &&
                (tagKeys.isEmpty() || e.manga.tags.map { it.key }.containsAll(tagKeys)) &&
                (states.isEmpty() || e.manga.state?.let { it in states } == true)
        }

        val sorted = when (order) {
            SortOrder.ALPHABETICAL -> filtered.sortedBy { it.manga.title }
            // "Terbaru": ikut urutan file JSON (hasil tes: judul terbaru sudah ada di atas)
            SortOrder.NEWEST -> filtered
            // "Populer": data tidak punya jumlah views, jadi dipakai rating tertinggi
            SortOrder.POPULARITY -> filtered.sortedByDescending { it.manga.rating }
            // "Baru diperbarui": tanggal chapter terbaru
            else -> filtered.sortedByDescending { it.updatedAt }
        }

        if (page == 0) zeroBasedPages = true
        val pageIndex = if (zeroBasedPages) page else (page - 1).coerceAtLeast(0)
        val from = pageIndex * PAGE_SIZE
        if (from >= sorted.size) return emptyList()
        val to = minOf(from + PAGE_SIZE, sorted.size)
        return sorted.subList(from, to).map { it.manga }
    }

    // Gabungan GitHub JSON + ringkasan Firestore (sama seperti yang dilakukan web-nya)
    private suspend fun loadEntries(): List<Entry> {
        cache?.let { if (System.currentTimeMillis() - cacheTime < CACHE_MS) return it }
        return cacheMutex.withLock {
            val cached = cache
            if (cached != null && System.currentTimeMillis() - cacheTime < CACHE_MS) {
                cached
            } else {
                buildEntries().also {
                    cache = it
                    cacheTime = System.currentTimeMillis()
                }
            }
        }
    }

    private suspend fun buildEntries(): List<Entry> {
        val github = fetchAllManga()
        val summary = fetchSummary()
        val blogger = fetchBloggerUpdates()

        val bySlug = HashMap<String, SummaryItem>()
        val byTitle = HashMap<String, SummaryItem>()
        for (s in summary) {
            bySlug[s.slug] = s
            if (s.githubSlug.isNotEmpty()) bySlug[s.githubSlug] = s
            byTitle[normalizeKey(s.title)] = s
        }

        val used = HashSet<SummaryItem>()
        val result = ArrayList<Entry>(github.size + summary.size)

        for (m in github) {
            val hit = bySlug[m.url] ?: byTitle[normalizeKey(m.title)]
            if (hit != null) used.add(hit)
            val time = maxOf(hit?.updatedAt ?: 0L, blogger[normalizeKey(m.title)] ?: 0L)
            result += Entry(m, time)
        }

        // Manga yang hanya ada di Firestore (bagian "Random Doujin" / hasil upload admin)
        for (s in summary) {
            if (s in used) continue
            result += Entry(summaryToManga(s), s.updatedAt)
        }
        return result
    }

    private suspend fun fetchSummary(): List<SummaryItem> {
        return try {
            parseSummaryDoc(webClient.httpGet(FIRESTORE_SUMMARY_URL).parseJson())
        } catch (e: HttpStatusException) {
            // 404 = dokumen ringkasan belum ada -> baca koleksi (boros kuota, hanya kalau terpaksa).
            // 429 (kuota habis), 403, dll -> jangan diulang, cukup pakai GitHub + Blogger.
            if (e.statusCode == 404) fetchSummaryFromCollection() else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseSummaryDoc(json: JSONObject): List<SummaryItem> {
        val values = json.optJSONObject("fields")
            ?.optJSONObject("list")
            ?.optJSONObject("arrayValue")
            ?.optJSONArray("values") ?: return emptyList()
        val result = ArrayList<SummaryItem>(values.length())
        for (i in 0 until values.length()) {
            val f = values.optJSONObject(i)?.optJSONObject("mapValue")?.optJSONObject("fields") ?: continue
            val slug = f.fsString("slug") ?: continue
            toSummaryItem(slug, f)?.let { result += it }
        }
        return result
    }

    // Tanggal update per seri dari feed Blogger (tidak kena kuota Firestore).
    // Setiap chapter = 1 postingan; ambil tanggal terbit terbaru per judul seri.
    private suspend fun fetchBloggerUpdates(): Map<String, Long> {
        val result = HashMap<String, Long>()
        try {
            for (pageIndex in 0 until 2) {
                val url = "$CHAPTER_FEED_BASE?alt=json&max-results=500&start-index=${pageIndex * 500 + 1}"
                val entries = webClient.httpGet(url).parseJson()
                    .optJSONObject("feed")?.optJSONArray("entry") ?: break
                for (i in 0 until entries.length()) {
                    val e = entries.optJSONObject(i) ?: continue
                    val title = e.optJSONObject("title")?.optString("\$t") ?: continue
                    val match = CHAPTER_NUM_REGEX.find(title) ?: continue
                    val key = normalizeKey(title.substring(0, match.range.first))
                    if (key.isEmpty()) continue
                    val ts = parseIsoMillis(e.optJSONObject("published")?.optString("\$t"))
                    if (ts > (result[key] ?: 0L)) result[key] = ts
                }
                if (entries.length() < 500) break
            }
        } catch (_: Exception) {
            // feed gagal -> urutan jatuh ke urutan file JSON
        }
        return result
    }

    private fun parseIsoMillis(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return try {
            java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    private suspend fun fetchSummaryFromCollection(): List<SummaryItem> {
        val result = ArrayList<SummaryItem>()
        var pageToken: String? = null
        var guard = 0
        try {
            do {
                val url = buildString {
                    append(FIRESTORE_COLLECTION_URL)
                    append("&pageSize=300")
                    for (field in FIRESTORE_FIELDS) append("&mask.fieldPaths=").append(field)
                    pageToken?.let { append("&pageToken=").append(URLEncoder.encode(it, "UTF-8")) }
                }
                val json = webClient.httpGet(url).parseJson()
                val docs = json.optJSONArray("documents")
                if (docs != null) {
                    for (i in 0 until docs.length()) {
                        val d = docs.optJSONObject(i) ?: continue
                        val f = d.optJSONObject("fields") ?: continue
                        val slug = d.optString("name").substringAfterLast('/')
                        if (slug.isEmpty()) continue
                        toSummaryItem(slug, f)?.let { result += it }
                    }
                }
                pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
                guard++
            } while (pageToken != null && guard < 10)
        } catch (_: Exception) {
            // kalau Firestore gagal, daftar tetap jalan dari GitHub saja
        }
        return result
    }

    private fun toSummaryItem(slug: String, f: JSONObject): SummaryItem? {
        if (f.optJSONObject("isDraft")?.optBoolean("booleanValue") == true) return null
        return SummaryItem(
            slug = slug,
            githubSlug = f.fsString("githubSlug").orEmpty(),
            title = f.fsString("title") ?: slug,
            cover = f.fsString("cover") ?: f.fsString("img"),
            status = f.fsString("status").orEmpty(),
            updatedAt = f.fsMillis("updatedAt").takeIf { it > 0 } ?: f.fsMillis("latestUpdate"),
        )
    }

    private fun parseState(raw: String): MangaState? = when (raw.trim().lowercase()) {
        "ongoing" -> MangaState.ONGOING
        "completed" -> MangaState.FINISHED
        "hiatus" -> MangaState.PAUSED
        "dropped" -> MangaState.ABANDONED
        else -> null
    }

    // Cocokkan judul + judul alternatif; toleran terhadap tanda baca / spasi / huruf besar
    private fun matchesQuery(m: Manga, query: String): Boolean {
        val hay = buildString {
            append(m.title)
            for (t in m.altTitles) {
                append(' ')
                append(t)
            }
        }
        if (hay.contains(query, ignoreCase = true)) return true
        val compactQuery = normalizeKey(query)
        if (compactQuery.isNotEmpty() && normalizeKey(hay).contains(compactQuery)) return true
        val tokens = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        val words = hay.lowercase().replace(Regex("[^a-z0-9]+"), " ")
        return tokens.all { words.contains(it) }
    }

    private fun summaryToManga(s: SummaryItem): Manga = Manga(
        id = generateUid(s.slug),
        title = s.title,
        altTitles = emptySet(),
        url = s.slug,
        publicUrl = "https://mikoroku.com/detail?slug=${s.slug}",
        rating = RATING_UNKNOWN,
        contentRating = null,
        coverUrl = resolveCover(s.cover),
        tags = emptySet(),
        state = parseState(s.status),
        authors = emptySet(),
        largeCoverUrl = null,
        description = null,
        source = source,
    )

    private fun normalizeKey(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun JSONObject.fsString(name: String): String? =
        optJSONObject(name)?.optString("stringValue")?.takeIf { it.isNotEmpty() }

    private fun JSONObject.fsMillis(name: String): Long {
        val v = optJSONObject(name) ?: return 0L
        fun norm(n: Long) = if (n in 1 until 1_000_000_000_000L) n * 1000 else n
        v.optString("integerValue").toLongOrNull()?.let { return norm(it) }
        if (v.has("doubleValue")) return norm(v.optDouble("doubleValue").toLong())
        val ts = v.optString("timestampValue")
        if (ts.isNotEmpty()) {
            return try {
                java.time.Instant.parse(ts).toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
        return 0L
    }

    private suspend fun fetchAllManga(): List<Manga> {
        val text = webClient.httpGet(MANGA_LIST_URL).body?.string().orEmpty()
        val arr = JSONArray(text)
        return arr.mapJSON(::parseManga)
    }

    private fun parseManga(jo: JSONObject): Manga {
        val title = jo.optString("title")
        val slug = jo.optString("slug").ifBlank { slugify(title) }
        return Manga(
            id = generateUid(slug),
            title = title,
            altTitles = jo.optString("altTitle").split(';').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            url = slug,
            publicUrl = "https://mikoroku.com/detail?slug=$slug",
            rating = parseRating(jo),
            contentRating = null,
            coverUrl = resolveCover(jo.optString("cover").ifBlank { jo.optString("img") }),
            tags = parseGenres(jo.optJSONArray("genres")),
            state = parseState(jo.optString("status")),
            authors = setOfNotNull(jo.optString("author").ifBlank { null }),
            largeCoverUrl = null,
            description = jo.optString("desc").ifBlank { null },
            source = source,
        )
    }

    private fun parseRating(jo: JSONObject): Float {
        val r = jo.optDouble("rating", Double.NaN)
        return if (!r.isNaN() && r > 0.0) (r / 10.0).toFloat().coerceIn(0f, 1f) else RATING_UNKNOWN
    }

    private fun parseGenres(arr: JSONArray?): Set<MangaTag> {
        if (arr == null) return emptySet()
        val out = LinkedHashSet<MangaTag>()
        for (i in 0 until arr.length()) {
            val g = arr.optString(i).trim()
            if (g.isEmpty()) continue
            out += MangaTag(key = g.lowercase(), title = g, source = source)
        }
        return out
    }

    private fun resolveCover(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.trim() == "-") return null
        val cleaned = raw.trim().removeSuffix(".")
        return if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)) {
            cleaned
        } else {
            GITHUB_RAW + cleaned.removePrefix("/").replace(" ", "%20")
        }
    }

    private fun slugify(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
    }

    // ================= DETAIL + CHAPTER LIST (Blogger feed) =================

    override suspend fun getDetails(manga: Manga): Manga {
        val feed = fetchChapterFeed(manga.title)
        val chapters = parseChaptersFromFeed(feed)
        return manga.copy(chapters = chapters)
    }

    private suspend fun fetchChapterFeed(seriesTitle: String): JSONObject {
        val query = URLEncoder.encode(seriesTitle, "UTF-8")
        val url = "$CHAPTER_FEED_BASE?alt=json&max-results=500&q=$query"
        return webClient.httpGet(url).parseJson()
    }

    private fun parseChaptersFromFeed(feed: JSONObject): List<MangaChapter> {
        val entries = feed.optJSONObject("feed")?.optJSONArray("entry") ?: JSONArray()
        return entries.mapJSONNotNull { entry ->
            val entryTitle = entry.optJSONObject("title")?.optString("\$t") ?: return@mapJSONNotNull null
            if (!entryTitle.contains("chapter", ignoreCase = true)) return@mapJSONNotNull null
            val match = CHAPTER_NUM_REGEX.find(entryTitle) ?: return@mapJSONNotNull null
            val number = match.groupValues[1].toFloatOrNull() ?: return@mapJSONNotNull null
            val entryId = entry.optJSONObject("id")?.optString("\$t") ?: return@mapJSONNotNull null
            MangaChapter(
                id = generateUid(entryId),
                title = entryTitle,
                number = number,
                volume = 0,
                url = entryId,
                scanlator = null,
                uploadDate = parseIsoMillis(entry.optJSONObject("published")?.optString("\$t")),
                branch = null,
                source = source,
            )
        }.sortedByDescending { it.number }
    }

    // ================= GAMBAR CHAPTER (dari isi post Blogger) =================

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val seriesTitle = CHAPTER_NUM_REGEX.replace(chapter.title.orEmpty(), "").trim()
        val feed = fetchChapterFeed(seriesTitle)
        val entries = feed.optJSONObject("feed")?.optJSONArray("entry") ?: JSONArray()

        var contentHtml: String? = null
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val entryId = entry.optJSONObject("id")?.optString("\$t")
            if (entryId == chapter.url) {
                contentHtml = entry.optJSONObject("content")?.optString("\$t")
                break
            }
        }

        val html = contentHtml ?: return emptyList()
        val urls = IMG_SRC_REGEX.findAll(html).map { it.groupValues[1] }.distinct().toList()

        return urls.map { url ->
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url
}
