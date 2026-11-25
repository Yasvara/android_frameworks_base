/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks

import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import android.icu.util.Calendar
import android.icu.util.TimeZone
import java.util.Date
import java.util.Locale

/** One reading of the clock, with the pieces faces draw. */
class ClockTime(
    date: Date,
    timeZone: TimeZone,
    val is24Hour: Boolean,
    val locale: Locale,
) {
    private val calendar = Calendar.getInstance(timeZone, locale).apply { time = date }

    val hour24: Int = calendar.get(Calendar.HOUR_OF_DAY)
    val minute: Int = calendar.get(Calendar.MINUTE)
    val second: Int = calendar.get(Calendar.SECOND)
    val dayOfMonth: Int = calendar.get(Calendar.DAY_OF_MONTH)
    val dayOfWeek: Int = calendar.get(Calendar.DAY_OF_WEEK)

    /** The hour as shown: 0-23, or 1-12 on a 12 hour clock. */
    val hour: Int = if (is24Hour) hour24 else (hour24 % 12).let { if (it == 0) 12 else it }

    /** Fraction of the day gone, 0 at midnight. */
    val dayFraction: Float = (hour24 * 3600 + minute * 60 + second) / 86_400f

    val hourText: String = if (is24Hour) "%02d".format(locale, hour) else hour.toString()
    val minuteText: String = "%02d".format(locale, minute)
    val secondText: String = "%02d".format(locale, second)
    val timeText: String = "$hourText:$minuteText"

    /** Four digits for faces that draw each digit alone; a 12 hour "9" is "09". */
    val digits: String = "%02d%02d".format(Locale.ROOT, hour, minute)

    val amPm: String? = if (is24Hour) null else if (hour24 < 12) "AM" else "PM"

    /** For instance "Tue 30 Sep" in the user's locale. */
    val dateText: String = pattern("EEEMMMd", date, timeZone)
    val dayName: String = pattern("EEEE", date, timeZone)
    val monthText: String = pattern("MMMM", date, timeZone)

    private fun pattern(skeleton: String, date: Date, timeZone: TimeZone): String =
        DateFormat.getInstanceForSkeleton(skeleton, locale).apply {
            this.timeZone = timeZone
            setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE)
        }.format(date)
}
