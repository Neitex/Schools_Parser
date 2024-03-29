package com.neitex

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import it.skrape.core.htmlDocument
import it.skrape.selects.CssSelector
import it.skrape.selects.DocElement
import it.skrape.selects.ElementNotFoundException
import it.skrape.selects.eachText
import it.skrape.selects.html5.*
import java.net.http.HttpConnectTimeoutException
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.time.*
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.collections.set

internal fun HttpResponse.checkCredentialsClear(): Boolean =
    this.setCookie().find { it.name == "sessionid" && it.value == "" } == null

class SchoolsByParser {
    companion object {
        private var client: HttpClient by mutableLazy {
            HttpClient(CIO) {
                engine {
                    requestTimeout = 10000
                    maxConnectionsCount = 50
                    threadsCount = 10
                    endpoint {
                        connectAttempts = 2
                        connectTimeout = 10000
                    }
                    https {
                        trustManager = object :
                            X509TrustManager { // Custom trust manager to ignore SSL errors when using block bypass
                            val systemTrustManager =
                                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                                    init(null as KeyStore?)
                                }.trustManagers.filterIsInstance<X509TrustManager>().first()

                            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                                return systemTrustManager.checkClientTrusted(chain, authType)
                            }

                            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                                if (useBlockBypass) return
                                return systemTrustManager.checkServerTrusted(chain, authType)
                            }

                            override fun getAcceptedIssuers(): Array<X509Certificate> {
                                return systemTrustManager.acceptedIssuers
                            }
                        }
                    }
                }
                expectSuccess = false
                followRedirects = true
                install(UserAgent)
                BrowserUserAgent()
            }
        }

        var schoolSubdomain: String = ""
            private set

        /**
         * Closes Ktor Http Client. After that all resources, used by library will be freed and library will become unusable
         * (unless you set a new HTTP Client manually)
         */
        @Suppress("UNUSED") // It is not used in tests, so it appears to be unused,
        // but it is required for a library to be capable of freeing its resources
        fun closeHttpClient() {
            client.close()
        }

        /**
         * Sets school's subdomain.
         *
         * Example: https://demo.schools.by/ (notice trailing slash)
         */
        fun setSubdomain(url: String) {
            if (!url.matches(Regex("https?://[a-zA-Z0-9-]+\\.schools\\.by/"))) {
                throw IllegalArgumentException("Invalid subdomain")
            } else {
                schoolSubdomain = url
            }
        }

        /**
         * Enables block bypass. This is a workaround for a geographical restriction to Belarus-only access.
         * It is recommended to use it as a last resort because it is not guaranteed to work.
         */
        var useBlockBypass: Boolean = false

        /**
         * Sets custom Http client for library to use
         */
        @Suppress("UNUSED") // It is not used in tests, so it appears to be unused,
        // but it is required for a library to be capable of changing HTTP Client (i.e. for custom tests)
        fun setHttpClient(client: HttpClient) {
            this.client = client
        }

        internal val logger = org.apache.log4j.Logger.getLogger("SchoolsByParser")

        internal fun noNulls(vararg values: Any?): Boolean = values.none { it == null }

        internal fun <T> CssSelector.nullableFind(init: CssSelector.() -> T): T? = try {
            this.init()
        } catch (e: ElementNotFoundException) {
            null
        }

        internal suspend inline fun <T> wrapReturn(
            requestUrl: String, credentials: Credentials?, returnValue: (HttpResponse) -> Result<T>
        ): Result<T> {
            return try {
                val response = if (!useBlockBypass) client.get {
                    url(requestUrl)
                    credentials?.also {
                        cookie("csrftoken", credentials.csrfToken)
                        cookie("sessionid", credentials.sessionID)
                    }
                } else client.get {
                    url(
                        "https://134.17.89.48/${
                            requestUrl.removePrefix("https://schools.by/").removePrefix(
                                schoolSubdomain
                            )
                        }"
                    )
                    headers.append(HttpHeaders.Host, schoolSubdomain.removePrefix("https://").removeSuffix("/"))
                    credentials?.also {
                        cookie("csrftoken", credentials.csrfToken)
                        cookie("sessionid", credentials.sessionID)
                    }
                }
                if (!response.checkCredentialsClear() || response.request.url.toString()
                        .contains("already-redirected")
                ) return Result.failure(BadSchoolsByCredentials())
                if (response.status == HttpStatusCode.NotFound) return Result.failure(PageNotFound("Page \'$requestUrl\' was not found"))
                return returnValue(response)
            } catch (e: HttpRequestTimeoutException) {
                Result.failure(SchoolsByUnavailable("Schools.by did not respond", e))
            } catch (e: HttpConnectTimeoutException) {
                Result.failure(SchoolsByUnavailable("Schools.by did not respond", e))
            } catch (e: ElementNotFoundException) {
                Result.failure(UnknownError("Page parsing failed: Exception: \'${e.message}\'; Stack trace: ${e.stackTraceToString()}"))
            } catch (e: UnknownError) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Functions related to authentication
     */
    object AUTH {
        /**
         * Logins user to Schools.by and returns [Credentials]
         */
        suspend fun getLoginCookies(username: String, password: String): Result<Credentials> {
            val (csrf, addFields) = wrapReturn("https://schools.by/login", null) {
                val csrf = it.setCookie().find { it.name.lowercase() == "csrftoken" }?.value
                    ?: return@wrapReturn Result.failure(UnknownError("CSRF token not found"))
                val inputs = htmlDocument(it.bodyAsText()) {
                    div {
                        withClass = "login_page_body"
                        form {
                            input {
                                withAttribute = "type" to "hidden"
                                findAll {
                                    map { it.attribute("name") to it.attribute("value") }
                                }
                            }
                        }
                    }
                }.filter {
                    it.first !in listOf("submit", "password", "username")
                }.toMap()
                Result.success(csrf to inputs)
            }.fold({ it }, { return Result.failure(it) })
            try {
                val (secondCSRFtoken, sessionid) = run {
                    val response =
                        client.submitForm(url = "https://schools.by/login", formParameters = Parameters.build {
                            append("username", username)
                            append("password", password)
                            addFields.forEach { entry ->
                                append(entry.key, entry.value)
                            }
                        }) {
                            cookie("csrftoken", csrf)
                            header(HttpHeaders.Referrer, "https://schools.by/login")
                        }
                    if ((response.headers["location"]
                            ?: "https://schools.by/login").contains("login")
                    ) return Result.failure(AuthorizationUnsuccessful())
                    Pair(
                        response.setCookie().find { it.name == "csrftoken" }?.value ?: return Result.failure(
                            UnknownError("Second stage of login failed: csrftoken cookie was not found")
                        ), response.setCookie().find { it.name == "sessionid" }?.value ?: return Result.failure(
                            UnknownError("Second stage of login failed: csrftoken cookie was not found")
                        )
                    )
                }
                return Result.success(Credentials(secondCSRFtoken, sessionid))
            } catch (e: HttpRequestTimeoutException) {
                return Result.failure(SchoolsByUnavailable("Schools.by did not respond", e))
            } catch (e: HttpConnectTimeoutException) {
                return Result.failure(SchoolsByUnavailable("Schools.by did not respond", e))
            }
        }

        /**
         * Checks if cookies are still valid
         */
        suspend fun checkCookies(credentials: Credentials): Result<Boolean> {
            return try {
                val response = client.get {
                    url(schoolSubdomain)
                    cookie("csrftoken", credentials.csrfToken)
                    cookie("sessionid", credentials.sessionID)
                }
                Result.success(response.setCookie().find {
                    it.name == "sessionid" && it.value == ""
                } == null)
            } catch (e: HttpRequestTimeoutException) {
                Result.failure(SchoolsByUnavailable("Schools.by did not respond", e))
            } catch (e: HttpConnectTimeoutException) {
                Result.failure(SchoolsByUnavailable("Schools.by did not respond", e))
            }
        }
    }

    /**
     * Calls and parsers related to users
     */
    object USER {
        /**
         * Returns ID of user, whose [credentials] were supplied to function
         */
        suspend fun getUserIDFromCredentials(credentials: Credentials): Result<Int> {
            val response = HttpClient(CIO) {
                followRedirects = false
                expectSuccess = false
            }.use {
                it.get {
                    url("https://schools.by/login")
                    cookie("csrftoken", credentials.csrfToken)
                    cookie("sessionid", credentials.sessionID)
                }
            }
            if (!response.checkCredentialsClear()) return Result.failure(BadSchoolsByCredentials())
            when (response.status) {
                HttpStatusCode.NotFound -> return Result.failure(UnknownError("Login page was not found, request page: \'${response.request.url}\'"))
                HttpStatusCode.OK -> return Result.failure(UnknownError("Schools.by returned unusual HTTP Code OK"))
            }
            return Result.success(
                response.headers["location"]?.replaceBeforeLast('/', "")?.removePrefix("/")?.toIntOrNull()
                    ?: return Result.failure(UnknownError("User ID was not found in location. Location header: ${response.headers["location"]}"))
            )
        }

        /**
         * Parses page of user with ID [userID]
         * @param credentials Schools.by credentials
         */
        suspend fun getBasicUserInfo(userID: Int, credentials: Credentials): Result<User> {
            return wrapReturn("${schoolSubdomain}user/$userID", credentials) { response ->
                htmlDocument(response.bodyAsText()) {
                    val nameText = div {
                        withClass = "title_box"
                        h1 {
                            findFirst {
                                ownText
                            }
                        }
                    }
                    val name = Name.fromString(nameText)
                        ?: return@htmlDocument Result.failure(UnknownError("Name detection failed: bad input: \'$nameText\'"))
                    val type = SchoolsByUserType.valueOf(
                        response.request.url.encodedPath.replaceAfterLast('/', "")
                            .removeSuffix("/").replaceBefore('/', "").removePrefix("/").uppercase()
                    )
                    Result.success(User(userID, type, name))
                }
            }
        }
    }

    /**
     * Parsers related to school classes
     */
    object CLASS {
        /**
         * Returns [SchoolClass] based on class ID
         */
        suspend fun getClassData(classID: Int, credentials: Credentials): Result<SchoolClass> {
            return wrapReturn("${schoolSubdomain}class/$classID", credentials) { response ->
                htmlDocument(response.bodyAsText()) {
                    val classTitle = div {
                        withClass = "title_box"
                        h1 {
                            findFirst {
                                ownText
                            }
                        }
                    }
                    val classTeacherID = div {
                        withClass = "main_grid_center_column"
                        div {
                            withClass = "main_grid_content"
                            div {
                                withClass = "grid_st_r"
                                div {
                                    withClass = "r_user_info"
                                    customTag("", "> p.role + p.name") {
                                        a {
                                            findFirst {
                                                attribute("href").removePrefix("${schoolSubdomain}teacher/")
                                                    .toIntOrNull()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                        ?: return@htmlDocument Result.failure(UnknownError("Class teacher ID detection failure: no class teacher ID was found"))
                    Result.success(SchoolClass(classID, classTeacherID, classTitle))
                }
            }
        }

        /**
         * Returns list of pupils in class with [classID]
         */
        suspend fun getPupilsList(classID: Int, credentials: Credentials): Result<List<Pupil>> {
            return wrapReturn("${schoolSubdomain}class/$classID/pupils/editall", credentials) { response ->
                htmlDocument(response.bodyAsText()) {
                    div {
                        withClass = "edit_user_fio"
                        findAll {
                            val pupils = this.mapIndexedNotNull { index, docElement ->
                                val id = docElement.input {
                                    withId = "id_stable-$index-id"
                                    nullableFind {
                                        findFirst { attribute("value").toIntOrNull() }
                                    }
                                } ?: return@mapIndexedNotNull null
                                val lastName = docElement.div {
                                    withClass = "input-text"
                                    nullableFind {
                                        findFirst { ownText }
                                    }
                                }
                                val firstName = docElement.div {
                                    withClass = "input-text"
                                    nullableFind {
                                        findSecond {
                                            ownText
                                        }
                                    }
                                }
                                val middleName = docElement.input {
                                    withId = "id_stable-$index-father_name"
                                    nullableFind {
                                        findFirst {
                                            attribute("value").ifBlank { null }
                                        }
                                    }
                                }
                                if (noNulls(id, firstName, lastName)) {
                                    Pupil(id, Name(firstName!!, middleName, lastName!!), classID)
                                } else null
                            }
                            Result.success(pupils)
                        }
                    }
                }
            }
        }

        /**
         * Returns [Timetable] of class with [classID]
         * @param walkToJournals Defines, whether the library should walk into journals or not (i.e. to get teachers ID's).
         * Walking will take **much, much** more times, but will provide you with the fullest information possible
         * @see getClassShift returns true shift
         */
        suspend fun getTimetable(
            classID: Int, credentials: Credentials, walkToJournals: Boolean = false
        ): Result<Timetable> {
            return wrapReturn("${schoolSubdomain}class/$classID/timetable", credentials) {
                val timetableMap = mutableMapOf<DayOfWeek, Array<TimetableLesson>>()
                htmlDocument(it.bodyAsText()) {
                    div {
                        withClass = "ttb_boxes"
                        div {
                            withClass = "ttb_box"
                            findAll {
                                forEachIndexed { _, mainDiv ->
                                    val day = russianDayNameToDayOfWeek(mainDiv.div {
                                        withClass = "ttb_day"; findFirst { ownText }
                                    })
                                    var dayTimetable = arrayOf<TimetableLesson>()
                                    mainDiv.tbody {
                                        tr {
                                            findAll {
                                                forEachIndexed { _, doc ->
                                                    var add = true
                                                    val num =
                                                        doc.td { withClass = "num"; findFirst { ownText } }.dropLast(1)
                                                            .toInt()
                                                    val time = TimeConstraints.fromString(doc.td {
                                                        withClass = "time"; findFirst { ownText }
                                                    })
                                                        ?: throw UnknownError("Failed detecting lesson time; Current element: \'$doc\'")
                                                    val name = doc.td {
                                                        val titles = mutableListOf<String>()
                                                        withClass = "subjs"; findFirst {
                                                        try {
                                                            a {
                                                                findAll {
                                                                    forEach { doc ->
                                                                        if (!titles.contains(doc.attribute("title")) && doc.attribute(
                                                                                "title"
                                                                            ).isNotEmpty()
                                                                        ) titles.add(doc.attribute("title"))
                                                                    }
                                                                }
                                                            }
                                                        } catch (e: ElementNotFoundException) {
                                                            try {
                                                                span {
                                                                    findAll {
                                                                        forEach { doc ->
                                                                            if (!titles.contains(doc.attribute("title")) && doc.attribute(
                                                                                    "title"
                                                                                ).isNotEmpty()
                                                                            ) titles.add(doc.attribute("title"))
                                                                        }
                                                                    }
                                                                }
                                                            } catch (e: ElementNotFoundException) {
                                                                add = false
                                                            }
                                                        }
                                                    }

                                                        return@td titles.toSet().joinToString(" / ")
                                                    }
                                                    val journalID = try {
                                                        doc.a {
                                                            withClass = "subj"
                                                            findFirst {
                                                                attribute("href").removePrefix("/journal/")
                                                                    .toIntOrNull()
                                                            }
                                                        }
                                                    } catch (e: ElementNotFoundException) {
                                                        null
                                                    }
                                                    if (add) dayTimetable += TimetableLesson(
                                                        num.toShort(), time, name, classID, null, journalID
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    timetableMap[day] = dayTimetable
                                }
                            }
                        }
                    }
                    timetableMap
                }
                if (walkToJournals) {
                    val cacheMap = mutableMapOf<Int, Array<Int>>() // JournalID, Array<TeacherID>
                    for (entry in timetableMap) {
                        for ((index, lesson) in entry.value.withIndex()) {
                            if (lesson.journalID != null) {
                                val teachers = if (!cacheMap.contains(lesson.journalID)) wrapReturn(
                                    "${schoolSubdomain}/journal/${lesson.journalID}", credentials
                                ) {
                                    val htmlTeachers = mutableListOf<Int>()
                                    htmlDocument(it.bodyAsText()) {
                                        div {
                                            withClass = "journal_teachers"
                                            div {
                                                withClass = "cnt"
                                                li {
                                                    findAll {
                                                        for (element in this) {
                                                            (try {
                                                                element.a {
                                                                    findFirst {
                                                                        attribute("href").removePrefix("/teacher/")
                                                                            .removePrefix("/administration/")
                                                                            .removePrefix("/director/").toIntOrNull()
                                                                    }
                                                                }
                                                            } catch (e: ElementNotFoundException) {
                                                                null
                                                            })?.also {
                                                                if (!try {
                                                                        element.small {
                                                                            findAll {
                                                                                eachText.any { it.contains("классн") }
                                                                            }
                                                                        }
                                                                    } catch (e: ElementNotFoundException) {
                                                                        false
                                                                    }
                                                                ) htmlTeachers.add(it)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Result.success(htmlTeachers)
                                }.getOrElse {
                                    Logger.getAnonymousLogger().log(
                                        Level.WARNING, "Failed to walk to journal with ID ${lesson.journalID}", it
                                    )
                                    arrayListOf()
                                }.toSet().toTypedArray().also { cacheMap[lesson.journalID] = it }
                                else cacheMap[lesson.journalID]!!
                                entry.setValue(entry.value.toMutableList().apply {
                                    removeAt(index)
                                    add(index, lesson.copy(teacherID = teachers))
                                }.toTypedArray())
                            }
                        }
                    }
                }
                return@wrapReturn Result.success(Timetable(timetableMap))
            }
        }

        /**
         * Returns [Boolean] indicating, if given class studies in second shift or not
         */
        suspend fun getClassShift(classID: Int, credentials: Credentials): Result<Boolean> =
            wrapReturn("${schoolSubdomain}/class/$classID/edit", credentials) {
                htmlDocument(it.bodyAsText()) {
                    select {
                        withId = "id_smena"
                        option {
                            withAttribute = Pair("selected", "")
                            findFirst {
                                return@findFirst Result.success(attribute("value") != "1")
                            }
                        }
                    }
                }
            }

        /**
         * Returns an array of Pair(PupilID, OrderInClass)
         */
        suspend fun getPupilsOrdering(classID: Int, credentials: Credentials): Result<Array<Pair<Int, Short>>> =
            wrapReturn("${schoolSubdomain}/class/$classID/pupils/ordering", credentials) {
                val pairings = mutableListOf<Pair<Int, Short>>()
                htmlDocument(it.bodyAsText()) {
                    val pupilsCount = input {
                        withId = "id_form-TOTAL_FORMS"
                        findFirst {
                            attribute("value").toInt()
                        }
                    }
                    for (i in 0 until pupilsCount) {
                        pairings.add(Pair(input {
                            withId = "id_form-$i-id"
                            findFirst {
                                attribute("value").toInt()
                            }
                        }, input {
                            withId = "id_form-$i-order"
                            findFirst {
                                attribute("value").toShort()
                            }
                        }))
                    }
                }
                Result.success(pairings.toTypedArray())
            }

        /**
         * Returns all transfers of pupils in given class. May contain records of pupils transferring from given class to other classes.
         */
        suspend fun getTransfers(
            classID: Int, credentials: Credentials
        ): Result<Map<Int, List<Pair<Pair<Int?, Int>, LocalDate>>>> =
            wrapReturn("${schoolSubdomain}/class/$classID/pupils/transfer", credentials) { response ->
                val dateRegex = Regex("""(\d{1,2})\s+([А-я]+)\s+(\d{4})""")
                val dateFormat = SimpleDateFormat("dd MMMM yyyy", Locale.forLanguageTag("ru"))
                val dates = mutableMapOf<Int, List<Pair<Pair<Int?, Int>, LocalDate>>>()
                htmlDocument(response.bodyAsText()) {
                    tr {
                        withClass = "row-pupil"
                        findAll {
                            this.forEach { row ->
                                val pupilID = row.customTag(
                                    "",
                                    "a.user_type_1, a.user_type_2, a.user_type_3, a.user_type_4, a.user_type_5, a.user_type_6, a.user_type_7"
                                ) {
                                    nullableFind {
                                        findFirst {
                                            attribute("href").removePrefix("/pupil/").toIntOrNull()
                                        }
                                    }
                                }
                                val history = nullableFind {
                                    val entries = mutableListOf<Pair<Pair<Int?, Int>, LocalDate>>()
                                    row.ul {
                                        withClass = "history"
                                        li {
                                            findAll {
                                                forEach {
                                                    entries.add(when (it.a {
                                                        findAll {
                                                            this.size
                                                        }
                                                    }) {
                                                        1, 2 -> {
                                                            Pair(
                                                                Pair(null, it.a {
                                                                    findFirst {
                                                                        attribute("href").removePrefix("/class/")
                                                                            .toInt()
                                                                    }
                                                                }), LocalDate.ofInstant(
                                                                    dateFormat.parse(dateRegex.find(it.ownText)!!.value)
                                                                        .toInstant(), ZoneId.of("Europe/Minsk")
                                                                )
                                                            )
                                                        }

                                                        3, 4 -> {
                                                            Pair(
                                                                Pair(it.a {
                                                                    findFirst {
                                                                        attribute("href").removePrefix("/class/")
                                                                            .toInt()
                                                                    }
                                                                }, it.a {
                                                                    findSecond {
                                                                        attribute("href").removePrefix("/class/")
                                                                            .toInt()
                                                                    }
                                                                }), LocalDate.ofInstant(
                                                                    dateFormat.parse(dateRegex.find(it.ownText)!!.value)
                                                                        .toInstant(), ZoneId.of("Europe/Minsk")
                                                                )
                                                            )
                                                        }

                                                        else -> throw IllegalStateException("Unexpected value: ${
                                                            it.a {
                                                                findAll {
                                                                    this.size
                                                                }
                                                            }
                                                        }")
                                                    })
                                                }
                                            }
                                        }
                                    }
                                    entries
                                }
                                if (pupilID != null && history != null) {
                                    dates[pupilID] = history
                                }
                            }
                        }
                    }
                }
                Result.success(dates)
            }

        suspend fun getLessonsListByJournal(
            classID: Int, journalID: Int, classSubgroupTitles: Map<Int, String>, credentials: Credentials
        ): Result<List<Lesson>> = wrapReturn("${schoolSubdomain}class/$classID/lessons/$journalID/show", credentials) {
            val dateRegex = Regex("""(\d{1,2})\s+([А-я]+)\s*(\d{4})?""")
            val dateFormat = SimpleDateFormat("dd MMMM yyyy", Locale.forLanguageTag("ru"))

            fun DocElement.parseTable(title: String, subgroupID: Int?, teacherID: Int?) = this.table {
                tbody {
                    tr {
                        findAll {
                            this.mapNotNull { row ->
                                runCatching {
                                    val (id, date) = row.td {
                                        withClass = "date"
                                        return@td findFirst {
                                            val date = LocalDate.ofInstant(dateRegex.find(text)?.value?.let {
                                                if (it.takeLast(4).toIntOrNull() == null) "$it ${LocalDate.now().year}"
                                                else it
                                            }?.let { dateFormat.parse(it).toInstant() } ?: when {
                                                text.contains("сегодня", true) -> Instant.now()
                                                text.contains("вчера", true) -> LocalDateTime.now().minusDays(1)
                                                    .toInstant(
                                                        ZoneOffset.of("+03:00")
                                                    )

                                                text.contains("завтра", true) -> LocalDateTime.now().minusDays(1)
                                                    .toInstant(
                                                        ZoneOffset.of("+03:00")
                                                    )

                                                else -> throw IllegalArgumentException()

                                            }, ZoneId.of("Europe/Minsk"))
                                            val id = this.attribute("lesson_id").toLong()
                                            id to date
                                        }
                                    }
                                    val place = row.td { withClass = "number"; findFirst { text.toInt() } }
                                    Lesson(
                                        id, journalID, teacherID, subgroupID, title, date, place
                                    )
                                }.fold({ l -> l }, {
                                    logger.warn("Failed to parse lesson", it)
                                    null
                                })
                            }
                        }
                    }
                }
            }

            htmlDocument(it.bodyAsText()) {
                val title = div {
                    withClass = "title_box2"
                    a {
                        findAll {
                            this.first { it.attribute("href").startsWith("/journal") }.ownText
                        }
                    }
                }
                return@htmlDocument Result.success(div {
                    withClass = "group"
                    nullableFind { // There may be a subject without lessons
                        findAll {
                            val mapped = map {
                                val (subgroup, teacherID) = it.div {
                                    withClass = "title"
                                    val subgroup = h2 {
                                        nullableFind {
                                            findFirst {
                                                val subgroupName = text.trim().takeIf { it.contains("Подгруппа") }
                                                if (subgroupName != null)
                                                    classSubgroupTitles.entries.firstOrNull {
                                                        it.value == text.trim().removePrefix("Подгруппа \"")
                                                            .removeSuffix("\"")
                                                    }?.key
                                                else null
                                            }
                                        }
                                    }
                                    val teacher = it.p {
                                        withClass = "teacher"
                                        "a.user_type_1, a.user_type_2, a.user_type_3, a.user_type_4, a.user_type_5, a.user_type_6, a.user_type_7" {
                                            nullableFind {
                                                findFirst {
                                                    attribute("href").removePrefix("/teacher/")
                                                        .removePrefix("/administration/").removePrefix("/director/")
                                                        .toInt()
                                                }
                                            }
                                        }
                                    }
                                    subgroup to teacher
                                }
                                it.parseTable(title, subgroup, teacherID)
                            }.flatten()
                            mapped
                        }
                    } ?: emptyList()
                })
            }
        }

        suspend fun getAllLessons(
            classID: Int, classSubgroupTitles: Map<Int, String>, credentials: Credentials
        ): Result<List<Lesson>> = wrapReturn("${schoolSubdomain}class/$classID/lessons", credentials) {
            val journals = htmlDocument(it.bodyAsText()) {
                val regex = Regex("/class/\\d+/lessons/(\\d+)/show")
                return@htmlDocument div {
                    withClass = "llitm_center2"
                    a {
                        findAll {
                            this.map {
                                regex.find(it.attribute("href"))!!.groupValues[1].toInt()
                            }
                        }
                    }
                }
            }
            Result.success(journals.mapNotNull { journal ->
                getLessonsListByJournal(classID, journal, classSubgroupTitles, credentials).let {
                    if (it.isFailure) {
                        logger.warn("Failed to get lessons for journal $journal", it.exceptionOrNull())
                        null
                    } else it.getOrNull()
                }
            }.flatten())
        }

        suspend fun getSubgroups(classID: Int, credentials: Credentials): Result<List<Subgroup>> =
            wrapReturn("${schoolSubdomain}class/$classID/subgroups", credentials) {
                Result.success(htmlDocument(it.bodyAsText()) {
                    div {
                        withClass = "class_subgroup"
                        kotlin.runCatching {
                            findAll {
                                mapNotNull {
                                    kotlin.runCatching {
                                        val title = it.div {
                                            withClass = "title"
                                            b {
                                                findFirst {
                                                    ownText.trim()
                                                }
                                            }
                                        }
                                        val id = it.div {
                                            withClass = "title"
                                            a {
                                                findFirst {
                                                    attribute("href").removePrefix("/class/$classID/subgroup/")
                                                        .removeSuffix("/edit").toInt()
                                                }
                                            }
                                        }
                                        val pupils = it.ul {
                                            withClass = "pupils"
                                            li {
                                                this.nullableFind {
                                                    findAll {
                                                        mapNotNull {
                                                            if (it.a { findAll { this } }.size >= 2) null
                                                            else it.a {
                                                                findFirst {
                                                                    attribute("href").removePrefix("/pupil/").toInt()
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        } ?: listOf()
                                        Subgroup(id, title, pupils)
                                    }.fold({ res -> res }, { e ->
                                        logger.warn("Failed to parse subgroup", e)
                                        null
                                    })
                                }
                            }
                        }.getOrNull() ?: emptyList()
                    }
                })
            }
    }

    /**
     * Functions related to Teachers/Directors/Administrators
     */
    object TEACHER {

        /**
         * Returns [SchoolClass] that given teacher is class teacher of (null otherwise)
         * @param userType Must be one of next: [SchoolsByUserType.TEACHER], [SchoolsByUserType.ADMINISTRATION], [SchoolsByUserType.DIRECTOR]
         */
        suspend fun getClassForTeacher(
            teacherID: Int,
            credentials: Credentials,
            userType: SchoolsByUserType = SchoolsByUserType.TEACHER
        ): Result<SchoolClass?> {
            when (userType) {
                SchoolsByUserType.PARENT, SchoolsByUserType.PUPIL -> return Result.failure(IllegalArgumentException("Only teachers can have classes"))
                else -> {}
            }
            return wrapReturn("${schoolSubdomain}${userType.name.lowercase()}/$teacherID", credentials) {
                var schoolClass: SchoolClass? = null
                htmlDocument(it.bodyAsText()) {
                    kotlin.runCatching {
                        this.div {
                            withClass = "pp_line"
                            findAll {
                                a {
                                    withAttributeKey = "href"
                                    findAll {
                                        for (a in this) {
                                            if (a.attribute("href").contains(".schools.by/class/")) {
                                                schoolClass = SchoolClass(
                                                    a.attribute("href").replaceBeforeLast('/', "").drop(1).toInt(),
                                                    teacherID,
                                                    a.ownText.replace("-го", "")
                                                )
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Result.success(schoolClass)
            }
        }

        /**
         * Returns timetable for given [teacherID].
         * !NOTE!: This method will not return other teachers in [TimetableLesson] (i.e. other subgroups teachers).
         * Please, call [CLASS.getTimetable] to get timetable with subgroups teachers
         * @param userType Must be one of next: [SchoolsByUserType.TEACHER], [SchoolsByUserType.ADMINISTRATION], [SchoolsByUserType.DIRECTOR]
         */
        suspend fun getTimetable(
            teacherID: Int,
            credentials: Credentials,
            userType: SchoolsByUserType = SchoolsByUserType.TEACHER
        ): Result<TwoShiftsTimetable> {
            when (userType) {
                SchoolsByUserType.PARENT, SchoolsByUserType.PUPIL -> return Result.failure(IllegalArgumentException("Only teachers are supported"))
                else -> {}
            }
            return wrapReturn("${schoolSubdomain}teacher/$teacherID/timetable", credentials) {
                val firstShiftTimetable = mutableMapOf<DayOfWeek, Array<TimetableLesson>>(
                    Pair(DayOfWeek.MONDAY, arrayOf()),
                    Pair(DayOfWeek.TUESDAY, arrayOf()),
                    Pair(DayOfWeek.WEDNESDAY, arrayOf()),
                    Pair(DayOfWeek.THURSDAY, arrayOf()),
                    Pair(DayOfWeek.FRIDAY, arrayOf()),
                    Pair(DayOfWeek.SATURDAY, arrayOf())
                )
                val secondShiftTimetable = mutableMapOf<DayOfWeek, Array<TimetableLesson>>(
                    Pair(DayOfWeek.MONDAY, arrayOf()),
                    Pair(DayOfWeek.TUESDAY, arrayOf()),
                    Pair(DayOfWeek.WEDNESDAY, arrayOf()),
                    Pair(DayOfWeek.THURSDAY, arrayOf()),
                    Pair(DayOfWeek.FRIDAY, arrayOf()),
                    Pair(DayOfWeek.SATURDAY, arrayOf())
                )

                fun List<DocElement>.parseTimetable(isSecondShift: Boolean = false) {
                    for (row in this) {
                        val place = row.td {
                            withClass = "num"; findFirst {
                            ownText.removeSuffix(".").toIntOrNull()
                        }
                        } ?: throw UnknownError("Lesson place detection failure: String to Int conversion failed")
                        val bells = row.td {
                            withClass = "bells"; TimeConstraints.fromString(findFirst { ownText })
                        } ?: continue
                        for ((index, column) in row.td { findAll { this.withIndex() } }) {
                            if (column.className != "" && column.className != "crossed-lesson") continue
                            if (column.ownText == "—") continue
                            column.div {
                                withClass = "lesson"
                                findAll {
                                    forEach {
                                        val (name, journalID) = try {
                                            it.a {
                                                withClass = "subject"; findFirst {
                                                Pair(
                                                    ownText, attribute("href").removePrefix("/journal/").toIntOrNull()
                                                )
                                            }
                                            }
                                        } catch (e: ElementNotFoundException) {
                                            it.b { findFirst { Pair(ownText, null) } }
                                        }
                                        val classID = it.span {
                                            withClass = "class"
                                            a { findFirst { this.attribute("href") } }.removePrefix("/class/").toInt()
                                        }
                                        if (!isSecondShift) {
                                            firstShiftTimetable[DayOfWeek.values()[index - 2]] =
                                                (firstShiftTimetable[DayOfWeek.values()[index - 2]]
                                                    ?: arrayOf()) + TimetableLesson(
                                                    place.toShort(),
                                                    bells,
                                                    unfoldLessonTitle(name),
                                                    classID,
                                                    arrayOf(teacherID),
                                                    journalID
                                                )
                                        } else {
                                            secondShiftTimetable[DayOfWeek.values()[index - 2]] =
                                                (secondShiftTimetable[DayOfWeek.values()[index - 2]]
                                                    ?: arrayOf()) + TimetableLesson(
                                                    place.toShort(),
                                                    bells,
                                                    unfoldLessonTitle(name),
                                                    classID,
                                                    arrayOf(teacherID),
                                                    journalID
                                                )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                htmlDocument(it.bodyAsText()) {
                    val availableShifts = run {
                        val result = div { withClass = "cc_timeTable"; kotlin.runCatching { findAll { this.size } } }
                        if (result.isSuccess) when (result.getOrNull()) {
                            2 -> Pair(true, true)
                            1 -> {
                                var firstShift = false
                                var secondShift = false
                                div {
                                    withClass = "tabs1_cbb"
                                    findFirst {
                                        for ((index, child) in children.withIndex()) {
                                            if (child.className == "cc_timeTable") {
                                                if (index == 1) firstShift = true
                                                else secondShift = true
                                            }
                                        }
                                    }
                                }
                                Pair(firstShift, secondShift)
                            }

                            else -> throw UnknownError("Shifts detection failure: too many tables")
                        }
                        else Pair(false, false)
                    }
                    if (availableShifts.first) { // parse first shift
                        table {
                            tbody {
                                findFirst {
                                    tr {
                                        findAll {
                                            this.parseTimetable(false)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (availableShifts.second) {
                        if (availableShifts.first) { // First shift timetable is also present
                            table {
                                findSecond {
                                    tbody {
                                        tr {
                                            findAll {
                                                this.parseTimetable(true)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            table {
                                findFirst {
                                    tbody {
                                        tr {
                                            findAll {
                                                this.parseTimetable(true)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Result.success(
                    TwoShiftsTimetable(DayOfWeek.values().filter { it != DayOfWeek.SUNDAY }.associateWith {
                        Pair(
                            firstShiftTimetable[it]!!.toList().toTypedArray(),
                            secondShiftTimetable[it]!!.toList().toTypedArray()
                        )
                    })
                )
            }
        }
    }

    /**
     * Functions related to pupils
     */
    object PUPIL {

        /**
         * Returns [SchoolClass] that given pupil is a part of. Makes two requests to get class teacher ID.
         */
        suspend fun getPupilClass(pupilID: Int, credentials: Credentials): Result<SchoolClass> {
            return wrapReturn("${schoolSubdomain}pupil/$pupilID", credentials) {
                var classID: Int = -1
                htmlDocument(it.bodyAsText()) {
                    div {
                        withClass = "pp_line"
                        findAll {
                            a {
                                withAttributeKey = "href"
                                findAll {
                                    for (a in this) {
                                        if (a.ownText.matches(Regex("^\\d{1,2}-го\\s\".\""))) {
                                            classID = a.attribute("href").replaceBeforeLast('/', "").drop(1).toInt()
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (classID == -1) return@wrapReturn Result.failure(UnknownError("Class ID was not found"))
                val schoolClass = CLASS.getClassData(classID, credentials)
                if (schoolClass.isSuccess) return@wrapReturn Result.success(schoolClass.getOrThrow())
                else return@wrapReturn Result.failure(schoolClass.exceptionOrNull()!!)
            }
        }
    }

    /**
     * Functions, related to parents
     */
    object PARENT {
        /**
         * Returns list of parent's pupils
         */
        suspend fun getPupils(parentID: Int, credentials: Credentials): Result<List<Pupil>> {
            return wrapReturn("${schoolSubdomain}parent/$parentID", credentials) {
                htmlDocument(it.bodyAsText()) {
                    val pupils = mutableListOf<Pupil>()
                    div {
                        withClass = "pp_line"
                        div {
                            withClass = "cnt"
                            table {
                                tr {
                                    findAll {
                                        for (row in this) {
                                            val (name, pupilID) = row.customTag(
                                                "",
                                                "a.user_type_1, a.user_type_2, a.user_type_3, a.user_type_4, a.user_type_5, a.user_type_6, a.user_type_7"
                                            ) {
                                                findFirst {
                                                    Pair(
                                                        Name.fromString(ownText),
                                                        attribute("href").replaceBeforeLast("/", "").removePrefix("/")
                                                    )
                                                }
                                            }
                                            val classID = row.a {
                                                findSecond {
                                                    if (className.isEmpty()) attribute("href").replaceBeforeLast(
                                                        "/", ""
                                                    ).removePrefix("/").toIntOrNull()
                                                    else null
                                                }
                                            }
                                            if (name != null && pupilID.toIntOrNull() != null && classID != null) {
                                                pupils.add(Pupil(pupilID.toInt(), name, classID))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Result.success(pupils)
                }
            }
        }
    }

    object SCHOOL {
        suspend fun getBells(): Result<Pair<List<TimetablePlace>, List<TimetablePlace>>> =
            wrapReturn("${schoolSubdomain}timetables/bells", null) { httpResponse ->
                fun CssSelector.parseTable() = this.table {
                    tbody {
                        tr {
                            findAll {
                                mapNotNull { docElement ->
                                    val place = docElement.td {
                                        withClass = "number"
                                        findFirst {
                                            ownText.removeSuffix(".").toInt()
                                        }
                                    }
                                    val constraints = kotlin.runCatching {
                                        docElement.td {
                                            withClass = "time"
                                            findFirst {
                                                ownText.lines().filterNot { it.isBlank() }
                                                    .joinToString(separator = "") {
                                                        it.trim()
                                                    }.split(" - ").let { stringList ->
                                                        val (beginHour, beginMinute) = stringList.first().split(":")
                                                            .let {
                                                                Pair(it.first().toShort(), it.last().toShort())
                                                            }
                                                        val (endHour, endMinute) = stringList.last().split(":").let {
                                                            Pair(it.first().toShort(), it.last().toShort())
                                                        }
                                                        TimeConstraints(beginHour, beginMinute, endHour, endMinute)
                                                    }
                                            }
                                        }
                                    }
                                    if (constraints.isSuccess) TimetablePlace(place, constraints.getOrThrow())
                                    else null
                                }
                            }
                        }
                    }
                }
                htmlDocument(httpResponse.bodyAsText()) {
                    val firstShiftBells = div {
                        withClass = "clmn1"
                        this.parseTable()
                    }
                    val secondShiftBells = div {
                        withClass = "clmn2"
                        this.parseTable()
                    }
                    Result.success(Pair(firstShiftBells, secondShiftBells))
                }
            }
    }
}
