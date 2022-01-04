package com.neitex

import java.time.DayOfWeek
import java.util.*

/**
 * Converts russian week day (i.e. Понедельник) to corresponding [DayOfWeek]
 * @param string Russian week day name
 * @return Corresponding [DayOfWeek]
 */
fun russianDayNameToDayOfWeek(string: String) = when (string.lowercase(Locale.getDefault())) {
    "понедельник" -> DayOfWeek.MONDAY
    "вторник" -> DayOfWeek.TUESDAY
    "среда" -> DayOfWeek.WEDNESDAY
    "четверг" -> DayOfWeek.THURSDAY
    "пятница" -> DayOfWeek.FRIDAY
    "суббота" -> DayOfWeek.SATURDAY
    "воскресенье" -> DayOfWeek.SUNDAY
    else -> throw IllegalArgumentException("Value $string is not a valid day of week name.")
}

/**
 * Unfold shortened lesson title, and if it fails, returns original title
 */
fun unfoldLessonTitle(input: String) = when (input) {
    "Англ. яз." -> "Английский язык"
    "Бел. лит." -> "Белорусская литература"
    "Бел. яз." -> "Белорусский язык"
    "Рус. яз." -> "Русский язык"
    "Рус. лит." -> "Русская литература"
    "Физ. к. и зд." -> "Физическая культура и здоровье"
    "Матем." -> "Математика"
    "Труд. обуч." -> "Трудовое обучение"
    "Информ. час" -> "Информационный час"
    "ЧЗС" -> "Час здоровья и спорта"
    "Кл. час" -> "Классный час"
    "Всемир. ист." -> "Всемирная история"
    "Обществов." -> "Обществоведение"
    "Ист. Бел." -> "История Беларуси"
    "Информ." -> "Информатика"
    else -> input
}