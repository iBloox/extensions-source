package eu.kanade.tachiyomi.extension.ar.waveteamy

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class WaveTeamy : ParsedHttpSource() {

    override val name = "WaveTeamy"

    override val baseUrl = "https://waveteamy.com"

    override val lang = "ar"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .rateLimit(10, 1, TimeUnit.SECONDS)
        .build()

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/", headers)
    }

    override fun popularMangaSelector() = "a[href*='/series/']"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            setUrlWithoutDomain(element.attr("href"))

            // Title from img alt or strong/h3 text
            title = element.select("img").attr("alt").ifEmpty {
                element.select("strong, h3, h2").text().ifEmpty {
                    element.text().substringBefore("مستمر").substringBefore("منتهي").trim()
                }
            }

            // Thumbnail
            thumbnail_url = element.select("img").attr("abs:src")
        }
    }

    override fun popularMangaNextPageSelector() = null

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector())
            .distinctBy { it.attr("href") }
            .map { popularMangaFromElement(it) }
            .filter { it.title.isNotEmpty() && it.url.isNotEmpty() }

        return MangasPage(mangas, false)
    }

    // Latest
    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/series".toHttpUrl().newBuilder()
            .apply {
                if (query.isNotEmpty()) {
                    addQueryParameter("search", query)
                }
                if (page > 1) {
                    addQueryParameter("page", page.toString())
                }
            }
            .build()
        return GET(url, headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = "a:contains(التالي), a:contains(Next)"

    // Manga Details
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            // Title
            title = document.select("h1").first()?.text() ?: ""

            // Description
            description = document.select("div:contains(القصة) + div, p:contains(القصة)").text()
                .replace("القصة", "").trim()

            // Thumbnail
            thumbnail_url = document.select("img[src*='wcloud'], img[src*='cover']")
                .first()?.attr("abs:src") ?: ""

            // Status
            val statusText = document.text()
            status = when {
                statusText.contains("مستمر") -> SManga.ONGOING
                statusText.contains("منتهي") -> SManga.COMPLETED
                statusText.contains("متوقف") -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            // Genre
            genre = document.select("a[href*='/genre/']").joinToString { it.text() }

            // Author
            author = document.select("div:contains(المؤلف), span:contains(المؤلف)")
                .text().replace("المؤلف:", "").replace("المؤلف", "").trim()
        }
    }

    // Chapters
    override fun chapterListSelector() = "a[href*='/chapter/'], a[href*='/ch/']"

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            setUrlWithoutDomain(element.attr("href"))

            // Chapter name
            name = element.text().ifEmpty {
                element.attr("href").substringAfterLast("/").replace("-", " ")
            }

            date_upload = 0L
        }
    }

    // Pages
    override fun pageListParse(document: Document): List<Page> {
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

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList()
}
