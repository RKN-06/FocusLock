package org.focuslock.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Attendance statuses become coloured dots under each day. */
@Composable
private fun statusColor(status: String): Color = when (status) {
    "present" -> MaterialTheme.colorScheme.primary
    "absent" -> MaterialTheme.colorScheme.error
    else -> Color(0xFF7A6BD6)
}

/** Present is a circle, absent a square, excused a diamond, so the marks differ by shape as well as colour. */
private fun statusMark(status: String, modifier: Modifier, color: Color): Modifier = when (status) {
    "present" -> modifier.background(color, CircleShape)
    "absent" -> modifier.background(color, RoundedCornerShape(1.dp))
    else -> modifier.rotate(45f).background(color, RoundedCornerShape(1.dp))
}

private fun statusWord(status: String) = when (status) { "present" -> "present"; "absent" -> "absent"; else -> "excused" }

/** A month calendar for attendance history: tap a day to see its records, swipe months with the arrows. */
@Composable
fun MonthCalendar(month: YearMonth, marks: Map<LocalDate, List<String>>, selected: LocalDate?, onSelect: (LocalDate?) -> Unit,
                  onMonth: (YearMonth) -> Unit, modifier: Modifier = Modifier) {
    val today = remember { LocalDate.now() }
    val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val lead = (month.atDay(1).dayOfWeek.value - first.value + 7) % 7
    val rows = (lead + month.lengthOfMonth() + 6) / 7
    Column(modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton({ onMonth(month.minusMonths(1)) }, "Previous month") { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null, tint = MaterialTheme.colorScheme.primary) }
            AnimatedContent(month, transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally(tween(260)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(260)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(160)))
            }, label = "month") { shown ->
                Text(shown.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            GlassIconButton({ onMonth(month.plusMonths(1)) }, "Next month") { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary) }
        }
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                val day: DayOfWeek = first.plus(i.toLong())
                Text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
        }
        for (week in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 7) {
                    val dayNumber = week * 7 + col - lead + 1
                    if (dayNumber < 1 || dayNumber > month.lengthOfMonth()) { Box(Modifier.weight(1f).heightIn(min = 48.dp)); continue }
                    val date = month.atDay(dayNumber)
                    val on = date == selected
                    val statuses = marks[date].orEmpty().distinct().take(3)
                    val fill by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, tween(220), label = "cell")
                    val ink by animateColorAsState(if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, tween(220), label = "ink")
                    val source = remember { MutableInteractionSource() }
                    val spoken = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM")) + when {
                        statuses.isEmpty() -> ", no records"
                        else -> ", " + marks[date].orEmpty().size + (if (marks[date].orEmpty().size == 1) " record: " else " records: ") + statuses.joinToString(" and ") { statusWord(it) }
                    } + if (date == today) ", today" else ""
                    Column(Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = spoken; this.selected = on }
                        .pressScale(source, 0.92f).background(fill, CircleShape)
                        .let { if (date == today && !on) it.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape) else it }
                        .clickable(interactionSource = source, indication = null, role = Role.Button) { onSelect(if (on) null else date) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(dayNumber.toString(), color = ink, style = MaterialTheme.typography.bodyMedium, fontWeight = if (statuses.isNotEmpty() || date == today) FontWeight.Bold else FontWeight.Normal)
                        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            statuses.forEach { Box(statusMark(it, Modifier.size(6.dp), if (on) ink else statusColor(it))) }
                            if (statuses.isEmpty()) Box(Modifier.size(5.dp))
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((label, key) in listOf("Present" to "present", "Absent" to "absent", "Excused" to "excused")) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(statusMark(key, Modifier.size(8.dp), statusColor(key)))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
