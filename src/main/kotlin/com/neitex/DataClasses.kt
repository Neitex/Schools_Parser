package com.neitex

import java.time.DayOfWeek

data class Credentials(val csrfToken: String, val sessionID: String)

enum class SchoolsByUserType { PARENT, PUPIL, TEACHER, ADMINISTRATION }

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

data class Lesson(
    val place: Short,
    val timeConstraints: TimeConstraints,
    val title: String,
    val classID: Int,
    val teacherID: Int?
)

class Timetable {
    private var lessons: Map<DayOfWeek, Array<Lesson>>

    constructor(lessonsMap: Map<DayOfWeek, Array<Lesson>>) {
        require(lessonsMap.keys.containsAll(kotlin.run {
            val set = mutableSetOf<DayOfWeek>()
            DayOfWeek.values().filter { it != DayOfWeek.SUNDAY }.toCollection(set)
            return@run set
        }))
        lessons = lessonsMap
    }

    constructor(
        monday: Array<Lesson> = arrayOf(),
        tuesday: Array<Lesson> = arrayOf(),
        wednesday: Array<Lesson> = arrayOf(),
        thursday: Array<Lesson> = arrayOf(),
        friday: Array<Lesson> = arrayOf(),
        saturday: Array<Lesson> = arrayOf()
    ) {
        lessons = kotlin.run {
            val map = mutableMapOf<DayOfWeek, Array<Lesson>>()
            map[DayOfWeek.MONDAY] = monday
            map[DayOfWeek.TUESDAY] = tuesday
            map[DayOfWeek.WEDNESDAY] = wednesday
            map[DayOfWeek.THURSDAY] = thursday
            map[DayOfWeek.FRIDAY] = friday
            map[DayOfWeek.SATURDAY] = saturday
            map.toMap()
        }
    }

    operator fun get(day: DayOfWeek): Array<Lesson> {
        require(day != DayOfWeek.SUNDAY)
        return lessons[day]!!
    }

    operator fun set(day: DayOfWeek, value: Array<Lesson>) {
        require(day != DayOfWeek.SUNDAY)
        lessons = lessons.minus(day).plus(Pair(day, value))
    }

    val monday: Array<Lesson>
        get() = lessons[DayOfWeek.MONDAY]!!
    val tuesday: Array<Lesson>
        get() = lessons[DayOfWeek.TUESDAY]!!
    val wednesday: Array<Lesson>
        get() = lessons[DayOfWeek.WEDNESDAY]!!
    val thursday: Array<Lesson>
        get() = lessons[DayOfWeek.THURSDAY]!!
    val friday: Array<Lesson>
        get() = lessons[DayOfWeek.FRIDAY]!!
    val saturday: Array<Lesson>
        get() = lessons[DayOfWeek.SATURDAY]!!
}

class TwoShiftsTimetable {
    private var lessons: Map<DayOfWeek, Pair<Array<Lesson>, Array<Lesson>>>

    constructor(lessonsMap: Map<DayOfWeek, Pair<Array<Lesson>, Array<Lesson>>>) {
        require(lessonsMap.keys.containsAll(kotlin.run {
            val set = mutableSetOf<DayOfWeek>()
            DayOfWeek.values().filter { it != DayOfWeek.SUNDAY }.toCollection(set)
            return@run set
        }))
        lessons = lessonsMap
    }

    constructor(
        monday: Pair<Array<Lesson>, Array<Lesson>> = Pair(arrayOf(), arrayOf()),
        tuesday: Pair<Array<Lesson>, Array<Lesson>> = Pair(arrayOf(), arrayOf()),
        wednesday: Pair<Array<Lesson>, Array<Lesson>> = Pair(arrayOf(), arrayOf()),
        thursday: Pair<Array<Lesson>, Array<Lesson>> = Pair(arrayOf(), arrayOf()),
        friday: Pair<Array<Lesson>, Array<Lesson>> = Pair(arrayOf(), arrayOf()),
        saturday: Pair<Array<Lesson>, Array<Lesson>> = Pair(arrayOf(), arrayOf())
    ) {
        lessons = kotlin.run {
            val map = mutableMapOf<DayOfWeek, Pair<Array<Lesson>, Array<Lesson>>>()
            map[DayOfWeek.MONDAY] = monday
            map[DayOfWeek.TUESDAY] = tuesday
            map[DayOfWeek.WEDNESDAY] = wednesday
            map[DayOfWeek.THURSDAY] = thursday
            map[DayOfWeek.FRIDAY] = friday
            map[DayOfWeek.SATURDAY] = saturday
            map.toMap()
        }
    }

    operator fun get(day: DayOfWeek): Pair<Array<Lesson>, Array<Lesson>> {
        require(day != DayOfWeek.SUNDAY)
        return lessons[day]!!
    }

    operator fun set(day: DayOfWeek, value: Pair<Array<Lesson>, Array<Lesson>>) {
        require(day != DayOfWeek.SUNDAY)
        lessons = lessons.minus(day).plus(Pair(day, value))
    }

    val monday: Pair<Array<Lesson>, Array<Lesson>>
        get() = lessons[DayOfWeek.MONDAY]!!
    val tuesday: Pair<Array<Lesson>, Array<Lesson>>
        get() = lessons[DayOfWeek.TUESDAY]!!
    val wednesday: Pair<Array<Lesson>, Array<Lesson>>
        get() = lessons[DayOfWeek.WEDNESDAY]!!
    val thursday: Pair<Array<Lesson>, Array<Lesson>>
        get() = lessons[DayOfWeek.THURSDAY]!!
    val friday: Pair<Array<Lesson>, Array<Lesson>>
        get() = lessons[DayOfWeek.FRIDAY]!!
    val saturday: Pair<Array<Lesson>, Array<Lesson>>
        get() = lessons[DayOfWeek.SATURDAY]!!
}