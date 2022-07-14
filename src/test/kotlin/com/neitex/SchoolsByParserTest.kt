package com.neitex

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariables
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.LocalDate
import java.time.Month
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue

@DisplayName("Schools.by parser tests")
internal class SchoolsByParserTest {
    private val validParentCredentials =
        Credentials(csrfToken = System.getenv("csrftoken"), sessionID = System.getenv("sessionid"))
    private val validTeacherCredentials =
        Credentials(csrfToken = System.getenv("teacher_csrftoken"), sessionID = System.getenv("teacher_sessionid"))
    private val invalidCredentials = Credentials(csrfToken = "123", sessionID = "123")

    val testConstraintsList = listOf(
        TimeConstraints(0, 0, 0, 0),
        TimeConstraints(8, 30, 9, 15),
        TimeConstraints(9, 25, 10, 10),
        TimeConstraints(10, 20, 11, 5),
        TimeConstraints(11, 25, 12, 10),
        TimeConstraints(12, 25, 13, 10),
        TimeConstraints(13, 20, 14, 5),
        TimeConstraints(14, 15, 15, 0),
    )

    @BeforeTest
    fun setUp() {
        SchoolsByParser.setSubdomain("https://demo.schools.by/")
    }

    @Nested
    @DisabledIfEnvironmentVariables(
        DisabledIfEnvironmentVariable(
            named = "GITHUB_ACTIONS",
            matches = "true",
            disabledReason = "Schools.by authentication service is unavailable outside of Belarus"
        ), DisabledIfEnvironmentVariable(
            named = "OUTSIDE_OF_BELARUS",
            matches = "true",
            disabledReason = "Schools.by authentication service is unavailable outside of Belarus"
        )
    )
    @DisplayName("Authentication tests")
    inner class AuthTests {

        // Unfortunately, tests for logging in with valid credentials are not possible, because it would be Schools.by TOS breaking

        @Test
        @DisplayName("Logging in with invalid credentials")
        fun testLoggingInWithoutValidCredentials() = runBlocking {
            val result = SchoolsByParser.AUTH.getLoginCookies(username = "", password = "")
            assertAll({ assert(result.isFailure) }, { assert(result.exceptionOrNull() is AuthorizationUnsuccessful) })
        }

        @Test
        @DisplayName("Check invalid cookies")
        fun testCookiesCheckWithoutValidCookies() = runBlocking {
            val result = SchoolsByParser.AUTH.checkCookies(Credentials(csrfToken = "123", sessionID = "123"))
            assert(result.isSuccess)
            assert(result.getOrNull() == false)
        }

        @Test
        @DisplayName("Check valid cookies")
        fun testCookiesCheckWithValidCookies() = runBlocking {
            val result = SchoolsByParser.AUTH.checkCookies(
                validParentCredentials
            ) // Credentials from Schools.by demo version
            assert(result.isSuccess)
            assert(result.getOrNull() == true)
        }
    }

