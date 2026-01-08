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
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class WaveTeamy : HttpSource() {

    override val name = "WaveTeamy"

    override val baseUrl = "https://waveteamy.com"

    override val lang = "ar"

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .rateLimit(10, 1, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "*/*")
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/series")

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        val formBody = FormBody.Builder()
            .add("page", page.toString())
            .build()

        return POST("$baseUrl/wapi/hanout/v1/series/series-list", headers, formBody)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val seriesList = json.decodeFromString<List<SeriesDto>>(response.body.string())

        val mangas = seriesList.map { series ->
            SManga.create().apply {
                url = "/series/${series.postId}"
                title = series.title
                thumbnail_url = "$baseUrl/${series.imageUrl}"
            }
        }

        return MangasPage(mangas, false)
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

    override fun searchMangaParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // Manga Details
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            title = document.select("h1").first()?.text() ?: ""

            description = document.select("div:contains(القصة)").parents().first()
                ?.text()?.substringAfter("القصة")?.trim() ?: ""

            thumbnail_url = document.select("img[src*='wcloud'], img[src*='cover']")
                .first()?.attr("abs:src") ?: ""

            val statusText = document.text()
            status = when {
                statusText.contains("مستمر") -> SManga.ONGOING
                statusText.contains("منتهي") -> SManga.COMPLETED
                statusText.contains("متوقف") -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            genre = document.select("a[href*='/genre/']").joinToString { it.text() }

            author = document.select("div:contains(المؤلف), span:contains(المؤلف)")
                .text().replace("المؤلف:", "").replace("المؤلف", "").trim()
        }
    }

    // Chapters
    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string())

        return document.select("a[href*='/chapter/'], a[href*='/ch/']").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                name = element.text().ifEmpty {
                    element.attr("href").substringAfterLast("/").replace("-", " ")
                }
                date_upload = 0L
            }
        }
    }

    // Pages
    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = Jsoup.parse(response.body.string())
        val images = document.select("img[src*='wcloud'], img[src*='cdn'], img[class*='page']")

        return images.mapIndexedNotNull { index, element ->
            val imageUrl = element.attr("abs:src").ifEmpty {
                element.attr("abs:data-src")
            }

            if (imageUrl.isNotEmpty() && imageUrl.contains("http")) {
                Page(index, "", imageUrl)
            } else {
                null
            }
        }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    override fun getFilterList() = FilterList()

    @Serializable
    data class SeriesDto(
        val id: Int,
        val title: String,
        val imageUrl: String,
        val ratingValue: Double,
        val statusValue: Int,
        val postId: Long,
    )
}
