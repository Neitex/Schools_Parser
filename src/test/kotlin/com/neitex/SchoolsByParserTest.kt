package com.neitex

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariables
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.*

@DisplayName("Schools.by parser tests")
internal class SchoolsByParserTest {
    private val validCredentials =
        Credentials(csrfToken = System.getenv("csrftoken"), sessionID = System.getenv("sessionid"))
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
            assertAll(
                { assert(result.isFailure) },
                { assert(result.exceptionOrNull() is AuthorizationUnsuccessful) }
            )
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
                validCredentials
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
                validCredentials
            ) // Credentials from Schools.by demo version
            assert(result.isSuccess)
            assert(result.getOrThrow() == 105190)
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
                userID = 105190,
                validCredentials
            ) // Credentials from Schools.by demo version
            assert(result.isSuccess)
            assert(
                result.getOrThrow() == User(
                    105190,
                    SchoolsByUserType.PARENT,
                    Name("Тамара", "Николаевна", "Соловьева")
                )
            ) {
                "$result"
            }
        }

        @Test
        @DisplayName("Get user data using invalid credentials and valid user ID")
        fun testGettingUserDataUsingInvalidCredentials() = runBlocking {
            val result = SchoolsByParser.USER.getBasicUserInfo(
                userID = 105190,
                invalidCredentials
            )
            assert(result.isFailure)
        }

        @Test
        @DisplayName("Get user data using valid credentials and invalid user ID")
        fun testGettingUserDataUsingNonExistentID() = runBlocking {
            val result = SchoolsByParser.USER.getBasicUserInfo(
                userID = 0,
                validCredentials
            ) // Credentials from Schools.by demo version
            assert(result.isFailure)
        }

        @Test
        @DisplayName("Get user data using invalid credentials and invalid user ID")
        fun testGettingUserDataUsingNonExistentIDAndInvalidCredentials() = runBlocking {
            val result = SchoolsByParser.USER.getBasicUserInfo(
                userID = 0,
                validCredentials
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
            val result = SchoolsByParser.CLASS.getClassData(classID = 8, validCredentials)
            assertTrue(result.isSuccess)
            assertEquals(result.getOrThrow(), SchoolClass(8, 108105, "11 \"А\""))
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
            val result = SchoolsByParser.CLASS.getPupilsList(classID = 8, validCredentials)
            assertAll(
                { assert(result.isSuccess) }, {
                    assertContentEquals(
                        result.getOrThrow(), listOf(
                            Pupil(100135, Name("Дмитрий", null, "Ильин"), 8),
                            Pupil(100119, Name("Елена", null, "Макеева"), 8),
                            Pupil(100139, Name("Борис", null, "Пономарёв"), 8),
                            Pupil(100142, Name("Мария", null, "Самсонова"), 8),
                            Pupil(100140, Name("Алеся", null, "Соловьева"), 8),
                            Pupil(100143, Name("Денис", null, "Шилин"), 8),
                            Pupil(100121, Name("Валерий", null, "Юрченко"), 8),
                            Pupil(100131, Name("Сергей", null, "Якушев"), 8)
                        )
                    )
                }
            )
        }

        @Test
        @DisplayName("Get list of pupils using invalid class ID and valid credentials")
        fun testGettingPupilsListInvalidClassIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.CLASS.getPupilsList(classID = (-1), validCredentials)
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
        @DisplayName("Get class timetable using valid class ID and valid credentials")
        fun testGettingTimetableValidClassIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.CLASS.getTimetable(classID = 8, validCredentials, guessShift = true)
            assert(result.isSuccess)
            val timetable = result.getOrThrow().second
            assertAll(
                {
                    assertNotNull(result.getOrThrow().first)
                    assertFalse(result.getOrThrow().first!!)
                },
                {
                    assertContentEquals(
                        timetable.monday, arrayOf(
                            Lesson(1, testConstraintsList[1], "Белорусская литература", 8, null),
                            Lesson(2, testConstraintsList[2], "Белорусский язык", 8, null),
                            Lesson(3, testConstraintsList[3], "История Беларуси", 8, null),
                            Lesson(4, testConstraintsList[4], "Математика", 8, null),
                            Lesson(5, testConstraintsList[5], "Английский язык", 8, null),
                            Lesson(6, testConstraintsList[6], "Информатика", 8, null),
                        )
                    )
                },
                {
                    assertContentEquals(
                        timetable.tuesday, arrayOf(
                            Lesson(1, testConstraintsList[1], "Русская литература", 8, null),
                            Lesson(2, testConstraintsList[2], "Русский язык", 8, null),
                            Lesson(3, testConstraintsList[3], "Обществоведение", 8, null),
                            Lesson(4, testConstraintsList[4], "Физика", 8, null),
                            Lesson(5, testConstraintsList[5], "Физическая культура и здоровье", 8, null)
                        )
                    )
                }, {
                    assertContentEquals(
                        timetable.wednesday, arrayOf(
                            Lesson(2, testConstraintsList[2], "Всемирная История", 8, null),
                            Lesson(3, testConstraintsList[3], "География", 8, null),
                            Lesson(4, testConstraintsList[4], "Биология", 8, null),
                            Lesson(5, testConstraintsList[5], "Английский язык", 8, null)
                        )
                    )
                }, {
                    assertContentEquals(
                        timetable.thursday, arrayOf(
                            Lesson(3, testConstraintsList[3], "Математика", 8, null),
                            Lesson(4, testConstraintsList[4], "Химия", 8, null),
                            Lesson(5, testConstraintsList[5], "Биология", 8, null),
                            Lesson(6, testConstraintsList[6], "Информатика", 8, null),
                            Lesson(7, testConstraintsList[7], "Физическая культура и здоровье", 8, null)
                        )
                    )
                }, {
                    assertContentEquals(
                        timetable.friday, arrayOf(
                            Lesson(2, testConstraintsList[2], "Математика", 8, null),
                            Lesson(3, testConstraintsList[3], "Химия", 8, null),
                            Lesson(4, testConstraintsList[4], "Английский язык", 8, null)
                        )
                    )
                }, {
                    assertContentEquals(timetable.saturday, arrayOf())
                }
            )
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
            val result = SchoolsByParser.CLASS.getTimetable(classID = (-1), validCredentials)
            assert(result.isFailure)
            assert(result.exceptionOrNull() is PageNotFound)
        }
    }

    @Nested
    @DisplayName("Teacher data tests")
    inner class TeacherDataTests {
        @Test
        @DisplayName("Get teacher's class using valid class teacher ID and valid credentials")
        fun testClassTeacherClassValidTeacherIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.TEACHER.getClassForTeacher(108105, validCredentials)
            assertAll(
                {
                    assertTrue(result.isSuccess)
                }, {
                    assertNotNull(result.getOrThrow())
                    assert(result.getOrThrow()!!.id == 8)
                    assert(result.getOrThrow()!!.classTitle == "11 \"А\"")
                }
            )
        }

        @Test
        @DisplayName("Get teacher's class using valid teacher ID with no class and valid credentials")
        fun testClassTeacherClassValidTeacherIDWithNoClassValidCredentials() = runBlocking {
            val result = SchoolsByParser.TEACHER.getClassForTeacher(109035, validCredentials)
            assertAll(
                {
                    assertTrue(result.isSuccess)
                }, {
                    assertNull(result.getOrThrow())
                }
            )
        }

        @Test
        @DisplayName("Get teacher's class using invalid teacher ID and valid credentials")
        fun testClassTeacherClassInvalidTeacherIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.TEACHER.getClassForTeacher(-1, validCredentials)
            assertAll(
                {
                    assertTrue(result.isFailure)
                }, {
                    assert(result.exceptionOrNull() is PageNotFound)
                }
            )
        }

        @Test
        @DisplayName("Get teacher's class using invalid teacher ID and invalid credentials")
        fun testClassTeacherClassInvalidTeacherIDInvalidCredentials() = runBlocking {
            val result = SchoolsByParser.TEACHER.getClassForTeacher(-1, invalidCredentials)
            assertAll(
                {
                    assertTrue(result.isFailure)
                }, {
                    assert(result.exceptionOrNull() is BadSchoolsByCredentials)
                }
            )
        }

        @Test
        @DisplayName("Get teacher's class using pupil ID and valid credentials")
        fun testClassTeacherClassPupilIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.TEACHER.getClassForTeacher(100135, validCredentials)
            assertAll(
                {
                    assertTrue(result.isFailure)
                }, {
                    assert(result.exceptionOrNull() is PageNotFound)
                }
            )
        }

        @Test
        @DisplayName("Get teacher's timetable using valid teacher ID and valid credentials")
        fun testTeacherTimetableValidTeacherIDValidCredentials() = runBlocking {
            val result = SchoolsByParser.TEACHER.getTimetable(108105, validCredentials)
            val unpackedResult = result.getOrThrow()
            assertAll(
                {
                    assertTrue(result.isSuccess)
                    assertNotNull(result.getOrNull())
                }, {
                    assertContentEquals(
                        unpackedResult.monday.first, arrayOf(
                            Lesson(1, testConstraintsList[1], "Белорусская литература", 8, 108105),
                            Lesson(2, testConstraintsList[2], "Белорусский язык", 8, 108105),
                            Lesson(3, testConstraintsList[3], "Английский язык", 6, 108105)
                        )
                    )
                    assert(unpackedResult.monday.second.isEmpty())
                }, {
                    assertContentEquals(
                        unpackedResult.tuesday.first, arrayOf(
                            Lesson(4, testConstraintsList[4], "Английский язык", 6, 108105)
                        )
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
                }
            )
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
            assertAll(
                {
                    assert(result.isSuccess)
                }, {
                    assert(unfoldedResult!!.monday.second.isNotEmpty())
                    assert(unfoldedResult.monday.second.count { it.place == 3.toShort() } == 2) // Ensures support of multiple lessons per place
                    assert(unfoldedResult.wednesday.second.isNotEmpty())
                    assert(unfoldedResult.thursday.second.isNotEmpty())
                    assert(unfoldedResult.friday.second.isNotEmpty())
                }
            )
            run { // Restore default data after this test
                SchoolsByParser.setSubdomain("https://demo.schools.by")
            }
        }
    }
}