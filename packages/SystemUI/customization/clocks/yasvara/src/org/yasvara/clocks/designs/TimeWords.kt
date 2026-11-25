/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks.designs

import org.yasvara.clocks.ClockTime

/**
 * The time as people say it. Indonesian follows its own grammar rather than a translated
 * table: half past six is "setengah tujuh", a quarter to seven "tujuh kurang seperempat", and
 * the part of the day is named (pagi, siang, sore, malam). Other languages use English.
 */
internal object TimeWords {
    fun say(time: ClockTime): String = when (time.locale.language) {
        "in", "id" -> indonesian(time.hour24, time.minute)
        else -> english(time.hour24, time.minute)
    }

    fun indonesian(hour24: Int, minute: Int): String {
        val next = hour24 + 1
        val part = when (if (minute > 30) next % 24 else hour24) {
            in 4..10 -> "pagi"
            in 11..14 -> "siang"
            in 15..17 -> "sore"
            else -> "malam"
        }
        val sentence = when {
            minute == 0 -> "pukul ${idHour(hour24)} tepat"
            minute == 15 -> "pukul ${idHour(hour24)} lewat seperempat"
            minute == 30 -> "pukul setengah ${idHour(next)}"
            minute == 45 -> "pukul ${idHour(next)} kurang seperempat"
            minute < 30 -> "pukul ${idHour(hour24)} lewat ${idNumber(minute)} menit"
            else -> "pukul ${idHour(next)} kurang ${idNumber(60 - minute)} menit"
        }
        return "$sentence $part"
    }

    private fun idHour(hour24: Int) = idNumber((hour24 % 12).let { if (it == 0) 12 else it })

    private val ID_UNITS = listOf(
        "", "satu", "dua", "tiga", "empat", "lima", "enam", "tujuh", "delapan", "sembilan",
    )

    fun idNumber(n: Int): String = when {
        n < 10 -> ID_UNITS[n]
        n == 10 -> "sepuluh"
        n == 11 -> "sebelas"
        n < 20 -> "${ID_UNITS[n - 10]} belas"
        else -> {
            val tens = "${ID_UNITS[n / 10]} puluh"
            if (n % 10 == 0) tens else "$tens ${ID_UNITS[n % 10]}"
        }
    }

    fun english(hour24: Int, minute: Int): String {
        val hour = enHour(hour24)
        val next = enHour(hour24 + 1)
        return when {
            minute == 0 -> "$hour o'clock"
            minute == 15 -> "quarter past $hour"
            minute == 30 -> "half past $hour"
            minute == 45 -> "quarter to $next"
            minute < 30 -> "${enNumber(minute)} past $hour"
            else -> "${enNumber(60 - minute)} to $next"
        }
    }

    private fun enHour(hour24: Int) = enNumber((hour24 % 12).let { if (it == 0) 12 else it })

    private val EN_SMALL = listOf(
        "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
        "eighteen", "nineteen",
    )
    private val EN_TENS = listOf("", "", "twenty", "thirty", "forty", "fifty")

    fun enNumber(n: Int): String = when {
        n < 20 -> EN_SMALL[n]
        n % 10 == 0 -> EN_TENS[n / 10]
        else -> "${EN_TENS[n / 10]}-${EN_SMALL[n % 10]}"
    }
}
