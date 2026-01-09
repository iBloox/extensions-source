package eu.kanade.tachiyomi.extension.ar.waveteamy

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
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
        return GET("$baseUrl/series?page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val html = response.body.string()

        // Extract JSON data from Next.js script tags
        val seriesDataRegex = """"mangaData":\{([^}]+)\}""".toRegex()
        val matches = seriesDataRegex.findAll(html)

        val mangas = mutableListOf<SManga>()

        // Parse series links from HTML
        val document = Jsoup.parse(html)
        val seriesLinks = document.select("a[href*='/series/']")

        seriesLinks.forEach { link ->
            val href = link.attr("href")
            val postId = href.substringAfterLast("/")

            if (postId.isNotEmpty() && postId.matches(Regex("\\d+"))) {
                val title = link.select(
                    "h3, .line-clamp-1",
                ).text().ifEmpty {
                    link.attr("title")
                }

                val imgUrl = link.select("img").attr("src").ifEmpty {
                    link.select("img").attr("data-src")
                }

                if (title.isNotEmpty()) {
                    mangas.add(
                        SManga.create().apply {
                            url = "/series/$postId"
                            this.title = title
                            thumbnail_url = when {
                                imgUrl.startsWith("http") -> imgUrl
                                imgUrl.startsWith("/") -> baseUrl + imgUrl
                                else -> "https://wcloud.site/$imgUrl"
                            }
                        },
                    )
                }
            }
        }

        return MangasPage(mangas.distinctBy { it.url }, mangas.size >= 20)
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/series?page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotEmpty()) {
            "$baseUrl/series?search=$query&page=$page"
        } else {
            "$baseUrl/series?page=$page"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // Manga Details
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val html = response.body.string()

        return SManga.create().apply {
            // Extract manga name - look for the pattern after "mangaData"
            // The name field appears multiple times, we want the one in mangaData
            val mangaDataMatch = """\\\"mangaData\\\":\{[^}]+\\\"name\\\":\\\"([^\\]+)\\\"""".toRegex().find(html)
            title = mangaDataMatch?.groupValues?.get(1) ?: extractJsonField(html, "name")

            val storyText = extractJsonField(html, "story")
            description = storyText.replace("\\\\n", "\n").replace("\\n", "\n")

            val coverPath = extractJsonField(html, "cover")
            thumbnail_url = if (coverPath.isNotEmpty()) {
                "https://wcloud.site/$coverPath"
            } else {
                ""
            }

            author = extractJsonField(html, "author")
            artist = extractJsonField(html, "artist")

            val statusValue = extractJsonNumber(html, "status")
            status = when (statusValue) {
                "0" -> SManga.ONGOING
                "1" -> SManga.COMPLETED
                "2" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            // Extract genre array (escaped format)
            val genreMatch = """\\\"genre\\\":\[([^\]]+)\]""".toRegex().find(html)
            if (genreMatch != null) {
                genre = genreMatch.groupValues[1]
                    .replace("\\\"", "")
                    .split(",")
                    .joinToString { it.trim() }
            }
        }
    }

    private fun extractJsonField(text: String, field: String): String {
        // Try escaped format first (from Next.js embedded JSON)
        val escapedRegex = """\\\"$field\\\":\\\"([^\\]+)\\\"""".toRegex()
        val escapedMatch = escapedRegex.find(text)
        if (escapedMatch != null) {
            return escapedMatch.groupValues[1]
        }

        // Try normal format
        val normalRegex = """"$field":"([^"\\]*(?:\\.[^"\\]*)*)"""".toRegex()
        val normalMatch = normalRegex.find(text)
        return normalMatch?.groupValues?.get(1)?.replace("\\\"", "\"") ?: ""
    }

    private fun extractJsonNumber(text: String, field: String): String {
        // Try escaped format
        val escapedRegex = """\\\"$field\\\":(\d+)""".toRegex()
        val escapedMatch = escapedRegex.find(text)
        if (escapedMatch != null) {
            return escapedMatch.groupValues[1]
        }

        // Try normal format
        val normalRegex = """"$field":(\d+)""".toRegex()
        val normalMatch = normalRegex.find(text)
        return normalMatch?.groupValues?.get(1) ?: ""
    }

    // Chapters
    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val html = response.body.string()

        val chapters = mutableListOf<SChapter>()

        // Extract seriesId (postId) - use escaped format
        val seriesId = extractJsonNumber(html, "postId").ifEmpty {
            val urlMatch = """/series/(\d+)""".toRegex().find(html)
            urlMatch?.groupValues?.get(1) ?: ""
        }

        // Extract chapters using escaped JSON format
        // Pattern: {\"id\":number,\"chapter\":number,...}
        val chapterPattern = """\{\\\"id\\\":(\d+),\\\"chapter\\\":(\d+)""".toRegex()

        chapterPattern.findAll(html).forEach { match ->
            val chapterId = match.groupValues[1]
            val chapterNum = match.groupValues[2]

            // Extract the full chapter object to get title and postTime
            val fullChapterRegex = """\{\\\"id\\\":$chapterId,\\\"chapter\\\":$chapterNum[^}]+\}""".toRegex()
            val fullMatch = fullChapterRegex.find(html)

            var chapterTitle = ""
            var postTime = ""

            if (fullMatch != null) {
                val chapterData = fullMatch.value
                // Extract title
                val titleMatch = """\\\"title\\\":\\\"([^\\]*)\\\"""".toRegex().find(chapterData)
                chapterTitle = titleMatch?.groupValues?.get(1)?.trim() ?: ""

                // Extract postTime
                val timeMatch = """\\\"postTime\\\":\\\"([^\\]+)\\\"""".toRegex().find(chapterData)
                postTime = timeMatch?.groupValues?.get(1) ?: ""
            }

            chapters.add(
                SChapter.create().apply {
                    url = "/series/$seriesId/chapter/$chapterId"
                    name = if (chapterTitle.isNotEmpty() && chapterTitle != " " && chapterTitle != "\n") {
                        "الفصل $chapterNum: $chapterTitle"
                    } else {
                        "الفصل $chapterNum"
                    }
                    date_upload = parseDate(postTime)
                    chapter_number = chapterNum.toFloatOrNull() ?: -1f
                },
            )
        }

        // Fallback: If no chapters found, try HTML parsing
        if (chapters.isEmpty()) {
            val document = Jsoup.parse(html)
            document.select("a[href*='/chapter/'], a[href*='/ch/']").forEach { element ->
                chapters.add(
                    SChapter.create().apply {
                        setUrlWithoutDomain(element.attr("href"))
                        name = element.text().ifEmpty {
                            element.attr("href").substringAfterLast("/").replace("-", " ")
                        }
                        date_upload = 0L
                    },
                )
            }
        }

        return chapters.reversed() // Reverse to show newest first
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            // Format: "2025-12-22 19:38:31"
            val parts = dateStr.split(" ")
            if (parts.size == 2) {
                val dateParts = parts[0].split("-")
                val timeParts = parts[1].split(":")

                if (dateParts.size == 3 && timeParts.size == 3) {
                    val year = dateParts[0].toInt()
                    val month = dateParts[1].toInt() - 1
                    val day = dateParts[2].toInt()

                    java.util.Calendar.getInstance().apply {
                        set(year, month, day)
                    }.timeInMillis
                } else {
                    0L
                }
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    // Pages
    override fun pageListRequest(chapter: SChapter): Request {
        // Extract chapter ID from URL: /series/{seriesId}/chapter/{chapterId}
        val chapterId = chapter.url.substringAfterLast("/")

        // Try API endpoint first
        val apiUrl = "$baseUrl/wapi/hanout/v1/chapter/$chapterId"
        return GET(apiUrl, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val responseBody = response.body.string()
        val pages = mutableListOf<Page>()

        // Method 1: Extract image paths from HTML using regex
        // Pattern: projects/371/1/1749963066128-0-01.jpg
        val projectsRegex = """projects/\d+/\d+/[^"'\s\\]+\.jpg""".toRegex()
        val matches = projectsRegex.findAll(responseBody)

        val imagePaths = matches
            .map { it.value.replace("\\", "") }
            .distinct()
            .toList()

        if (imagePaths.isNotEmpty()) {
            imagePaths.forEachIndexed { index, path ->
                pages.add(Page(index, "", "https://wcloud.site/$path"))
            }
            return pages
        }

        // Method 2: Try JSON API response format
        try {
            if (responseBody.trim().startsWith("{")) {
                val pagesRegex = """"pages":\[([^\]]+)\]""".toRegex()
                val pagesMatch = pagesRegex.find(responseBody)

                if (pagesMatch != null) {
                    val pagesData = pagesMatch.groupValues[1]
                    val pathRegex = """"([^"]+)"""".toRegex()

                    pathRegex.findAll(pagesData).forEachIndexed { index, match ->
                        val path = match.groupValues[1]
                        val imageUrl = when {
                            path.startsWith("http") -> path
                            path.startsWith("/") -> "https://wcloud.site$path"
                            else -> "https://wcloud.site/$path"
                        }
                        pages.add(Page(index, "", imageUrl))
                    }

                    if (pages.isNotEmpty()) return pages
                }
            }
        } catch (e: Exception) {
            // Continue to next method
        }

        // Method 3: Try escaped JSON format
        val escapedPagesRegex = """\\\"pages\\\":\[([^\]]+)\]""".toRegex()
        val escapedMatch = escapedPagesRegex.find(responseBody)

        if (escapedMatch != null) {
            val pagesData = escapedMatch.groupValues[1]
            val pathRegex = """\\\"([^\\]+)\\\"""".toRegex()

            pathRegex.findAll(pagesData).forEachIndexed { index, match ->
                val path = match.groupValues[1]
                val imageUrl = when {
                    path.startsWith("http") -> path
                    path.startsWith("/") -> "https://wcloud.site$path"
                    else -> "https://wcloud.site/$path"
                }
                pages.add(Page(index, "", imageUrl))
            }
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    override fun getFilterList() = FilterList()
}
