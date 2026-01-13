package eu.kanade.tachiyomi.extension.ar.waveteamy

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class WaveTeamy : HttpSource() {

    override val name = "WaveTeamy"

    override val baseUrl = "https://waveteamy.com"

    private val cdnUrl = "https://wcloud.site"

    override val lang = "ar"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .rateLimit(2, 1, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "*/*")
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/series")

    // Popular - using API
    override fun popularMangaRequest(page: Int): Request {
        val formBody = FormBody.Builder()
            .add("page", page.toString())
            .build()

        return POST("$baseUrl/wapi/hanout/v1/series/series-list", headers, formBody)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val responseBody = response.body.string()
        val seriesList = json.decodeFromString<List<SeriesDto>>(responseBody)

        val mangas = seriesList.map { series ->
            SManga.create().apply {
                url = "/series/${series.postId}"
                title = series.title
                thumbnail_url = "$cdnUrl/${series.imageUrl}"
            }
        }

        return MangasPage(mangas, seriesList.size >= 20)
    }

    // Latest - not supported
    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val formBody = FormBody.Builder()
            .add("page", page.toString())
            .apply {
                if (query.isNotEmpty()) {
                    add("search", query)
                }
            }
            .build()

        return POST("$baseUrl/wapi/hanout/v1/series/series-list", headers, formBody)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // Manga Details
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val html = response.body.string()

        return SManga.create().apply {
            title = extractDoubleEscapedField(html, "name") ?: ""
            description = extractDoubleEscapedField(html, "story")
                ?.replace("\\\\n", "\n")
                ?.replace("\\n", "\n")
            author = extractDoubleEscapedField(html, "author")
            artist = extractDoubleEscapedField(html, "artist")

            val coverPath = extractDoubleEscapedField(html, "cover")
            thumbnail_url = coverPath?.let { "$cdnUrl/$it" }

            val statusValue = extractDoubleEscapedInt(html, "status")
            status = when (statusValue) {
                0 -> SManga.ONGOING
                1 -> SManga.COMPLETED
                2 -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            val typeValue = extractDoubleEscapedField(html, "type") ?: ""
            genre = typeValue.takeIf { it.isNotEmpty() }
        }
    }

    private fun extractDoubleEscapedField(html: String, field: String): String? {
        // Pattern for double-escaped JSON: \\\"field\\\":\\\"value\\\"
        val pattern = """\\\\?"$field\\\\?":\\\\?"([^\\]*(?:\\\\.[^\\]*)*)\\\\?"""".toRegex()
        val match = pattern.find(html)
        return match?.groupValues?.get(1)
            ?.replace("\\\\\"", "\"")
            ?.replace("\\\"", "\"")
    }

    private fun extractDoubleEscapedInt(html: String, field: String): Int? {
        val pattern = """\\\\?"$field\\\\?":(\d+)""".toRegex()
        val match = pattern.find(html)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    // Chapters
    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val html = response.body.string()
        val chapters = mutableListOf<SChapter>()

        val seriesId = response.request.url.pathSegments.lastOrNull() ?: ""

        // Extract internal series ID from cover URL: series/570/cover/...
        val internalIdPattern = """series/(\d+)/cover/""".toRegex()
        val internalIdMatch = internalIdPattern.find(html)
        val internalId = internalIdMatch?.groupValues?.get(1) ?: ""

        // Pattern for chapter data
        val chapterPattern = """\{\\\\?"id\\\\?":(\d+),\\\\?"chapter\\\\?":(\d+),[^}]*\\\\?"postTime\\\\?":\\\\?"([^\\"]*)\\\\?"[^}]*\}""".toRegex()

        chapterPattern.findAll(html).forEach { match ->
            val chapterId = match.groupValues[1]
            val chapterNum = match.groupValues[2]
            val postTime = match.groupValues[3]

            chapters.add(
                SChapter.create().apply {
                    // Store internalId in URL for use in pageListParse
                    url = "/series/$seriesId/$chapterId#$internalId#$chapterNum"
                    name = "الفصل $chapterNum"
                    date_upload = parseDate(postTime)
                    chapter_number = chapterNum.toFloatOrNull() ?: -1f
                },
            )
        }

        return chapters.distinctBy { it.url }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)

    private fun parseDate(dateStr: String): Long {
        return try {
            dateFormat.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // Pages
    override fun pageListRequest(chapter: SChapter): Request {
        // URL format: /series/{postId}/{chapterId}#{internalId}#{chapterNum}
        val urlParts = chapter.url.split("#")
        val basePath = urlParts[0]
        return GET(baseUrl + basePath, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        val pages = mutableListOf<Page>()

        // Extract internal series ID from the page
        val internalIdPattern = """series/(\d+)/cover/""".toRegex()
        val internalIdMatch = internalIdPattern.find(html)
        val internalId = internalIdMatch?.groupValues?.get(1) ?: return pages

        // Get chapter number from URL or page
        val chapterNumPattern = """\\\\?"chapter\\\\?":(\d+)""".toRegex()
        val chapterNumMatch = chapterNumPattern.find(html)
        val chapterNum = chapterNumMatch?.groupValues?.get(1) ?: return pages

        // Try to find image paths in the page
        // Pattern: projects/{internalId}/{chapterNum}/{filename}.webp
        val imagePattern = """projects/$internalId/$chapterNum/(\d+)\.(webp|jpg|png)""".toRegex()
        val imageMatches = imagePattern.findAll(html).toList()

        if (imageMatches.isNotEmpty()) {
            imageMatches.forEachIndexed { index, match ->
                val filename = match.groupValues[1]
                val ext = match.groupValues[2]
                pages.add(Page(index, "", "$cdnUrl/projects/$internalId/$chapterNum/$filename.$ext"))
            }
            return pages
        }

        // Fallback: Try to find any wcloud.site image URLs
        val wcloudPattern = """wcloud\.site/(series/$internalId/\d+/[^"'\s\\]+\.(webp|jpg|png))""".toRegex()
        wcloudPattern.findAll(html).forEachIndexed { index, match ->
            pages.add(Page(index, "", "$cdnUrl/${match.groupValues[1]}"))
        }

        // If still no pages, try projects pattern without chapter restriction
        if (pages.isEmpty()) {
            val projectsPattern = """(projects/\d+/\d+/[^"'\s\\]+\.(webp|jpg|png))""".toRegex()
            projectsPattern.findAll(html)
                .map { it.groupValues[1] }
                .distinct()
                .forEachIndexed { index, path ->
                    pages.add(Page(index, "", "$cdnUrl/$path"))
                }
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    override fun imageRequest(page: Page): Request {
        val headers = headersBuilder()
            .add("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
            .add("Referer", baseUrl)
            .build()
        return GET(page.imageUrl!!, headers)
    }

    override fun getFilterList() = FilterList()

    @Serializable
    data class SeriesDto(
        val id: Int = 0,
        val title: String = "",
        val imageUrl: String = "",
        val ratingValue: Double = 0.0,
        val statusValue: Int = 0,
        val postId: Long = 0,
    )
}
