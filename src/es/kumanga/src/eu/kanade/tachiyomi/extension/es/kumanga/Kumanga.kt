package eu.kanade.tachiyomi.extension.es.kumanga

import com.github.salomonbrys.kotson.array
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.int
import com.github.salomonbrys.kotson.string
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class Kumanga : HttpSource() {

    override val client: OkHttpClient = network.cloudflareClient
        .newBuilder()
        .followRedirects(true)
        .build()

    override val name = "Kumanga"

    override val baseUrl = "https://www.kumanga.com"

    override val lang = "es"

    override val supportsLatest = false

    private val chapterImagesHeaders = Headers.Builder()
        .add("Referer", baseUrl)
        .build()

    private fun getMangaCover(mangaId: String) = "https://static.kumanga.com/manga_covers/$mangaId.jpg?w=201"

    private fun getMangaUrl(mangaId: String, mangaSlug: String, page: Int) = "/manga/$mangaId/p/$page/$mangaSlug#cl"

    private fun parseMangaFromJson(json: JsonElement) = SManga.create().apply {
        title = json["name"].string
        description = json["description"].string.replace("\\", "")
        url = getMangaUrl(json["id"].string, json["slug"].string, 1)
        thumbnail_url = getMangaCover(json["id"].string)

        val genresArray = json["categories"].array
        genre = genresArray.joinToString { jsonObject ->
            parseGenresFromJson(jsonObject)
        }
    }

    private fun parseJson(json: String): JsonElement {
        return JsonParser().parse(json)
    }

    private fun parseGenresFromJson(json: JsonElement) = json["name"].string

    override fun popularMangaRequest(page: Int): Request {
        return POST("$baseUrl/backend/ajax/searchengine.php?page=$page&perPage=10&keywords=&retrieveCategories=true&retrieveAuthors=false&contentType=manga", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val res = response.body()!!.string()
        val json = parseJson(res)
        val data = json["contents"].array
        val retrievedCount = json["retrievedCount"].int
        val hasNextPage = retrievedCount == 10

        val mangas = data.map { jsonObject ->
            parseMangaFromJson(jsonObject)
        }

        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int) = throw Exception("Not Used")

    override fun latestUpdatesParse(response: Response) = throw Exception("Not Used")

    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val body = response.asJsoup()

        body.select("div#tab2").let {
            status = parseStatus(it.select("span").text().orEmpty())
            author = it.select("p:nth-child(3) > a").text()
            artist = it.select("p:nth-child(4) > a").text()
        }
    }

    private fun parseStatus(status: String) = when {
        status.contains("Activo") -> SManga.ONGOING
        status.contains("Finalizado") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    private fun parseChapterDate(date: String): Long = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        .parse(date)?.time ?: 0L

    private fun chapterSelector() = "div#accordion > div.panel.panel-default.c_panel:has(table)"

    private fun chapterFromElement(element: Element) = SChapter.create().apply {
        element.select("table:first-child td h4").let { it ->
            it.select("a:has(i)").let {
                url = '/' + it.attr("href").replace("/c/", "/leer/")
                name = it.text()
                date_upload = parseChapterDate(it.attr("title"))
            }
            scanlator = it.select("span.pull-right.greenSpan")?.text()
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> = mutableListOf<SChapter>().apply {
        var document = response.asJsoup()
        val params = document.select("body").toString().substringAfter("php_pagination(").substringBefore(")")
        val numberChapters = params.split(",")[4].toIntOrNull()
        val mangaId = params.split(",")[0]
        val mangaSlug = params.split(",")[1].replace("'", "")

        if (numberChapters != null) {
            // Calculating total of pages, Kumanga shows 10 chapters per page, total_pages = #chapters / 10
            val numberOfPages = (numberChapters / 10.toDouble() + 0.4).roundToInt()
            document.select(chapterSelector()).map { add(chapterFromElement(it)) }
            var page = 2

            while (page <= numberOfPages) {
                document = client.newCall(GET(baseUrl + getMangaUrl(mangaId, mangaSlug, page))).execute().asJsoup()
                document.select(chapterSelector()).map { add(chapterFromElement(it)) }
                page++
            }
        } else throw Exception("No fue posible obtener los capítulos")
    }

    override fun pageListParse(response: Response): List<Page> = mutableListOf<Page>().apply {
        val document = response.asJsoup()
        val imagesJsonListStr = document.select("head").toString()
            .substringAfter("var pUrl=")
            .substringBefore(";")
        val imagesJsonList = parseJson(imagesJsonListStr).array

        imagesJsonList.forEach {
            val fakeImageUrl = it["imgURL"].string.replace("\\", "")
            val imageUrl = baseUrl + fakeImageUrl

            add(Page(size, "", imageUrl))
        }
    }

    override fun imageRequest(page: Page) = GET(page.imageUrl!!, chapterImagesHeaders)

    override fun imageUrlParse(response: Response) = throw Exception("Not Used")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = HttpUrl.parse("$baseUrl/backend/ajax/searchengine.php?page=$page&perPage=10&keywords=$query&retrieveCategories=true&retrieveAuthors=false&contentType=manga")!!.newBuilder()

        filters.forEach { filter ->
            when (filter) {
                is TypeList -> {
                    filter.state
                        .filter { type -> type.state }
                        .forEach { type -> url.addQueryParameter("type_filter[]", type.id) }
                }
                is StatusList -> {
                    filter.state
                        .filter { status -> status.state }
                        .forEach { status -> url.addQueryParameter("status_filter[]", status.id) }
                }
                is GenreList -> {
                    filter.state
                        .filter { genre -> genre.state }
                        .forEach { genre -> url.addQueryParameter("category_filter[]", genre.id) }
                }
            }
        }

        return POST(url.build().toString(), headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun getFilterList() = FilterList(
        TypeList(getTypeList()),
        Filter.Separator(),
        StatusList(getStatusList()),
        Filter.Separator(),
        GenreList(getGenreList())
    )

    private class Type(name: String, val id: String) : Filter.CheckBox(name)
    private class TypeList(types: List<Type>) : Filter.Group<Type>("Filtrar por tipos", types)

    private class Status(name: String, val id: String) : Filter.CheckBox(name)
    private class StatusList(status: List<Status>) : Filter.Group<Status>("Filtrar por estado", status)

    private class Genre(name: String, val id: String) : Filter.CheckBox(name)
    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Filtrar por géneros", genres)

    private fun getTypeList() = listOf(
        Type("Manga", "1"),
        Type("Manhwa", "2"),
        Type("Manhua", "3"),
        Type("One shot", "4"),
        Type("Doujinshi", "5")
    )

    private fun getStatusList() = listOf(
        Status("Activo", "1"),
        Status("Finalizado", "2"),
        Status("Inconcluso", "3")
    )

    private fun getGenreList() = listOf(
        Genre("Acción", "1"),
        Genre("Artes marciales", "2"),
        Genre("Automóviles", "3"),
        Genre("Aventura", "4"),
        Genre("Ciencia Ficción", "5"),
        Genre("Comedia", "6"),
        Genre("Demonios", "7"),
        Genre("Deportes", "8"),
        Genre("Doujinshi", "9"),
        Genre("Drama", "10"),
        Genre("Ecchi", "11"),
        Genre("Espacio exterior", "12"),
        Genre("Fantasía", "13"),
        Genre("Gender bender", "14"),
        Genre("Gore", "46"),
        Genre("Harem", "15"),
        Genre("Hentai", "16"),
        Genre("Histórico", "17"),
        Genre("Horror", "18"),
        Genre("Josei", "19"),
        Genre("Juegos", "20"),
        Genre("Locura", "21"),
        Genre("Magia", "22"),
        Genre("Mecha", "23"),
        Genre("Militar", "24"),
        Genre("Misterio", "25"),
        Genre("Música", "26"),
        Genre("Niños", "27"),
        Genre("Parodia", "28"),
        Genre("Policía", "29"),
        Genre("Psicológico", "30"),
        Genre("Recuentos de la vida", "31"),
        Genre("Romance", "32"),
        Genre("Samurai", "33"),
        Genre("Seinen", "34"),
        Genre("Shoujo", "35"),
        Genre("Shoujo Ai", "36"),
        Genre("Shounen", "37"),
        Genre("Shounen Ai", "38"),
        Genre("Sobrenatural", "39"),
        Genre("Súperpoderes", "41"),
        Genre("Suspenso", "40"),
        Genre("Terror", "47"),
        Genre("Tragedia", "48"),
        Genre("Vampiros", "42"),
        Genre("Vida escolar", "43"),
        Genre("Yaoi", "44"),
        Genre("Yuri", "45")
    )
}