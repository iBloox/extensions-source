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

    override val lang = "ar"

    override val supportsLatest = true

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
                thumbnail_url = "https://wcloud.site/${series.imageUrl}"
            }
        }

        return MangasPage(mangas, seriesList.size >= 20)
    }

    // Latest
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
            // Extract from escaped JSON: \"name\":\"...\",\"story\":\"...\"
            title = extractEscapedField(html, "name") ?: ""
            description = extractEscapedField(html, "story")?.replace("\\n", "\n")
            author = extractEscapedField(html, "author")
            artist = extractEscapedField(html, "artist")

            val coverPath = extractEscapedField(html, "cover")
            thumbnail_url = if (!coverPath.isNullOrEmpty()) {
                "https://wcloud.site/$coverPath"
            } else {
                null
            }

            val statusValue = extractEscapedNumber(html, "status")
            status = when (statusValue) {
                0 -> SManga.ONGOING
                1 -> SManga.COMPLETED
                2 -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            val typeValue = extractEscapedField(html, "type") ?: ""
            genre = typeValue.takeIf { it.isNotEmpty() }
        }
    }

    private fun extractEscapedField(html: String, field: String): String? {
        // Pattern: \"field\":\"value\" or \"field\":\"value with \\\" escaped quotes\"
        val pattern = """\\?"$field\\?":\\?"([^"\\]*(?:\\.[^"\\]*)*)\\?"""".toRegex()
        val match = pattern.find(html)
        return match?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
    }

    private fun extractEscapedNumber(html: String, field: String): Int? {
        val pattern = """\\?"$field\\?":(\d+)""".toRegex()
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

        // Pattern: {\"id\":15410,\"chapter\":49,...\"postTime\":\"2025-12-22 19:38:31\",...}
        val chapterPattern = """\{\\?"id\\?":\s*(\d+),\\?"chapter\\?":\s*(\d+),[^}]*\\?"title\\?":\\?"([^"\\]*)\\?"[^}]*\\?"postTime\\?":\\?"([^"\\]*)\\?"[^}]*\}""".toRegex()

        chapterPattern.findAll(html).forEach { match ->
            val chapterId = match.groupValues[1]
            val chapterNum = match.groupValues[2]
            val chapterTitle = match.groupValues[3].trim()
            val postTime = match.groupValues[4]

            chapters.add(
                SChapter.create().apply {
                    url = "/series/$seriesId/$chapterId"
                    name = if (chapterTitle.isNotEmpty()) {
                        "الفصل $chapterNum: $chapterTitle"
                    } else {
                        "الفصل $chapterNum"
                    }
                    date_upload = parseDate(postTime)
                    chapter_number = chapterNum.toFloatOrNull() ?: -1f
                },
            )
        }

        return chapters
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
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        val pages = mutableListOf<Page>()

        // Pattern: projects/553/49/filename.jpg or .webp or .png
        val imagePattern = """(projects/\d+/\d+/[^"'\s\\]+\.(jpg|png|webp))""".toRegex()

        val imagePaths = imagePattern.findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        imagePaths.forEachIndexed { index, path ->
            pages.add(Page(index, "", "https://wcloud.site/$path"))
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
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