    @Nested
    @DisplayName("User data tests")
    inner class SchoolsByUserDataTest {
        @Test
        @DisabledIfEnvironmentVariables(
            DisabledIfEnvironmentVariable(
                named = "GITHUB_ACTIONS",
                matches = "true",
                disabledReason = "Schools.by authentication service is unavailable outside of Belarus"
            ), DisabledIfEnvironmentVariable(
                named = "OUTSIDE_OF_BELARUS",
                matches = "true",
                disabledReason = "Schools.by authentication service is unavailable outside of Belarus"
            )
        )
        @DisplayName("Get user ID from valid credentials")
        fun testGettingUserIDFromCredentials() = runBlocking {
            val result = SchoolsByParser.USER.getUserIDFromCredentials(
                validParentCredentials
            ) // Credentials from Schools.by demo version
            assert(result.isSuccess)
            assert(result.getOrThrow() == 105190 || result.getOrThrow() == 104631)
        }

        @Test
        @DisplayName("Get user ID from invalid credentials")
        fun testGettingUserIDFromInvalidCredentials() = runBlocking {
            val result =
                SchoolsByParser.USER.getUserIDFromCredentials(Credentials(csrfToken = "123", sessionID = "123"))
            assert(result.isFailure)
        }

        @Test
        @DisplayName("Get user data using valid user ID and credentials")
        fun testGettingUserDataUsingCredentialsAndUserID() = runBlocking {
            val result = SchoolsByParser.USER.getBasicUserInfo(
                userID = 105190, validParentCredentials
            ) // Credentials from Schools.by demo version
            assert(result.isSuccess)
            assert(
                result.getOrThrow() == User(
                    105190, SchoolsByUserType.PARENT, Name("Тамара", "Николаевна", "Соловьева")
                )
            ) {
                "$result"
            }
        }

        @Test
        @DisplayName("Get user data using invalid credentials and valid user ID")
        fun testGettingUserDataUsingInvalidCredentials() = runBlocking {
            val result = SchoolsByParser.USER.getBasicUserInfo(
                userID = 105190, invalidCredentials
            )
            assert(result.isFailure)
        }

        @Test
        @DisplayName("Get user data using valid credentials and invalid user ID")
        fun testGettingUserDataUsingNonExistentID() = runBlocking {
            val result = SchoolsByParser.USER.getBasicUserInfo(
                userID = 0, validParentCredentials
            ) // Credentials from Schools.by demo version
            assert(result.isFailure)
        }

        @Test
        @DisplayName("Get user data using invalid credentials and invalid user ID")
        fun testGettingUserDataUsingNonExistentIDAndInvalidCredentials() = runBlocking {
            val result = SchoolsByParser.USER.getBasicUserInfo(
                userID = 0, validParentCredentials
            ) // Credentials from Schools.by demo version
            assert(result.isFailure)
        }
    }

    @Nested
    @DisplayName("Class data tests")
    inner class ClassesDataTests {
        @Test
        @DisplayName("Get class data using valid class ID and valid credentials")
        fun testGettingClassDataValidClassIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.CLASS.getClassData(classID = 8, validTeacherCredentials)
            assertTrue(result.isSuccess)
            assertEquals(SchoolClass(8, 108105, "11 \"А\""), result.getOrThrow())
        }

        @Test
        @DisplayName("Get class data using valid class ID and invalid credentials")
        fun testGettingClassDataValidClassIDInvalidCredentials() = runBlocking {
            val result = SchoolsByParser.CLASS.getClassData(classID = 8, invalidCredentials)
            assertFalse(result.isSuccess)
        }

        @Test
        @DisplayName("Get class data using invalid class ID and invalid credentials")
        fun testGettingClassDataInvalidClassIDInvalidCredentials() = runBlocking {
            val result = SchoolsByParser.CLASS.getClassData(classID = (-1), invalidCredentials)
            assertFalse(result.isSuccess)
            assert(result.exceptionOrNull() is BadSchoolsByCredentials)
        }

