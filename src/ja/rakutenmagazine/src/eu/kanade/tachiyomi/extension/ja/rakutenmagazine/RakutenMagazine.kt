package eu.kanade.tachiyomi.extension.ja.rakutenmagazine

import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import rx.Observable
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class RakutenMagazine : HttpSource(), ConfigurableSource {

    override val name = "Rakuten Magazine"

    override val baseUrl = "https://magazine.rakuten.co.jp"

    override val lang = "ja"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    // Ensure a persistent browser device UUID so Rakuten Magazine never triggers
    // the "up to 2 browsers per 30 minutes" (403 Forbidden) rate limit.
    private val defaultDeviceUuid: String by lazy {
        val existing = preferences.getString(PREF_AUTO_REM_UUID, null)
        if (!existing.isNullOrBlank()) {
            existing
        } else {
            val generated = UUID.randomUUID().toString()
            preferences.edit().putString(PREF_AUTO_REM_UUID, generated).apply()
            generated
        }
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(::authAndImageInterceptor)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================= WebView URL Overrides =============================

    override fun getMangaUrl(manga: SManga): String {
        if (manga.url == LOGIN_MANGA_URL) {
            return RAKUTEN_SSO_LOGIN_URL
        }
        val titleId = extractTitleId(manga.url)
        return "$baseUrl/title/$titleId/"
    }

    override fun getChapterUrl(chapter: SChapter): String {
        if (chapter.url == LOGIN_CHAPTER_SSO_URL) {
            return RAKUTEN_SSO_LOGIN_URL
        }
        if (chapter.url == LOGIN_CHAPTER_REDIRECT_URL) {
            return "$baseUrl/login"
        }
        return "$baseUrl${chapter.url}"
    }

    // ============================= Catalog: Popular & Latest =============================

    @Volatile
    private var cachedLatestIssues: List<IssueDto>? = null

    @Volatile
    private var cachedLatestIssuesTimestamp: Long = 0L

    @Volatile
    private var cachedKanaMap: Map<String, String>? = null

    private fun getLatestIssuesCatalog(): List<IssueDto> {
        val now = System.currentTimeMillis()
        val current = cachedLatestIssues
        if (current != null && now - cachedLatestIssuesTimestamp < CATALOG_CACHE_TTL_MS) {
            return current
        }
        synchronized(this) {
            val again = cachedLatestIssues
            if (again != null && System.currentTimeMillis() - cachedLatestIssuesTimestamp < CATALOG_CACHE_TTL_MS) {
                return again
            }
            val request = GET("$baseUrl/json/v1.0/latestIssues", headers)
            val response = client.newCall(request).execute()
            val parsed = response.parseAs<LatestIssuesResponse>().latestIssues.orEmpty()
            cachedLatestIssues = parsed
            cachedLatestIssuesTimestamp = System.currentTimeMillis()
            return parsed
        }
    }

    private fun getNoticeTitlesCatalog(): List<IssueDto> {
        val request = GET("$baseUrl/json/v1.0/noticeTitles", headers)
        val response = client.newCall(request).execute()
        return response.parseAs<NoticeTitlesResponse>().noticeTitles.orEmpty()
    }

    private fun getKanaSearchMap(): Map<String, String> {
        val current = cachedKanaMap
        if (current != null) return current
        synchronized(this) {
            val again = cachedKanaMap
            if (again != null) return again
            val map = mutableMapOf<String, String>()
            try {
                val request = GET("$baseUrl/csv/magazine_list.csv", headers)
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body.bytes()
                        val text = String(bytes, Charset.forName("Shift_JIS"))
                        text.lineSequence().drop(1).forEach { line ->
                            val cols = line.split(',', '\t').map { it.trim() }
                            if (cols.size >= 5) {
                                val kana = cols[3]
                                val urlParts = cols[4].split('/')
                                if (urlParts.size >= 5) {
                                    val type = urlParts[3]
                                    val id = urlParts[4]
                                    if (id.isNotEmpty() && kana.isNotEmpty()) {
                                        map["$type:$id"] = normalizeForSearch(kana)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            cachedKanaMap = map
            return map
        }
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return Observable.fromCallable {
            val sorted = getLatestIssuesCatalog().sortedWith(
                compareBy<IssueDto> { if (it.rank <= 0) Int.MAX_VALUE else it.rank }
                    .thenByDescending { it.publicationDate.orEmpty() },
            )
            paginateIssues(sorted, page, includeLoginCard = shouldShowLoginCard(page))
        }
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return Observable.fromCallable {
            val sorted = getLatestIssuesCatalog().sortedWith(
                compareByDescending<IssueDto> { it.publicationDate.orEmpty() }
                    .thenBy { if (it.rank <= 0) Int.MAX_VALUE else it.rank },
            )
            paginateIssues(sorted, page, includeLoginCard = shouldShowLoginCard(page))
        }
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return Observable.fromCallable {
            val trimmed = query.trim()

            // Direct deep-link support: /title/<id> or /issue/<id>
            if (trimmed.startsWith("https://magazine.rakuten.co.jp/") ||
                trimmed.startsWith("title:") ||
                trimmed.startsWith("issue:")
            ) {
                val all = getLatestIssuesCatalog()
                val targetId = trimmed
                    .substringAfter("/title/", "")
                    .ifEmpty { trimmed.substringAfter("/issue/", "") }
                    .ifEmpty { trimmed.substringAfter("title:", "") }
                    .ifEmpty { trimmed.substringAfter("issue:", "") }
                    .trim('/')
                val matched = all.filter {
                    it.titleIdentifier.orEmpty().equals(targetId, ignoreCase = true) ||
                        it.issueId.orEmpty().equals(targetId, ignoreCase = true)
                }
                return@fromCallable paginateIssues(matched, page, includeLoginCard = false)
            }

            var categoryId = ""
            var sortByDate = false
            var featuredOnly = false

            filters.forEach { filter ->
                when (filter) {
                    is CategoryFilter -> categoryId = filter.selectedCategoryId
                    is SortFilter -> sortByDate = filter.isByDate
                    is FeaturedOnlyFilter -> featuredOnly = filter.state
                    else -> {}
                }
            }

            val baseList = if (featuredOnly) {
                getNoticeTitlesCatalog()
            } else {
                getLatestIssuesCatalog()
            }

            val categoryFiltered = if (categoryId.isNotEmpty()) {
                baseList.filter { it.categoryId == categoryId }
            } else {
                baseList
            }

            val queryFiltered = if (trimmed.isNotEmpty()) {
                val normQuery = normalizeForSearch(trimmed)
                val kanaMap = getKanaSearchMap()
                categoryFiltered.filter { issue ->
                    val tid = issue.titleIdentifier.orEmpty()
                    val iid = issue.issueId.orEmpty()
                    normalizeForSearch(issue.titleName.orEmpty()).contains(normQuery) ||
                        normalizeForSearch(issue.issueName.orEmpty()).contains(normQuery) ||
                        normalizeForSearch(issue.description.orEmpty()).contains(normQuery) ||
                        normalizeForSearch(issue.categoryName.orEmpty()).contains(normQuery) ||
                        tid.equals(trimmed, ignoreCase = true) ||
                        (kanaMap["title:$tid"]?.contains(normQuery) == true) ||
                        (kanaMap["issue:$iid"]?.contains(normQuery) == true)
                }
            } else {
                categoryFiltered
            }

            val sorted = if (sortByDate || (trimmed.isNotEmpty() && !featuredOnly)) {
                queryFiltered.sortedWith(
                    compareByDescending<IssueDto> { it.publicationDate.orEmpty() }
                        .thenBy { if (it.rank <= 0) Int.MAX_VALUE else it.rank },
                )
            } else {
                queryFiltered.sortedWith(
                    compareBy<IssueDto> { if (it.rank <= 0) Int.MAX_VALUE else it.rank }
                        .thenByDescending { it.publicationDate.orEmpty() },
                )
            }

            val showLogin = page == 1 &&
                preferences.getBoolean(PREF_SHOW_LOGIN_CARD, true) &&
                (trimmed.isEmpty() || trimmed.equals("login", ignoreCase = true) || trimmed.contains("ログイン"))
            paginateIssues(sorted, page, includeLoginCard = showLogin)
        }
    }

    override fun getFilterList(): FilterList = FilterList(
        CategoryFilter(),
        SortFilter(),
        FeaturedOnlyFilter(),
    )

    override fun popularMangaRequest(page: Int): Request =
        throw UnsupportedOperationException()

    override fun popularMangaParse(response: Response): MangasPage =
        throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request =
        throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): MangasPage =
        throw UnsupportedOperationException()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        throw UnsupportedOperationException()

    override fun searchMangaParse(response: Response): MangasPage =
        throw UnsupportedOperationException()

    private fun shouldShowLoginCard(page: Int): Boolean =
        page == 1 && preferences.getBoolean(PREF_SHOW_LOGIN_CARD, true)

    private fun createLoginEntryCard(): SManga = SManga.create().apply {
        url = LOGIN_MANGA_URL
        title = "【ログイン / Login Status】楽天マガジン"
        thumbnail_url = "$baseUrl/apple-touch-icon.png"
        genre = "Account / Login"
        status = SManga.ONGOING
        description = buildString {
            append("【WebViewでログインする方法 / How to Log In via WebView】\n")
            append("1. この画面右上の「WebView (地球/ブラウザ)」アイコンをタップすると、楽天ログイン画面が開きます。\n")
            append("   (Tap the WebView / Globe icon in the top-right corner to open the Rakuten SSO Login screen.)\n")
            append("2. 楽天IDでログインし、楽天マガジンのトップ画面に戻るまで待ちます。\n")
            append("3. WebViewを閉じて、この画面を下に引っ張って更新（リフレッシュ）するとログイン状態が反映されます。\n\n")
            append("※ iOS (Tachimanga) でWebViewのCookieが共有されない場合は、拡張機能の設定画面から `Rz` Cookieを貼り付けることもできます。")
        }
        initialized = false
    }

    private fun paginateIssues(
        issues: List<IssueDto>,
        page: Int,
        includeLoginCard: Boolean,
    ): MangasPage {
        val seenTitles = HashSet<String>()
        val deduped = issues.filter { issue ->
            val key = issue.titleIdentifier.orEmpty().ifEmpty { issue.issueId.orEmpty() }
            key.isNotEmpty() && seenTitles.add(key)
        }
        val fromIndex = ((page - 1) * PAGE_SIZE).coerceAtMost(deduped.size)
        val toIndex = (fromIndex + PAGE_SIZE).coerceAtMost(deduped.size)
        val slice = deduped.subList(fromIndex, toIndex).map { it.toSManga() }
        val finalSlice = if (includeLoginCard) {
            listOf(createLoginEntryCard()) + slice
        } else {
            slice
        }
        return MangasPage(finalSlice, toIndex < deduped.size)
    }

    private fun IssueDto.toSManga(): SManga = SManga.create().apply {
        val tid = titleIdentifier.orEmpty()
        val tName = titleName.orEmpty()
        val iName = issueName.orEmpty()
        val pubDate = publicationDate.orEmpty()
        val desc = this@toSManga.description.orEmpty()

        url = "/title/$tid"
        title = tName.ifEmpty { iName }
        thumbnail_url = imageUrl.orEmpty().ifEmpty { thumbnail.orEmpty() }
        genre = categoryName.orEmpty()
        description = buildString {
            if (iName.isNotEmpty()) {
                append("最新号: ").append(iName)
            }
            if (pubDate.length == 8) {
                if (isNotEmpty()) append(" (")
                append(pubDate.substring(0, 4)).append("-")
                    .append(pubDate.substring(4, 6)).append("-")
                    .append(pubDate.substring(6, 8))
                append(")")
            }
            if (desc.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(desc)
            }
        }
        status = SManga.ONGOING
        initialized = true
    }

    // ============================= Manga Details & Chapters =============================

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        if (manga.url == LOGIN_MANGA_URL) {
            return Observable.fromCallable {
                val statusInfo = checkLiveAccountStatus()
                createLoginEntryCard().apply {
                    title = if (statusInfo.isSubscribed) {
                        "【ログイン済 ✓】楽天マガジン (${statusInfo.statusLabel})"
                    } else {
                        "【未ログイン — WebViewを開いてログイン】楽天マガジン"
                    }
                    description = buildString {
                        append("■ 現在のステータス: ").append(statusInfo.statusLabel).append("\n")
                        append("■ Cookie状態: ").append(statusInfo.cookieSummary).append("\n")
                        append("■ ブラウザUUID (_REM_UUID): ").append(statusInfo.activeUuid).append("\n\n")
                        append("────────────────────────\n")
                        append("【WebViewでログインする手順】\n")
                        append("1. 右上の「WebView (地球マーク)」ボタンをタップすると、楽天会員SSOログイン画面 (`login.account.rakuten.com`) がポップアップします。\n")
                        append("2. 楽天ID・パスワードを入力してログインします。\n")
                        append("3. ログイン完了後、自動的に `magazine.rakuten.co.jp` にリダイレクトされたらWebViewを閉じます。\n")
                        append("4. このページを下に引っ張って更新し、「ログイン済 ✓」になることを確認してください。\n")
                    }
                    initialized = true
                }
            }
        }
        return super.fetchMangaDetails(manga)
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        if (manga.url == LOGIN_MANGA_URL) {
            return Observable.fromCallable {
                val statusInfo = checkLiveAccountStatus()
                listOf(
                    SChapter.create().apply {
                        url = LOGIN_CHAPTER_SSO_URL
                        name = if (statusInfo.isSubscribed) {
                            "✓ ログイン完了 (${statusInfo.statusLabel}) — 再ログインはWebViewを開く"
                        } else {
                            "👉 右上のWebViewアイコンから楽天IDでログインしてください"
                        }
                        date_upload = System.currentTimeMillis()
                        chapter_number = 1f
                        scanlator = "Rakuten SSO"
                    },
                )
            }
        }
        return super.fetchChapterList(manga)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val titleId = extractTitleId(manga.url)
        return GET("$baseUrl/title/$titleId/relatedIssue.json", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val related = response.parseAs<RelatedIssuesResponse>()
        val backNumbers = related.backNumber.orEmpty()
        val latest = backNumbers.firstOrNull()
        return SManga.create().apply {
            if (latest != null) {
                val tName = latest.titleName.orEmpty()
                val iName = latest.issueName.orEmpty()
                val pubDate = latest.publicationDate.orEmpty()
                val desc = latest.description.orEmpty()

                title = tName.ifEmpty { iName }
                thumbnail_url = latest.imageUrl.orEmpty().ifEmpty { latest.thumbnail.orEmpty() }
                genre = latest.categoryName.orEmpty()
                description = buildString {
                    append("最新号: ").append(iName)
                    if (pubDate.length == 8) {
                        append(" (")
                            .append(pubDate.substring(0, 4)).append("-")
                            .append(pubDate.substring(4, 6)).append("-")
                            .append(pubDate.substring(6, 8))
                            .append(")")
                    }
                    append("\n配信中バックナンバー: ").append(backNumbers.size).append("冊")
                    if (desc.isNotEmpty()) {
                        append("\n\n").append(desc)
                    }
                }
                status = SManga.ONGOING
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val titleId = extractTitleId(manga.url)
        return GET("$baseUrl/title/$titleId/relatedIssue.json", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val related = response.parseAs<RelatedIssuesResponse>()
        val issues = related.backNumber.orEmpty().filter { it.contentUrlExist || it.previewUrlExist }
        val total = issues.size
        return issues.mapIndexed { index, issue ->
            val iName = issue.issueName.orEmpty().ifEmpty { issue.titleName.orEmpty() }
            SChapter.create().apply {
                url = "/read/${issue.issueId.orEmpty()}"
                name = buildString {
                    append(iName)
                    if (!issue.contentUrlExist && issue.previewUrlExist) {
                        append(" [試し読み]")
                    }
                }
                date_upload = parsePublicationDate(issue.publicationDate.orEmpty())
                chapter_number = (total - index).toFloat()
                scanlator = issue.categoryName.orEmpty()
            }
        }
    }

    // ============================= Reader: Page List & Decryption =============================

    override fun pageListRequest(chapter: SChapter): Request {
        if (chapter.url == LOGIN_CHAPTER_SSO_URL || chapter.url == LOGIN_CHAPTER_REDIRECT_URL) {
            throw Exception(
                "ログインするには、この画面の右上にある「WebView (地球/ブラウザ)」ボタンをタップして楽天IDでサインインしてください。\n" +
                    "(To log in, tap the WebView / Globe icon at the top-right corner of the screen.)",
            )
        }
        val issueId = chapter.url
            .substringAfterLast('/')
            .substringBefore('?')
        return GET("$baseUrl/read/$issueId", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val finalUrl = response.request.url.toString()
        val html = response.body.string()

        val issueIdFromRequest = response.request.url.pathSegments.lastOrNull().orEmpty()

        var contentUrl = CONTENT_URL_REGEX.find(html)?.groupValues?.get(1)?.replace("\\/", "/").orEmpty()
        var previewUrl = PREVIEW_URL_REGEX.find(html)?.groupValues?.get(1)?.replace("\\/", "/").orEmpty()
        var readMode = READ_MODE_REGEX.find(html)?.groupValues?.get(1).orEmpty()
        var readerString = READER_STRING_REGEX.find(html)?.groupValues?.get(1).orEmpty()

        if (contentUrl.isEmpty() && previewUrl.isEmpty()) {
            if (html.contains("30分間に2ブラウザまで")) {
                throw Exception(
                    "Rakuten Magazine rate limit: Web reading is limited to 2 browsers per 30 minutes. " +
                        "Please set a fixed _REM_UUID in extension settings or wait 30 minutes.",
                )
            }
            val allowPreview = preferences.getBoolean(PREF_FALLBACK_PREVIEW, true)
            if (allowPreview && issueIdFromRequest.isNotEmpty() && !finalUrl.contains("/read/preview/")) {
                val prevResp = client.newCall(GET("$baseUrl/read/preview/$issueIdFromRequest", headers)).execute()
                val prevHtml = prevResp.body.string()
                contentUrl = CONTENT_URL_REGEX.find(prevHtml)?.groupValues?.get(1)?.replace("\\/", "/").orEmpty()
                previewUrl = PREVIEW_URL_REGEX.find(prevHtml)?.groupValues?.get(1)?.replace("\\/", "/").orEmpty()
                readMode = READ_MODE_REGEX.find(prevHtml)?.groupValues?.get(1).orEmpty()
                readerString = READER_STRING_REGEX.find(prevHtml)?.groupValues?.get(1).orEmpty()
            }
        }

        if (contentUrl.isEmpty() && previewUrl.isEmpty()) {
            throw Exception(
                "Rakuten Magazine login required: Open the '【ログイン / Login Status】' entry and tap WebView to sign in, " +
                    "or paste your Rz cookie in the Rakuten Magazine extension settings.",
            )
        }

        val useFullContent = readMode == "CONTENT" && contentUrl.isNotEmpty() && readerString.isNotEmpty()
        val baseDocDir = if (useFullContent) {
            normalizeHttpsUrl(contentUrl) + "documents/"
        } else {
            if (!preferences.getBoolean(PREF_FALLBACK_PREVIEW, true)) {
                throw Exception(
                    "Not logged in to an active Rakuten Magazine subscription (readMode=$readMode). " +
                        "Please log in via WebView or configure your Rz cookie in extension settings.",
                )
            }
            normalizeHttpsUrl(previewUrl.ifEmpty { contentUrl }) + "documents/"
        }

        val passphraseBytes = if (readerString.isNotEmpty()) {
            Base64.decode(readerString, Base64.DEFAULT)
        } else {
            ByteArray(0)
        }

        val docInfoBytes = fetchBytes("${baseDocDir}documentInfo.proj")
        val docInfoJson = if (RakutenMagazineCrypto.isSaltedOpenSsl(docInfoBytes)) {
            if (passphraseBytes.isEmpty()) {
                throw Exception("Encrypted documentInfo.proj requires a valid Rakuten Magazine login session.")
            }
            String(RakutenMagazineCrypto.decryptOpenSslAes256Cbc(docInfoBytes, passphraseBytes), Charsets.UTF_8)
        } else {
            String(docInfoBytes, Charsets.UTF_8)
        }

        val docInfo = docInfoJson.parseAs<AquafadasDocumentInfo>()
        val readers = docInfo.readers.orEmpty()
        val readerEntry = readers.firstOrNull { it.type.orEmpty().equals("PDF", ignoreCase = true) }
            ?: readers.firstOrNull()
            ?: throw Exception("No supported reader entry found in documentInfo.proj")

        val avedFileName = readerEntry.fileName.orEmpty().ifEmpty { "AVED0.proj" }
        val avedBytes = fetchBytes("$baseDocDir$avedFileName")
        val avedJson = if (RakutenMagazineCrypto.isSaltedOpenSsl(avedBytes)) {
            String(RakutenMagazineCrypto.decryptOpenSslAes256Cbc(avedBytes, passphraseBytes), Charsets.UTF_8)
        } else {
            String(avedBytes, Charsets.UTF_8)
        }

        val avedProj = avedJson.parseAs<AquafadasAvedProj>()
        val flatPages = avedProj.pages.orEmpty().flatMap { it.pages.orEmpty() }
        if (flatPages.isEmpty()) {
            throw Exception("No pages found in $avedFileName")
        }

        return flatPages.mapIndexed { idx, page ->
            val pdfFile = page.fileName.orEmpty().ifEmpty { page.preview.orEmpty() }
            val imageUrl = "$baseDocDir$pdfFile#rakuten_pdf_extract=1"
            Page(idx, "", imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used")

    // ============================= Live Account Status Check =============================

    private data class AccountStatusInfo(
        val isSubscribed: Boolean,
        val statusLabel: String,
        val cookieSummary: String,
        val activeUuid: String,
    )

    private fun collectActiveCookies(): Map<String, String> {
        val cookieMap = LinkedHashMap<String, String>()

        // 1. Auto-saved cookies from prior WebView / HTTP responses
        val autoSaved = preferences.getString(PREF_AUTO_SAVED_COOKIES, "").orEmpty()
        if (autoSaved.isNotEmpty()) {
            cookieMap.putAll(parseCookieHeader(autoSaved))
        }

        // 2. Cookies from WebView / OkHttp CookieJar across Rakuten domains
        val urlsToQuery = listOf(
            baseUrl.toHttpUrl(),
            "https://www.rakuten.co.jp/".toHttpUrl(),
            "https://member.id.rakuten.co.jp/rms/nid/".toHttpUrl(),
        )
        for (u in urlsToQuery) {
            try {
                for (c in network.client.cookieJar.loadForRequest(u)) {
                    if (c.value.isNotEmpty()) {
                        cookieMap[c.name] = c.value
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 3. Explicit user-configured cookie override in preferences
        val customCookieInput = preferences.getString(PREF_COOKIE_STRING, "")?.trim().orEmpty()
        if (customCookieInput.isNotEmpty()) {
            if (customCookieInput.contains("=")) {
                cookieMap.putAll(parseCookieHeader(customCookieInput))
            } else {
                cookieMap["Rz"] = customCookieInput
            }
        }

        val customUuid = preferences.getString(PREF_DEVICE_UUID, "")?.trim().orEmpty()
        val activeUuid = customUuid.ifEmpty { defaultDeviceUuid }
        if (!cookieMap.containsKey("_REM_UUID")) {
            cookieMap["_REM_UUID"] = activeUuid
        }

        return cookieMap
    }

    private fun checkLiveAccountStatus(): AccountStatusInfo {
        val cookies = collectActiveCookies()
        val hasRz = !cookies["Rz"].isNullOrBlank()
        val hasRem = !cookies["_REM"].isNullOrBlank()
        val activeUuid = cookies["_REM_UUID"].orEmpty()

        val cookieParts = mutableListOf<String>()
        if (hasRz) cookieParts.add("Rz ✓") else cookieParts.add("Rz ✗")
        if (hasRem) cookieParts.add("_REM ✓") else cookieParts.add("_REM ✗")
        val cookieSummary = cookieParts.joinToString(", ")

        if (hasRz) {
            try {
                val req = GET("$baseUrl/v1.0/b2c/userstatus", headers)
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val statusResp = resp.parseAs<UserStatusResponse>()
                        val st = statusResp.userInfo?.status ?: -1
                        val plan = statusResp.userInfo?.planType.orEmpty()
                        val planLabel = when (plan) {
                            "2", "5" -> "月額プラン (Monthly)"
                            "3" -> "年額プラン (Yearly)"
                            "1" -> "Liteプラン"
                            "4" -> "フリーアクセス"
                            else -> "Plan $plan"
                        }
                        if (st in FULL_CONTENT_ALLOWED_STATUSES) {
                            return AccountStatusInfo(
                                isSubscribed = true,
                                statusLabel = "契約中 / 読み放題有効 ($planLabel)",
                                cookieSummary = cookieSummary,
                                activeUuid = activeUuid,
                            )
                        } else {
                            return AccountStatusInfo(
                                isSubscribed = false,
                                statusLabel = "ログイン済・未契約 (Status=$st, $planLabel)",
                                cookieSummary = cookieSummary,
                                activeUuid = activeUuid,
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Also check if _REM or Rz allows reading a sample issue directly
        if (hasRz || hasRem) {
            try {
                val sampleIssue = getLatestIssuesCatalog().firstOrNull { it.contentUrlExist }
                if (sampleIssue != null) {
                    val req = GET("$baseUrl/read/${sampleIssue.issueId}", headers)
                    client.newCall(req).execute().use { resp ->
                        val html = resp.body.string()
                        val mode = READ_MODE_REGEX.find(html)?.groupValues?.get(1).orEmpty()
                        if (mode == "CONTENT") {
                            return AccountStatusInfo(
                                isSubscribed = true,
                                statusLabel = "契約中 / 読み放題有効 (WebReader CONTENT)",
                                cookieSummary = cookieSummary,
                                activeUuid = activeUuid,
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        return AccountStatusInfo(
            isSubscribed = false,
            statusLabel = "未ログイン (Not Logged In)",
            cookieSummary = cookieSummary,
            activeUuid = activeUuid,
        )
    }

    // ============================= OkHttp Interceptor =============================

    private fun authAndImageInterceptor(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url

        // 1. Attach session cookies (Rz, _REM, _REM_UUID) for magazine.rakuten.co.jp requests
        val requestToExecute = if (url.host.endsWith("rakuten.co.jp")) {
            val cookieMap = collectActiveCookies().toMutableMap()
            for ((k, v) in parseCookieHeader(originalRequest.header("Cookie").orEmpty())) {
                cookieMap.putIfAbsent(k, v)
            }

            // Persist any newly discovered Rz or _REM cookies from WebView CookieJar
            val savedCookies = mutableMapOf<String, String>()
            cookieMap["Rz"]?.takeIf { it.isNotEmpty() }?.let { savedCookies["Rz"] = it }
            cookieMap["_REM"]?.takeIf { it.isNotEmpty() }?.let { savedCookies["_REM"] = it }
            if (savedCookies.isNotEmpty()) {
                val serialized = savedCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                if (preferences.getString(PREF_AUTO_SAVED_COOKIES, null) != serialized) {
                    preferences.edit().putString(PREF_AUTO_SAVED_COOKIES, serialized).apply()
                }
            }

            val newBuilder = originalRequest.newBuilder()
            if (cookieMap.isNotEmpty()) {
                val mergedCookie = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                newBuilder.header("Cookie", mergedCookie)
            }
            if (url.encodedPath.startsWith("/v1.0/") && cookieMap["Rz"] != null && originalRequest.header("Token-Z") == null) {
                newBuilder.header("Token-Z", cookieMap["Rz"]!!)
            }
            newBuilder.build()
        } else {
            originalRequest
        }

        val response = chain.proceed(requestToExecute)

        // Capture server-assigned _REM_UUID or _REM if returned in Set-Cookie
        for (setCookie in response.headers("Set-Cookie")) {
            if (setCookie.startsWith("_REM_UUID=")) {
                val uuidVal = setCookie.substringAfter("_REM_UUID=").substringBefore(";").trim()
                if (uuidVal.isNotEmpty() && preferences.getString(PREF_DEVICE_UUID, "").isNullOrBlank()) {
                    preferences.edit().putString(PREF_AUTO_REM_UUID, uuidVal).apply()
                }
            } else if (setCookie.startsWith("_REM=")) {
                val remVal = setCookie.substringAfter("_REM=").substringBefore(";").trim()
                if (remVal.isNotEmpty()) {
                    val existing = parseCookieHeader(preferences.getString(PREF_AUTO_SAVED_COOKIES, "").orEmpty()).toMutableMap()
                    existing["_REM"] = remVal
                    val serialized = existing.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    preferences.edit().putString(PREF_AUTO_SAVED_COOKIES, serialized).apply()
                }
            }
        }

        // 2. If this is an Aquafadas single-page PDF request, extract the embedded high-res JPEG stream
        val isAquafadasPdf = url.fragment == "rakuten_pdf_extract=1" ||
            (url.host.contains("magazine.rakuten.co.jp") && url.encodedPath.endsWith(".pdf"))

        if (isAquafadasPdf && response.isSuccessful) {
            val pdfBytes = response.body.bytes()
            val jpegBytes = RakutenMagazineCrypto.extractJpegFromAquafadasPdf(pdfBytes)
            if (jpegBytes != null) {
                return response.newBuilder()
                    .header("Content-Type", "image/jpeg")
                    .header("Content-Length", jpegBytes.size.toString())
                    .body(jpegBytes.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }
            return response.newBuilder()
                .body(pdfBytes.toResponseBody(response.body.contentType()))
                .build()
        }

        return response
    }

    // ============================= Settings / Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        CheckBoxPreference(screen.context).apply {
            key = PREF_SHOW_LOGIN_CARD
            title = "カタログ先頭に「ログイン / Login Status」を表示"
            summary = "カタログの1ページ目先頭にWebViewログイン＆ステータス確認用の項目を表示します。"
            setDefaultValue(true)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_COOKIE_STRING
            title = "Rakuten Session Cookie (Rz / Full Cookie)"
            summary = "WebViewでログインできない場合（iOSのCookie共有制限など）に、ブラウザの `Rz` Cookie値または `Rz=...; _REM=...` を貼り付けてください。"
            setDefaultValue("")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_DEVICE_UUID
            title = "Browser Device UUID (_REM_UUID)"
            summary = "楽天マガジンは30分間に2ブラウザまでの制限があります。空欄の場合は自動生成された固定UUID ($defaultDeviceUuid) を使用します。"
            setDefaultValue("")
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = PREF_FALLBACK_PREVIEW
            title = "未ログイン時に試し読み (Preview) を許可"
            summary = "有効な場合、未ログイン時でも試し読み可能な雑誌はプレビューページを表示します。"
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    // ============================= Helpers =============================

    private fun fetchBytes(url: String): ByteArray {
        val req = GET(url, headers)
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("HTTP ${resp.code} fetching $url")
            }
            return resp.body.bytes()
        }
    }

    private fun extractTitleId(url: String): String {
        val clean = url.substringBefore('?').trimEnd('/')
        return clean.substringAfterLast('/')
    }

    private fun normalizeHttpsUrl(url: String): String {
        val https = if (url.startsWith("http://")) {
            "https://" + url.removePrefix("http://")
        } else {
            url
        }
        return if (https.endsWith("/")) https else "$https/"
    }

    private fun parseCookieHeader(header: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        header.split(';').forEach { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val k = trimmed.substring(0, eq).trim()
                val v = trimmed.substring(eq + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    map[k] = v
                }
            }
        }
        return map
    }

    private fun parsePublicationDate(dateStr: String): Long {
        if (dateStr.length != 8) return 0L
        return try {
            val sdf = SimpleDateFormat("yyyyMMdd", Locale.JAPAN).apply {
                timeZone = TimeZone.getTimeZone("Asia/Tokyo")
            }
            sdf.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun normalizeForSearch(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when {
                ch.isWhitespace() -> continue
                // Hiragana (U+3041..U+3096) -> Katakana (U+30A1..U+30F6)
                ch in '\u3041'..'\u3096' -> sb.append((ch.code + 0x60).toChar())
                // Full-width ASCII (U+FF01..U+FF5E) -> Half-width ASCII
                ch in '\uFF01'..'\uFF5E' -> sb.append((ch.code - 0xFEE0).toChar().lowercaseChar())
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    companion object {
        private const val PAGE_SIZE = 24
        private const val CATALOG_CACHE_TTL_MS = 5 * 60 * 1000L

        const val LOGIN_MANGA_URL = "/login"
        const val LOGIN_CHAPTER_SSO_URL = "/login/sso"
        const val LOGIN_CHAPTER_REDIRECT_URL = "/login/redirect"

        const val RAKUTEN_SSO_LOGIN_URL =
            "https://login.account.rakuten.com/sso/authorize?client_id=rakuten_magazine_web&redirect_uri=https://magazine.rakuten.co.jp/login/auth&ui_locales=ja-JP&scope=openid%20profile%20email&response_type=code#/sign_in"

        private val FULL_CONTENT_ALLOWED_STATUSES = setOf(0, 1, 5, 7, 8, 21, 22, 23, -6)

        const val PREF_SHOW_LOGIN_CARD = "pref_rakuten_show_login_card"
        const val PREF_COOKIE_STRING = "pref_rakuten_cookie_string"
        const val PREF_DEVICE_UUID = "pref_rakuten_device_uuid"
        const val PREF_AUTO_REM_UUID = "pref_rakuten_auto_rem_uuid"
        const val PREF_AUTO_SAVED_COOKIES = "pref_rakuten_auto_saved_cookies"
        const val PREF_FALLBACK_PREVIEW = "pref_rakuten_fallback_preview"

        private val CONTENT_URL_REGEX = Regex("""var\s+contentUrl\s*=\s*"([^"]*)"""")
        private val PREVIEW_URL_REGEX = Regex("""var\s+previewUrl\s*=\s*"([^"]*)"""")
        private val READ_MODE_REGEX = Regex("""var\s+readMode\s*=\s*"([^"]*)"""")
        private val READER_STRING_REGEX = Regex("""var\s+readerString\s*=\s*"([^"]*)"""")
    }
}
