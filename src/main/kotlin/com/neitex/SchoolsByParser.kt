package com.neitex


import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import it.skrape.core.htmlDocument
import it.skrape.selects.DocElement
import it.skrape.selects.ElementNotFoundException
import it.skrape.selects.html5.*
import java.net.http.HttpConnectTimeoutException
import java.time.DayOfWeek
import kotlin.collections.List
import kotlin.collections.associateWith
import kotlin.collections.filter
import kotlin.collections.find
import kotlin.collections.forEach
import kotlin.collections.forEachIndexed
import kotlin.collections.joinToString
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.plus
import kotlin.collections.set
import kotlin.collections.toList
import kotlin.collections.toSet
import kotlin.collections.toTypedArray
import kotlin.collections.withIndex

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
        fun closeHttpClient() {
            client.close()
        }

        /**
         * Sets school's subdomain.
         *
         * Example: https://demo.schools.by/ (notice trailing slash)
         */
        fun setSubdomain(url: String) {
            schoolSubdomain = url
        }

        /**
         * Sets custom Http client for library to use
         */
        fun setHttpClient(client: HttpClient) {
            this.client = client
        }

        internal suspend fun <T> wrapReturn(
            requestUrl: String,
            credentials: Credentials,
            returnValue: suspend (HttpResponse) -> Result<T>
        ): Result<T> {
            return try {
                val response = client.get {
                    url(requestUrl)
                    cookie("csrftoken", credentials.csrfToken)
                    cookie("sessionid", credentials.sessionID)
                }
                if (!response.checkCredentialsClear() || response.request.url.toString().contains("already-redirected"))
                    return Result.failure(BadSchoolsByCredentials())
                if (response.status == HttpStatusCode.NotFound)
                    return Result.failure(PageNotFound("Page \'$requestUrl\' was not found"))
                returnValue(response)
            } catch (e: HttpRequestTimeoutException) {
                Result.failure(SchoolsByUnavailable("Schools.by did not respond", e))
            } catch (e: HttpConnectTimeoutException) {
                Result.failure(SchoolsByUnavailable("Schools.by did not respond", e))
            } catch (e: ElementNotFoundException) {
                Result.failure(UnknownError("Page parsing failed: Exception: \'${e.message}\'; Stack trace: ${e.stackTraceToString()}"))
            } catch (e: UnknownError) {
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
            try {
                val firstCSRFtoken = run {
                    val response = client.get("https://schools.by/login")
                    response.setCookie().find { it.name == "csrftoken" }
                } ?: return Result.failure(UnknownError("First stage of login failed: csrftoken cookie was not found"))
                val (secondCSRFtoken, sessionid) = run {
                    val response = client.submitForm(
                        url = "https://schools.by/login",
                        formParameters = Parameters.build {
                            append("csrfmiddlewaretoken", firstCSRFtoken.value)
                            append("username", username)
                            append("password", password)
                        }) {
                        cookie("csrftoken", firstCSRFtoken.value)
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
            if (!response.checkCredentialsClear())
                return Result.failure(BadSchoolsByCredentials())
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
                        response.request.url.encodedPath.replace("director", "administration").replaceAfterLast('/', "")
                            .removeSuffix("/")
                            .replaceBefore('/', "").removePrefix("/").uppercase()
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
                        withClass = "grid_st_r"
                        div {
                            withClass = "r_user_info"
                            p {
                                withClass = "name"
                                a {
                                    withClass = "user_type_3"
                                    findFirst {
                                        attribute("href").removePrefix("${schoolSubdomain}teacher/").toIntOrNull()
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
            return wrapReturn("${schoolSubdomain}class/$classID/pupils", credentials) { response ->
                val pupilsList = mutableListOf<Pupil>()
                htmlDocument(response.bodyAsText()) {
                    div {
                        withClass = "pupil"
                        findAll {
                            a {
                                withClass = "user_type_1"
                                findAll {
                                    forEach {
                                        pupilsList.add(
                                            Pupil(
                                                it.attribute("href").removePrefix("/pupil/").toInt(),
                                                Name.fromString(it.ownText)
                                                    ?: throw UnknownError("Name detection failed: invalid value \'${it.ownText}\'"),
                                                classID
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Result.success(pupilsList)
            }
        }

        /**
         * Returns [Timetable] of class with [classID]
         * @param guessShift Defines, should library make a guess on shift of this class (default: false).
         * @return [Pair], where first element defines, whether this class is second shift or not
         * (null if [guessShift] is false), and second element is timetable
         */
        suspend fun getTimetable(
            classID: Int,
            credentials: Credentials,
            guessShift: Boolean = false
        ): Result<Pair<Boolean?, Timetable>> {
            return wrapReturn("${schoolSubdomain}class/$classID/timetable", credentials) {
                val timetableMap = mutableMapOf<DayOfWeek, Array<Lesson>>()
                htmlDocument(it.bodyAsText()) {
                    div {
                        withClass = "ttb_boxes"
                        div {
                            withClass = "ttb_box"
                            findAll {
                                forEachIndexed { _, mainDiv ->
                                    val day =
                                        russianDayNameToDayOfWeek(mainDiv.div {
                                            withClass = "ttb_day"; findFirst { ownText }
                                        })
                                    var dayTimetable = arrayOf<Lesson>()
                                    mainDiv.tbody {
                                        tr {
                                            findAll {
                                                forEachIndexed { _, doc ->
                                                    var add = true
                                                    val num =
                                                        doc.td { withClass = "num"; findFirst { ownText } }.dropLast(1)
                                                            .toInt()
                                                    val time = TimeConstraints.fromString(
                                                        doc.td { withClass = "time"; findFirst { ownText } })
                                                        ?: throw UnknownError("Failed detecting lesson time; Current element: \'$doc\'")
                                                    val name = doc.td {
                                                        val titles = mutableListOf<String>()
                                                        withClass = "subjs"; findFirst {
                                                        try {
                                                            a {
                                                                findAll {
                                                                    forEach { doc ->
                                                                        if (!titles.contains(doc.attribute("title"))
                                                                            && doc.attribute("title").isNotEmpty()
                                                                        )
                                                                            titles.add(doc.attribute("title"))
                                                                    }
                                                                }
                                                            }
                                                        } catch (e: ElementNotFoundException) {
                                                            try {
                                                                span {
                                                                    findAll {
                                                                        forEach { doc ->
                                                                            if (!titles.contains(doc.attribute("title"))
                                                                                && doc.attribute("title").isNotEmpty()
                                                                            )
                                                                                titles.add(doc.attribute("title"))
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
                                                    if (add)
                                                        dayTimetable += Lesson(
                                                            num.toShort(),
                                                            time,
                                                            name,
                                                            classID,
                                                            null,
                                                            journalID
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
                    return@htmlDocument Result.success(
                        Pair(
                            if (guessShift) {
                                (timetableMap.filter { it.key != DayOfWeek.SATURDAY && it.value.find { it.place == 1.toShort() } != null }.values.find { it.find { it.place == 1.toShort() } != null }
                                    ?.find { it.place == 1.toShort() }?.timeConstraints?.startHour)?.compareTo(12).let {
                                        if (it != null)
                                            it >= 12
                                        else null
                                    } ?: false
                            } else null, Timetable(timetableMap)
                        )
                    )
                }
            }
        }
    }

    /**
     * Functions related to Teachers
     */
    object TEACHER {

        /**
         * Returns [SchoolClass] that given teacher is class teacher of (null otherwise)
         */
        suspend fun getClassForTeacher(teacherID: Int, credentials: Credentials): Result<SchoolClass?> {
            return wrapReturn("${schoolSubdomain}teacher/$teacherID", credentials) {
                var schoolClass: SchoolClass? = null
                htmlDocument(it.bodyAsText()) {
                    div {
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
                Result.success(schoolClass)
            }
        }

        suspend fun getTimetable(teacherID: Int, credentials: Credentials): Result<TwoShiftsTimetable> {
            return wrapReturn("${schoolSubdomain}teacher/$teacherID/timetable", credentials) {
                val firstShiftTimetable = mutableMapOf<DayOfWeek, Array<Lesson>>(
                    Pair(DayOfWeek.MONDAY, arrayOf()),
                    Pair(DayOfWeek.TUESDAY, arrayOf()),
                    Pair(DayOfWeek.WEDNESDAY, arrayOf()),
                    Pair(DayOfWeek.THURSDAY, arrayOf()),
                    Pair(DayOfWeek.FRIDAY, arrayOf()),
                    Pair(DayOfWeek.SATURDAY, arrayOf())
                )
                val secondShiftTimetable = mutableMapOf<DayOfWeek, Array<Lesson>>(
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
                        }
                            ?: throw UnknownError("Lesson place detection failure: String to Int conversion failed")
                        val bells = row.td {
                            withClass = "bells"; TimeConstraints.fromString(
                            findFirst { ownText }
                        )
                        }
                            ?: continue
                        for ((index, column) in row.td { findAll { this.withIndex() } }) {
                            if (column.className != "" && column.className != "crossed-lesson")
                                continue
                            if (column.ownText == "—")
                                continue
                            column.div {
                                withClass = "lesson"
                                findAll {
                                    forEach {
                                        val (name, journalID) = try {
                                            it.a {
                                                withClass = "subject"; findFirst {
                                                Pair(
                                                    ownText,
                                                    attribute("href").removePrefix("/journal/").toIntOrNull()
                                                )
                                            }
                                            }
                                        } catch (e: ElementNotFoundException) {
                                            it.b { findFirst { Pair(ownText, null) } }
                                        }
                                        val classID =
                                            it.span {
                                                withClass = "class"
                                                a { findFirst { this.attribute("href") } }.removePrefix("/class/")
                                                    .toInt()
                                            }

                                        if (!isSecondShift) {
                                            firstShiftTimetable[DayOfWeek.values()[index - 2]] =
                                                (firstShiftTimetable[DayOfWeek.values()[index - 2]]
                                                    ?: arrayOf()).let {
                                                    it + Lesson(
                                                        place.toShort(),
                                                        bells,
                                                        unfoldLessonTitle(name),
                                                        classID,
                                                        teacherID,
                                                        journalID
                                                    )
                                                }
                                        } else {
                                            secondShiftTimetable[DayOfWeek.values()[index - 2]] =
                                                (secondShiftTimetable[DayOfWeek.values()[index - 2]]
                                                    ?: arrayOf()).let {
                                                    it + Lesson(
                                                        place.toShort(),
                                                        bells,
                                                        unfoldLessonTitle(name),
                                                        classID,
                                                        teacherID,
                                                        journalID
                                                    )
                                                }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                htmlDocument(it.bodyAsText()) {
                    val availableShifts = run {
                        when (div { withClass = "cc_timeTable"; findAll { this.size } }) {
                            2 -> Pair(true, true)
                            1 -> {
                                var firstShift = false
                                var secondShift = false
                                div {
                                    withClass = "tabs1_cbb"
                                    findFirst {
                                        for ((index, child) in children.withIndex()) {
                                            if (child.className == "cc_timeTable") {
                                                if (index == 1)
                                                    firstShift = true
                                                else secondShift = true
                                            }
                                        }
                                    }
                                }
                                Pair(firstShift, secondShift)
                            }
                            else -> throw UnknownError("Shifts detection failure: too many tables")
                        }
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
                Result.success(TwoShiftsTimetable(
                    DayOfWeek.values().filter { it != DayOfWeek.SUNDAY }
                        .associateWith {
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
                if (classID == -1)
                    return@wrapReturn Result.failure(UnknownError("Class ID was not found"))
                val schoolClass = CLASS.getClassData(classID, credentials)
                if (schoolClass.isSuccess)
                    return@wrapReturn Result.success(schoolClass.getOrThrow())
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
                                            val (name, pupilID) = row.a {
                                                withClass = "user_type_1"
                                                findFirst {
                                                    Pair(
                                                        Name.fromString(ownText),
                                                        attribute("href").replaceBeforeLast("/", "").removePrefix("/")
                                                    )
                                                }
                                            }
                                            val classID = row.a {
                                                findSecond {
                                                    if (className.isEmpty())
                                                        attribute("href").replaceBeforeLast("/", "").removePrefix("/")
                                                            .toIntOrNull()
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
}