        @Test
        @DisplayName("Get list of pupils using valid class ID and valid credentials")
        fun testGettingPupilsListValidClassIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.CLASS.getPupilsList(classID = 8, validTeacherCredentials)
            assertAll({ assert(result.isSuccess) }, {
                assertContentEquals(
                    listOf(
                        Pupil(100135, Name("Дмитрий", "Анатольевич", "Ильин"), 8),
                        Pupil(100119, Name("Елена", "Евгеньевна", "Макеева"), 8),
                        Pupil(100139, Name("Борис", "Глебович.", "Пономарёв"), 8),
                        Pupil(100142, Name("Мария", "Викторовна", "Самсонова"), 8),
                        Pupil(100140, Name("Алеся", "Андреевна", "Соловьева"), 8),
                        Pupil(100143, Name("Денис", "Семёнович", "Шилин"), 8),
                        Pupil(100121, Name("Валерий", "Владимирович", "Юрченко"), 8),
                        Pupil(100131, Name("Сергей", "Александрович", "Якушев"), 8)
                    ), result.getOrThrow()
                )
            })
        }

        @Test
        @DisplayName("Get list of pupils using invalid class ID and valid credentials")
        fun testGettingPupilsListInvalidClassIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.CLASS.getPupilsList(classID = (-1), validParentCredentials)
            assert(result.isFailure)
            assert(result.exceptionOrNull() is PageNotFound)
        }

        @Test
        @DisplayName("Get list of pupils using invalid class ID and valid credentials")
        fun testGettingPupilsListInvalidClassIDInvalidCredentials() = runBlocking {
            val result = SchoolsByParser.CLASS.getPupilsList(classID = (-1), invalidCredentials)
            assert(result.isFailure)
            assert(result.exceptionOrNull() is BadSchoolsByCredentials)
        }

        @Test
        @DisplayName("Get list of pupils using invalid class ID and valid credentials")
        fun testGettingPupilsListValidClassIDInvalidCredentials() = runBlocking {
            val result = SchoolsByParser.CLASS.getPupilsList(classID = 8, invalidCredentials)
            assert(result.isFailure)
            assert(result.exceptionOrNull() is BadSchoolsByCredentials)
        }

        @Test
        @DisplayName("Get class timetable using valid class ID and valid credentials without walking to journal")
        fun testGettingTimetableValidClassIDValidCredentials() = runBlocking {
            val result =
                SchoolsByParser.CLASS.getTimetable(classID = 8, validTeacherCredentials, walkToJournals = false)
            assert(result.isSuccess)
            val timetable = result.getOrThrow()
            assertAll({
                assertNotNull(result.getOrThrow())
            }, {
                assertContentEquals(
                    arrayOf(
                        TimetableLesson(1, testConstraintsList[1], "Белорусская литература", 8, null, 377660),
                        TimetableLesson(2, testConstraintsList[2], "История Беларуси", 8, null, 377654),
                        TimetableLesson(3, testConstraintsList[3], "Белорусский язык", 8, null, 377659),
                        TimetableLesson(4, testConstraintsList[4], "Математика", 8, null, 100131),
                        TimetableLesson(5, testConstraintsList[5], "Английский язык", 8, null, 100121),
                        TimetableLesson(6, testConstraintsList[6], "Информатика", 8, null, 100129),
                    ), timetable.monday
                )
            }, {
                assertContentEquals(
                    arrayOf(
                        TimetableLesson(1, testConstraintsList[1], "Русская литература", 8, null, 100135),
                        TimetableLesson(2, testConstraintsList[2], "Русский язык", 8, null, 100136),
                        TimetableLesson(3, testConstraintsList[3], "Обществоведение", 8, null, 377651),
                        TimetableLesson(4, testConstraintsList[4], "Физика", 8, null, 100138),
                        TimetableLesson(
                            5, testConstraintsList[5], "Физическая культура и здоровье", 8, null, 100139
                        )
                    ), timetable.tuesday
                )
            }, {
                assertContentEquals(
                    arrayOf(
                        TimetableLesson(2, testConstraintsList[2], "Всемирная История", 8, null, 100126),
                        TimetableLesson(3, testConstraintsList[3], "География", 8, null, 100127),
                        TimetableLesson(4, testConstraintsList[4], "Биология", 8, null, 100125),
                        TimetableLesson(5, testConstraintsList[5], "Английский язык", 8, null, 100121)
                    ), timetable.wednesday
                )
            }, {
                assertContentEquals(
                    arrayOf(
                        TimetableLesson(3, testConstraintsList[3], "Математика", 8, null, 100131),
                        TimetableLesson(4, testConstraintsList[4], "Химия", 8, null, 100140),
                        TimetableLesson(5, testConstraintsList[5], "Биология", 8, null, 100125),
                        TimetableLesson(6, testConstraintsList[6], "Информатика", 8, null, 100129),
                        TimetableLesson(
                            7, testConstraintsList[7], "Физическая культура и здоровье", 8, null, 100139
                        )
                    ), timetable.thursday
                )
            }, {
                assertContentEquals(
                    arrayOf(
                        TimetableLesson(2, testConstraintsList[2], "Математика", 8, null, 100131),
                        TimetableLesson(3, testConstraintsList[3], "Химия", 8, null, 100140),
                        TimetableLesson(4, testConstraintsList[4], "Английский язык", 8, null, 100121)
                    ), timetable.friday
                )
            }, {
                assertContentEquals(arrayOf(), timetable.saturday)
            })
        }

        @Test
        @DisplayName("Get class timetable using valid class ID and valid credentials with walking to journal")
        fun testGettingTimetableValidClassIDValidCredentialsWithWalking() = runBlocking {
            val result = SchoolsByParser.CLASS.getTimetable(classID = 8, validTeacherCredentials, walkToJournals = true)
            assert(result.isSuccess)
            val timetable = result.getOrThrow()
            assertAll({
                assertNotNull(result.getOrThrow())
            }, {
                assertContentEquals(
                    arrayOf(
                        TimetableLesson(
                            1, testConstraintsList[1], "Белорусская литература", 8, arrayOf(108105), 377660
                        ),
                        TimetableLesson(2, testConstraintsList[2], "История Беларуси", 8, arrayOf(109035), 377654),
                        TimetableLesson(3, testConstraintsList[3], "Белорусский язык", 8, arrayOf(108105), 377659),
                        TimetableLesson(4, testConstraintsList[4], "Математика", 8, arrayOf(108728), 100131),
                        TimetableLesson(
                            5, testConstraintsList[5], "Английский язык", 8, arrayOf(151701, 108508), 100121
                        ),
                        TimetableLesson(
                            6, testConstraintsList[6], "Информатика", 8, arrayOf(108728, 109035), 100129
                        ),
                    ), timetable.monday
                )
            }, {
                assertContentEquals(
                    arrayOf(
                        TimetableLesson(
                            1, testConstraintsList[1], "Русская литература", 8, arrayOf(108728), 100135
                        ),
                        TimetableLesson(2, testConstraintsList[2], "Русский язык", 8, arrayOf(108728), 100136),
                        TimetableLesson(3, testConstraintsList[3], "Обществоведение", 8, arrayOf(108301), 377651),
                        TimetableLesson(4, testConstraintsList[4], "Физика", 8, arrayOf(104407), 100138),
                        TimetableLesson(
                            5, testConstraintsList[5], "Физическая культура и здоровье", 8, arrayOf(109035), 100139
                        )
                    ), timetable.tuesday
                )
            }, {
                assertContentEquals(
                    arrayOf(
                        TimetableLesson(2, testConstraintsList[2], "Всемирная История", 8, arrayOf(109035), 100126),
                        TimetableLesson(3, testConstraintsList[3], "География", 8, arrayOf(104437), 100127),
                        TimetableLesson(4, testConstraintsList[4], "Биология", 8, arrayOf(104631), 100125),
                        TimetableLesson(
                            5, testConstraintsList[5], "Английский язык", 8, arrayOf(151701, 108508), 100121
                        )
                    ), timetable.wednesday
                )
            }, {
                assertContentEquals(
                    arrayOf(
                        TimetableLesson(3, testConstraintsList[3], "Математика", 8, arrayOf(108728), 100131),
                        TimetableLesson(4, testConstraintsList[4], "Химия", 8, arrayOf(109035), 100140),
                        TimetableLesson(5, testConstraintsList[5], "Биология", 8, arrayOf(104631), 100125),
                        TimetableLesson(
                            6, testConstraintsList[6], "Информатика", 8, arrayOf(108728, 109035), 100129
                        ),
                        TimetableLesson(
                            7, testConstraintsList[7], "Физическая культура и здоровье", 8, arrayOf(109035), 100139
                        )
                    ), timetable.thursday
                )
            }, {
                assertContentEquals(
                    arrayOf(
                        TimetableLesson(2, testConstraintsList[2], "Математика", 8, arrayOf(108728), 100131),
                        TimetableLesson(3, testConstraintsList[3], "Химия", 8, arrayOf(109035), 100140),
                        TimetableLesson(
                            4, testConstraintsList[4], "Английский язык", 8, arrayOf(151701, 108508), 100121
                        )
                    ), timetable.friday
                )
            }, {
                assertContentEquals(arrayOf(), timetable.saturday)
            })
        }

        @Test
        @DisplayName("Get class timetable using valid class ID and invalid credentials")
        fun testTimetableValidClassIDInvalidCredentials() = runBlocking {
            val result = SchoolsByParser.CLASS.getTimetable(classID = 8, invalidCredentials)
            assert(result.isFailure)
            assert(result.exceptionOrNull() is BadSchoolsByCredentials)
        }

        @Test
        @DisplayName("Get class timetable using invalid class ID and valid credentials")
        fun testTimetableInvalidClassIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.CLASS.getTimetable(classID = (-1), validParentCredentials)
            assert(result.isFailure)
            assert(result.exceptionOrNull() is PageNotFound)
        }

        @Test
        @DisplayName("Get class shift")
        fun testShiftGetting() = runBlocking {
            val result = SchoolsByParser.CLASS.getClassShift(classID = 8, validTeacherCredentials)
            assert(result.isSuccess)
            assert(!result.getOrThrow())
        }

        @Test
        @DisplayName("Get pupils ordering")
        fun testPupilsOrdering() = runBlocking {
            val tests = SchoolsByParser.CLASS.getPupilsOrdering(classID = 6, validTeacherCredentials)
            assert(tests.isSuccess)
            assertContentEquals(
                arrayOf(
                    Pair(67, 0.toShort()),
                    Pair(495803, 0.toShort()),
                    Pair(70, 0.toShort()),
                    Pair(100132, 0.toShort()),
                    Pair(61, 0.toShort()),
                    Pair(69, 0.toShort()),
                    Pair(65, 0.toShort()),
                    Pair(66, 0.toShort()),
                    Pair(62, 0.toShort()),
                    Pair(100148, 10.toShort()),
                    Pair(63, 11.toShort())
                ), tests.getOrThrow()
            )
        }

        @Test
        @DisplayName("Get pupils movements between classes")
        fun testMoveDates() = runBlocking {
            val result = SchoolsByParser.CLASS.getTransfers(classID = 6, validTeacherCredentials)
            assert(result.isSuccess)
            assert(result.getOrThrow().isEmpty())
        }

        @Test
        @EnabledIfEnvironmentVariable(
            named = "customSchoolsTest",
            matches = "true",
            disabledReason = "This test is only enabled if custom test data is provided"
        )
        @DisplayName("Get pupils movements between classes (custom)")
        fun testMoveDatesCustom() = runBlocking {
            run { // Customize library for this test
                SchoolsByParser.setSubdomain(System.getenv("customSchoolDomain"))
            }
            val result = SchoolsByParser.CLASS.getTransfers(
                System.getenv("customClassID").toInt(), Credentials(
                    System.getenv("customCsrfToken"), System.getenv("customSessionID")
                )
            )
            assert(result.isSuccess)
            assertContentEquals(
                listOf(
                    Pair(Pair(null, 26218), LocalDate.of(2019, Month.AUGUST, 1)),
                    Pair(Pair(26218, 26215), LocalDate.of(2021, Month.SEPTEMBER, 1))
                ), result.getOrThrow()[1896875]!!
            )

            SchoolsByParser.setSubdomain("https://demo.schools.by/")
        }

        @Test
        @DisplayName("Get subgroups")
        fun testSubgroups() = runBlocking {
            val result = SchoolsByParser.CLASS.getSubgroups(8, validTeacherCredentials)
            assert(result.isSuccess)
            val subgroups = result.getOrThrow()
            assertEquals(Subgroup(3, "мальчики", listOf(100135, 100139, 100143, 100121, 100131)), subgroups[0])
        }

        val subgroups by lazy {
            runBlocking {
                SchoolsByParser.CLASS.getSubgroups(8, validTeacherCredentials)
            }.getOrThrow()
        }

        @Test
        @DisplayName("Get lessons by journal ID")
        fun testGetLessonsJournalID() = runBlocking {
            val result = SchoolsByParser.CLASS.getLessonsListByJournal(
                8,
                100127,
                subgroups.associate { it.subgroupID to it.title },
                validTeacherCredentials
            )
            println(result)
            assert(result.isSuccess)
            assertContentEquals(
                listOf(
                    Lesson(
                        81645560,
                        100127,
                        setOf(104437),
                        null,
                        "География",
                        LocalDate.of(2021, Month.SEPTEMBER, 1),
                        3
                    ),
                    Lesson(
                        81645593,
                        100127,
                        setOf(104437),
                        null,
                        "География",
                        LocalDate.of(2021, Month.SEPTEMBER, 8),
                        3
                    ),
                    Lesson(
                        81645626,
                        100127,
                        setOf(104437),
                        null,
                        "География",
                        LocalDate.of(2021, Month.SEPTEMBER, 15),
                        3
                    ),
                    Lesson(
                        81645659,
                        100127,
                        setOf(104437),
                        null,
                        "География",
                        LocalDate.of(2021, Month.SEPTEMBER, 22),
                        3
                    ),
                    Lesson(
                        81645692,
                        100127,
                        setOf(104437),
                        null,
                        "География",
                        LocalDate.of(2021, Month.SEPTEMBER, 29),
                        3
                    ),
                    Lesson(
                        81645725,
                        100127,
                        setOf(104437),
                        null,
                        "География",
                        LocalDate.of(2021, Month.OCTOBER, 6),
                        3
                    ),
                    Lesson(
                        81645758,
                        100127,
                        setOf(104437),
                        null,
                        "География",
                        LocalDate.of(2021, Month.OCTOBER, 13),
                        3
                    ),
                    Lesson(
                        81645791,
                        100127,
                        setOf(104437),
                        null,
                        "География",
                        LocalDate.of(2021, Month.OCTOBER, 20),
                        3
                    ),
                    Lesson(
                        81645824,
                        100127,
                        setOf(104437),
                        null,
                        "География",
                        LocalDate.of(2021, Month.OCTOBER, 27),
                        3
                    ),
                ), result.getOrThrow().take(9)
            )
        }

        @OptIn(ExperimentalTime::class)
        @Test
        @DisplayName("Get all lessons for class (no assertions, only time test)")
        fun timeGetAllLessons() = runBlocking {
            val result = measureTimedValue {
                SchoolsByParser.CLASS.getAllLessons(
                    8,
                    subgroups.associate { it.subgroupID to it.title },
                    validTeacherCredentials
                )
            }
            println(result)
            assert(result.duration.inWholeMinutes < 10)
            assert(result.value.isSuccess)
            assert(result.value.getOrThrow().isNotEmpty())
            println("Found ${result.value.getOrThrow().size} lessons")
        }
    }

    @Nested
    @DisplayName("Teacher data tests")
    inner class TeacherDataTests {
        @Test
        @DisplayName("Get teacher's class using valid class teacher ID and valid credentials")
        fun testClassTeacherClassValidTeacherIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.TEACHER.getClassForTeacher(108105, validTeacherCredentials)
            assertAll({
                assertTrue(result.isSuccess)
            }, {
                assertNotNull(result.getOrThrow())
                assert(result.getOrThrow()!!.id == 8)
                assert(result.getOrThrow()!!.classTitle == "11 \"А\"")
            })
        }

        @Test
        @DisplayName("Get teacher's class using valid teacher ID with no class and valid credentials")
        fun testClassTeacherClassValidTeacherIDWithNoClassValidCredentials() = runBlocking {
            val result = SchoolsByParser.TEACHER.getClassForTeacher(109035, validParentCredentials)
            assertAll({
                assertTrue(result.isSuccess)
            }, {
                assertNull(result.getOrThrow())
            })
        }

        @Test
        @DisplayName("Get teacher's class using invalid teacher ID and valid credentials")
        fun testClassTeacherClassInvalidTeacherIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.TEACHER.getClassForTeacher(-1, validParentCredentials)
            assertAll({
                assertTrue(result.isFailure)
            }, {
                assert(result.exceptionOrNull() is PageNotFound)
            })
        }

        @Test
        @DisplayName("Get teacher's class using invalid teacher ID and invalid credentials")
        fun testClassTeacherClassInvalidTeacherIDInvalidCredentials() = runBlocking {
            val result = SchoolsByParser.TEACHER.getClassForTeacher(-1, invalidCredentials)
            assertAll({
                assertTrue(result.isFailure)
            }, {
                assert(result.exceptionOrNull() is BadSchoolsByCredentials)
            })
        }

        @Test
        @DisplayName("Get teacher's class using pupil ID and valid credentials")
        fun testClassTeacherClassPupilIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.TEACHER.getClassForTeacher(100135, validParentCredentials)
            assertAll({
                assertTrue(result.isFailure)
            }, {
                assert(result.exceptionOrNull() is PageNotFound)
            })
        }

        @Test
        @DisplayName("Get teacher's timetable using valid teacher ID and valid credentials")
        fun testTeacherTimetableValidTeacherIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.TEACHER.getTimetable(108105, validTeacherCredentials)
            val unpackedResult = result.getOrThrow()
            assertAll({
                assertTrue(result.isSuccess)
                assertNotNull(result.getOrNull())
            }, {
                assertContentEquals(
                    arrayOf(
                        TimetableLesson(
                            1, testConstraintsList[1], "Белорусская литература", 8, arrayOf(108105), 377660
                        ),
                        TimetableLesson(3, testConstraintsList[3], "Английский язык", 6, arrayOf(108105), 74),
                        TimetableLesson(3, testConstraintsList[3], "Белорусский язык", 8, arrayOf(108105), 377659),
                    ), unpackedResult.monday.first
                )
                assert(unpackedResult.monday.second.isEmpty())
            }, {
                assertContentEquals(
                    arrayOf(
                        TimetableLesson(4, testConstraintsList[4], "Английский язык", 6, arrayOf(108105), 74)
                    ), unpackedResult.tuesday.first
                )
                assert(unpackedResult.tuesday.second.isEmpty())
            }, {
                assert(unpackedResult.wednesday.second.isEmpty())
                assert(unpackedResult.wednesday.second.isEmpty())
            }, {
                assert(unpackedResult.thursday.second.isEmpty())
                assert(unpackedResult.thursday.second.isEmpty())
            }, {
                assert(unpackedResult.friday.second.isEmpty())
                assert(unpackedResult.friday.second.isEmpty())
            }, {
                assert(unpackedResult.saturday.second.isEmpty())
                assert(unpackedResult.saturday.second.isEmpty())
            })
        }

        @Test
        @DisplayName("Get timetable with links instead of b with valid teacher ID and valid credentials")
        fun testTimetableLinksValidTeacherIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.TEACHER.getTimetable(104631, validParentCredentials)
            assertAll({
                assert(result.isSuccess)
            }, {
                val timetable = result.getOrThrow()
                assertContentEquals(
                    arrayOf(
                        TimetableLesson(4, testConstraintsList[4], "Биология", 8, arrayOf(104631), null)
                    ), timetable.wednesday.first
                )
                assertContentEquals(
                    arrayOf(
                        TimetableLesson(5, testConstraintsList[5], "Биология", 8, arrayOf(104631), null)
                    ), timetable.thursday.first
                )
                assert(
                    timetable.monday.second.isEmpty()
                )
                assert(
                    timetable.tuesday.second.isEmpty()
                )
                assert(
                    timetable.wednesday.second.isEmpty()
                )
                assert(
                    timetable.thursday.second.isEmpty()
                )
                assert(
                    timetable.friday.second.isEmpty()
                )
                assert(
                    timetable.saturday.second.isEmpty()
                )
            })
        }

        @Test
        @EnabledIfEnvironmentVariable(
            named = "customSchoolsTest",
            matches = "true",
            disabledReason = "This test is only enabled if custom test data is provided"
        )
        @DisplayName("Get custom teacher timetable with second shift")
        fun testCustomTeacherTimetableCustomIDCustomTeacherID() = runBlocking {
            run { // Customize library for this test
                SchoolsByParser.setSubdomain(System.getenv("customSchoolDomain"))
            }
            val result = SchoolsByParser.TEACHER.getTimetable(
                System.getenv("customSchoolsTeacherID").toInt(), Credentials(
                    System.getenv("customCsrfToken"), System.getenv("customSessionID")
                )
            )
            val unfoldedResult = result.getOrNull()
            println(result)
            assertAll({
                assert(result.isSuccess)
            }, {
                assert(unfoldedResult!!.monday.second.isNotEmpty())
                // assert(unfoldedResult.monday.second.count { it.place == 3.toShort() } == 2)   the teacher that is supposed to be in test doesn't have two lessons at the same time anymore :(
//                assert(unfoldedResult.wednesday.second.isNotEmpty())
                assert(unfoldedResult.thursday.second.isNotEmpty())
                assert(unfoldedResult.friday.second.isNotEmpty())
                assert(unfoldedResult.saturday.second.isNotEmpty())
            })
            run { // Restore default data after this test
                SchoolsByParser.setSubdomain("https://demo.schools.by")
            }
        }
    }

    @Nested
    @DisplayName("Pupils data tests")
    inner class PupilsDataTests {
        @Test
        @DisplayName("Get pupil's class using valid pupil ID and valid credentials")
        fun testPupilClassValidPupilIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.PUPIL.getPupilClass(pupilID = 100135, validParentCredentials)
            assertAll({
                assert(result.isSuccess)
            }, {
                assert(result.getOrThrow().id == 8)
                assert(result.getOrThrow().classTitle == "11 \"А\"")
                assert(result.getOrThrow().classTeacherID == 108105)
            })
        }

        @Test
        @DisplayName("Get pupil's class using invalid pupil ID and valid credentials")
        fun testPupilClassInvalidPupilIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.PUPIL.getPupilClass(pupilID = -1, validParentCredentials)
            assert(result.isFailure)
            assert(result.exceptionOrNull() is PageNotFound)
        }

        @Test
        @DisplayName("Get pupil's class using valid pupil ID and invalid credentials")
        fun testPupilClassValidPupilIDInvalidCredentials() = runBlocking {
            val result = SchoolsByParser.PUPIL.getPupilClass(pupilID = 100135, invalidCredentials)
            assert(result.isFailure)
            assert(result.exceptionOrNull() is BadSchoolsByCredentials)
        }
    }

    @Nested
    @DisplayName("Parents data tests")
    inner class ParentsDataTests {
        @Test
        @DisplayName("Get parent's pupils using valid parent ID and valid credentials")
        fun testParentsPupilsListValidParentIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.PARENT.getPupils(parentID = 105189, validParentCredentials)
            println(result)
            assert(result.isSuccess)
            assertContentEquals(
                result.getOrThrow(), arrayListOf(
                    Pupil(100140, Name("Алеся", null, "Соловьева"), 8),
                    Pupil(100148, Name("Константин", null, "Соловьев"), 6)
                )
            )
        }

        @Test
        @DisplayName("Get parents pupils using invalid parent ID and valid credentials")
        fun testParentsPupilsInvalidParentIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.PARENT.getPupils(parentID = -1, validParentCredentials)
            assert(result.isFailure)
            assert(result.exceptionOrNull() is PageNotFound)
        }

        @Test
        @DisplayName("Get pupil's class using valid pupil ID and invalid credentials")
        fun testPupilClassValidPupilIDInvalidCredentials() = runBlocking {
            val result = SchoolsByParser.PARENT.getPupils(parentID = 105189, invalidCredentials)
            assert(result.isFailure)
            assert(result.exceptionOrNull() is BadSchoolsByCredentials)
        }
    }
}
