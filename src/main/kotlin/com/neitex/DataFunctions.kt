package com.neitex

import java.time.DayOfWeek
import java.util.*

/**
 * Converts russian week day (i.e. Понедельник) to corresponding [DayOfWeek]
 * @param string Russian week day name
 * @return Corresponding [DayOfWeek]
 */
fun russianDayNameToDayOfWeek(string: String) = when(string.lowercase(Locale.getDefault())){
    "понедельник" -> DayOfWeek.MONDAY
    "вторник" -> DayOfWeek.TUESDAY
    "среда" -> DayOfWeek.WEDNESDAY
    "четверг" -> DayOfWeek.THURSDAY
    "пятница" -> DayOfWeek.FRIDAY
    "суббота" -> DayOfWeek.SATURDAY
    "воскресенье" -> DayOfWeek.SUNDAY
    else -> throw IllegalArgumentException("Value $string is not a valid day of week name.")
}