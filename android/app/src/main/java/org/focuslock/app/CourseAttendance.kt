package org.focuslock.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Terms (semesters) with a card per course showing the attendance percentage; tapping a course opens its calendar of present and absent days. */
fun LazyListScope.courseAttendance(
    terms: List<TermSummary>, term: String?, courseId: Int?, onTerm: (String) -> Unit, onCourse: (Int?) -> Unit,
    month: YearMonth, picked: LocalDate?, onMonth: (YearMonth) -> Unit, onPick: (LocalDate?) -> Unit, onReport: (Int) -> Unit,
) {
    if (terms.isEmpty()) return
    val current = terms.firstOrNull { it.term == term } ?: terms.first()
    val course = current.courses.firstOrNull { it.courseId == courseId }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("By semester and course", MaterialTheme.colorScheme.primary, Icons.Rounded.DateRange)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (item in terms) GlassChip(item.term, Modifier.clickable(role = Role.Tab) { onTerm(item.term); onCourse(null) },
                    color = if (item.term == current.term) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    items(current.courses.size) { index ->
        val row = current.courses[index]
        GlassCard(onClick = { onCourse(if (row.courseId == courseId) null else row.courseId) }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.code, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(row.title, style = MaterialTheme.typography.titleMedium)
                    Text(row.teacher, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(row.rate?.let { "${it.toInt()}%" } ?: "—", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            }
            LinearProgressIndicator(progress = { ((row.rate ?: 0.0) / 100.0).toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Text("Present ${row.present} of ${row.held} · Absent ${row.absent + row.notRecorded}" + (if (row.excused > 0) " · Excused ${row.excused}" else ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            if (row.breaks > 0 || row.quitFocus > 0) Text("Breaks asked ${row.breaks} · Left focus early ${row.quitFocus}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (course != null) {
        val marks = course.days.groupBy { LocalDate.parse(it.date) }.mapValues { (_, rows) -> rows.map { it.status ?: "absent" } }
        val shown = course.days.filter { YearMonth.from(LocalDate.parse(it.date)) == month && (picked == null || LocalDate.parse(it.date) == picked) }.sortedByDescending { it.startsAt }
        item { MonthCalendar(month, marks, picked, onPick, onMonth) }
        item { SectionLabel("${course.code} · " + (picked?.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")) ?: month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))),
            MaterialTheme.colorScheme.primary, Icons.Rounded.DateRange, count = shown.size) }
        if (shown.isEmpty()) item { Text(if (picked != null) "No classes on this day" else "No classes this month", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(shown.size) { index ->
            val day = shown[index]
            val time = Instant.parse(day.startsAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, h:mm a"))
            GlassCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(day.title, style = MaterialTheme.typography.titleSmall)
                        Text(time + if (day.roomCode.isNotBlank()) " · ${day.roomCode}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    GlassChip(when (day.status) { "present" -> "Present"; "excused" -> "Excused"; "absent" -> "Absent"; else -> "Not marked" },
                        color = if (day.status == "present") MaterialTheme.colorScheme.primary else if (day.status == "excused") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                }
                if (day.status != "present") GTextButton(onClick = { onReport(day.sessionId) }) { Text("Report a mistake") }
            }
        }
    }
}
