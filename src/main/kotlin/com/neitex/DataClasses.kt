package com.neitex

import java.time.DayOfWeek
import java.time.LocalDate

data class Credentials(val csrfToken: String, val sessionID: String)

enum class SchoolsByUserType { PARENT, PUPIL, TEACHER, ADMINISTRATION, DIRECTOR }

data class Name(val firstName: String, val middleName: String?, val lastName: String) {
    companion object {
        fun fromString(input: String): Name? {
            val split = input.split(' ')
            return when (split.size) {
                2 -> Name(split[1], null, split[0])
                3 -> Name(split[1], split[2], split[0])
                else -> null
            }
        }
    }
}

open class User(val id: Int, val type: SchoolsByUserType, val name: Name) {
    override fun equals(other: Any?): Boolean {
        return if (other is User) {
            (other.id == this.id) && (other.name == this.name) && (other.type == this.type)
        } else super.equals(other)
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + type.hashCode()
        result = 31 * result + name.hashCode()
        return result
    }
}

data class SchoolClass(val id: Int, val classTeacherID: Int, val classTitle: String)

@Suppress("unused")
class Pupil(id: Int, name: Name, val classID: Int) : User(id, SchoolsByUserType.PUPIL, name)

data class TimeConstraints(val startHour: Short, val startMinute: Short, val endHour: Short, val endMinute: Short) {
    companion object {
        fun fromString(input: String): TimeConstraints? {
            val startTime = input.substringBefore(" – ")
            if (startTime == input) {
                return null
            }
            return try {
                val endTime = input.substringAfter(" – ")
                val startHour = startTime.substringBefore(':').toShort()
                val startMinute = startTime.substringAfter(':').toShort()

                val endHour = endTime.substringBefore(':').toShort()
                val endMinute = endTime.substringAfter(':').toShort()
                TimeConstraints(startHour, startMinute, endHour, endMinute)
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}

data class TimetableLesson(
    val place: Short,
    val timeConstraints: TimeConstraints,
    val title: String,
    val classID: Int,
    val teacherID: Array<Int>?,
    val journalID: Int?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TimetableLesson

        if (place != other.place) return false
        if (timeConstraints != other.timeConstraints) return false
        if (title != other.title) return false
        if (classID != other.classID) return false
        if (teacherID != null) {
            if (other.teacherID == null) return false
            if (!teacherID.contentEquals(other.teacherID)) return false
        } else if (other.teacherID != null) return false
        return journalID == other.journalID
    }

    override fun hashCode(): Int {
        var result = place.toInt()
        result = 31 * result + timeConstraints.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + classID
        result = 31 * result + (teacherID?.contentHashCode() ?: 0)
        result = 31 * result + (journalID ?: 0)
        return result
    }
}

class Timetable {
    private var lessons: Map<DayOfWeek, Array<TimetableLesson>>

    constructor(lessonsMap: Map<DayOfWeek, Array<TimetableLesson>>) {
        require(lessonsMap.keys.containsAll(kotlin.run {
            val set = mutableSetOf<DayOfWeek>()
            DayOfWeek.values().filter { it != DayOfWeek.SUNDAY }.toCollection(set)
            return@run set
        }))
        lessons = lessonsMap
    }

    @Suppress("UNUSED")
    constructor(
        monday: Array<TimetableLesson> = arrayOf(),
        tuesday: Array<TimetableLesson> = arrayOf(),
        wednesday: Array<TimetableLesson> = arrayOf(),
        thursday: Array<TimetableLesson> = arrayOf(),
        friday: Array<TimetableLesson> = arrayOf(),
        saturday: Array<TimetableLesson> = arrayOf()
    ) {
        lessons = kotlin.run {
            val map = mutableMapOf<DayOfWeek, Array<TimetableLesson>>()
            map[DayOfWeek.MONDAY] = monday
            map[DayOfWeek.TUESDAY] = tuesday
            map[DayOfWeek.WEDNESDAY] = wednesday
            map[DayOfWeek.THURSDAY] = thursday
            map[DayOfWeek.FRIDAY] = friday
            map[DayOfWeek.SATURDAY] = saturday
            map.toMap()
        }
    }

    operator fun get(day: DayOfWeek): Array<TimetableLesson> {
        require(day != DayOfWeek.SUNDAY)
        return lessons[day]!!
    }

    operator fun set(day: DayOfWeek, value: Array<TimetableLesson>) {
        require(day != DayOfWeek.SUNDAY)
        lessons = lessons.minus(day).plus(Pair(day, value))
    }

    val monday: Array<TimetableLesson>
        get() = lessons[DayOfWeek.MONDAY]!!
    val tuesday: Array<TimetableLesson>
        get() = lessons[DayOfWeek.TUESDAY]!!
    val wednesday: Array<TimetableLesson>
        get() = lessons[DayOfWeek.WEDNESDAY]!!
    val thursday: Array<TimetableLesson>
        get() = lessons[DayOfWeek.THURSDAY]!!
    val friday: Array<TimetableLesson>
        get() = lessons[DayOfWeek.FRIDAY]!!
    val saturday: Array<TimetableLesson>
        get() = lessons[DayOfWeek.SATURDAY]!!
}

class TwoShiftsTimetable {
    private var lessons: Map<DayOfWeek, Pair<Array<TimetableLesson>, Array<TimetableLesson>>>

    constructor(lessonsMap: Map<DayOfWeek, Pair<Array<TimetableLesson>, Array<TimetableLesson>>>) {
        require(lessonsMap.keys.containsAll(kotlin.run {
            val set = mutableSetOf<DayOfWeek>()
            DayOfWeek.values().filter { it != DayOfWeek.SUNDAY }.toCollection(set)
            return@run set
        }))
        lessons = lessonsMap
    }

    @Suppress("UNUSED")
    constructor(
        monday: Pair<Array<TimetableLesson>, Array<TimetableLesson>> = Pair(arrayOf(), arrayOf()),
        tuesday: Pair<Array<TimetableLesson>, Array<TimetableLesson>> = Pair(arrayOf(), arrayOf()),
        wednesday: Pair<Array<TimetableLesson>, Array<TimetableLesson>> = Pair(arrayOf(), arrayOf()),
        thursday: Pair<Array<TimetableLesson>, Array<TimetableLesson>> = Pair(arrayOf(), arrayOf()),
        friday: Pair<Array<TimetableLesson>, Array<TimetableLesson>> = Pair(arrayOf(), arrayOf()),
        saturday: Pair<Array<TimetableLesson>, Array<TimetableLesson>> = Pair(arrayOf(), arrayOf())
    ) {
        lessons = kotlin.run {
            val map = mutableMapOf<DayOfWeek, Pair<Array<TimetableLesson>, Array<TimetableLesson>>>()
            map[DayOfWeek.MONDAY] = monday
            map[DayOfWeek.TUESDAY] = tuesday
            map[DayOfWeek.WEDNESDAY] = wednesday
            map[DayOfWeek.THURSDAY] = thursday
            map[DayOfWeek.FRIDAY] = friday
            map[DayOfWeek.SATURDAY] = saturday
            map.toMap()
        }
    }

    operator fun get(day: DayOfWeek): Pair<Array<TimetableLesson>, Array<TimetableLesson>> {
        require(day != DayOfWeek.SUNDAY)
        return lessons[day]!!
    }

    operator fun set(day: DayOfWeek, value: Pair<Array<TimetableLesson>, Array<TimetableLesson>>) {
        require(day != DayOfWeek.SUNDAY)
        lessons = lessons.minus(day).plus(Pair(day, value))
    }

    val monday: Pair<Array<TimetableLesson>, Array<TimetableLesson>>
        get() = lessons[DayOfWeek.MONDAY]!!
    val tuesday: Pair<Array<TimetableLesson>, Array<TimetableLesson>>
        get() = lessons[DayOfWeek.TUESDAY]!!
    val wednesday: Pair<Array<TimetableLesson>, Array<TimetableLesson>>
        get() = lessons[DayOfWeek.WEDNESDAY]!!
    val thursday: Pair<Array<TimetableLesson>, Array<TimetableLesson>>
        get() = lessons[DayOfWeek.THURSDAY]!!
    val friday: Pair<Array<TimetableLesson>, Array<TimetableLesson>>
        get() = lessons[DayOfWeek.FRIDAY]!!
    val saturday: Pair<Array<TimetableLesson>, Array<TimetableLesson>>
        get() = lessons[DayOfWeek.SATURDAY]!!
}

data class Lesson(
    val lessonID: Long,
    val journalID: Int?,
    val teacher: Int?,
    val subgroup: Int?,
    val title: String,
    val date: LocalDate,
    val place: Int
)

data class Subgroup(
    val subgroupID: Int,
    val title: String,
    val pupils: List<Int>
)

data class TimetablePlace(
    val place: Int,
    val constraints: TimeConstraints
)
