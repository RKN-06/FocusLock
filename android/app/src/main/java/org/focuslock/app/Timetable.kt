package org.focuslock.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val Clock = DateTimeFormatter.ofPattern("HH:mm")
private val DayName = DateTimeFormatter.ofPattern("EEE, d MMM")

private fun roomText(room: String) = if (room.isBlank() || room == "UNASSIGNED") "Room not assigned" else "Room $room"

private fun startsIn(entry: TimetableEntry, now: Instant, zone: ZoneId): String {
    val start = Instant.parse(entry.startsAt)
    val end = Instant.parse(entry.endsAt)
    if (now >= start && now < end) return "Happening now · ends in ${Duration.between(now, end).toMinutes().coerceAtLeast(1)} min"
    val minutes = Duration.between(now, start).toMinutes()
    val local = start.atZone(zone)
    return when {
        minutes < 1 -> "Starting now"
        minutes < 60 -> "Starts in $minutes min"
        local.toLocalDate() == LocalDate.now(zone) -> "Today at ${local.format(Clock)}"
        local.toLocalDate() == LocalDate.now(zone).plusDays(1) -> "Tomorrow at ${local.format(Clock)}"
        else -> "${local.format(DayName)} at ${local.format(Clock)}"
    }
}

/** Your week of classes: what is next, where it is, who teaches it, and which period it falls in. */
@Composable
fun TimetableScreen(entries: List<TimetableEntry>, weekStart: LocalDate, loading: Boolean, onWeek: (LocalDate) -> Unit, onRefresh: () -> Unit,
                    onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }
    val zone = ZoneId.systemDefault()
    val days = entries.groupBy { Instant.parse(it.startsAt).atZone(zone).toLocalDate() }.toSortedMap()
    val next = entries.filter { Instant.parse(it.endsAt) > now }.minByOrNull { Instant.parse(it.startsAt) }
    val weekEnd = weekStart.plusDays(6)
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item { Row(Modifier.fillMaxWidth().appear(0), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Timetable", style = MaterialTheme.typography.headlineLarge)
            GlassIconButton(onRefresh, "Refresh") { Icon(Icons.Rounded.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        } }
        item { Row(Modifier.fillMaxWidth().appear(1), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton({ onWeek(weekStart.minusWeeks(1)) }, "Previous week") { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null, tint = MaterialTheme.colorScheme.primary) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${weekStart.format(DateTimeFormatter.ofPattern("d MMM"))} – ${weekEnd.format(DateTimeFormatter.ofPattern("d MMM"))}", style = MaterialTheme.typography.titleMedium)
                GTextButton({ onWeek(currentWeekStart()) }) { Text("This week") }
            }
            GlassIconButton({ onWeek(weekStart.plusWeeks(1)) }, "Next week") { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary) }
        } }
        next?.let { entry -> item(key = "next") { GlassCard(Modifier.appear(2), onClick = { onOpen(entry.id) }) {
            Text("NEXT UP", style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
            Text(entry.courseTitle, style = MaterialTheme.typography.titleLarge)
            Text(roomText(entry.roomCode), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(startsIn(entry, now, zone), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(listOfNotNull(entry.teacher, entry.period).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } } }
        if (!loading && entries.isEmpty()) item { Text("No classes this week", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
        days.forEach { (day, list) ->
            item(key = "day-$day") { SectionLabel(if (day == LocalDate.now(zone)) "Today · ${day.format(DayName)}" else day.format(DayName),
                MaterialTheme.colorScheme.primary, Icons.Rounded.DateRange, Modifier.appear(2), count = list.size) }
            items(list, key = { it.id }) { entry -> TimetableCard(entry, past = Instant.parse(entry.endsAt) <= now, zone = zone, onOpen = onOpen) }
        }
    }
}

fun currentWeekStart(): LocalDate = LocalDate.now().let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }

@Composable
private fun TimetableCard(entry: TimetableEntry, past: Boolean, zone: ZoneId, onOpen: (Int) -> Unit) {
    val start = Instant.parse(entry.startsAt).atZone(zone)
    val end = Instant.parse(entry.endsAt).atZone(zone)
    val spoken = "${start.format(Clock)} to ${end.format(Clock)}, ${entry.courseTitle}, ${roomText(entry.roomCode)}, with ${entry.teacher}" +
        (entry.period?.let { ", $it" } ?: "") + (entry.attendance?.let { ", attendance $it" } ?: "")
    GlassCard(Modifier.semantics { contentDescription = spoken }, onClick = { onOpen(entry.id) }) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.width(52.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(start.format(Clock), style = MaterialTheme.typography.titleMedium, color = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Text(end.format(Clock), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.width(3.dp).fillMaxHeight().background(if (past) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.courseTitle, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(entry.courseCode, entry.period, entry.teacher).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassChip(roomText(entry.roomCode))
                    entry.attendance?.let { GlassChip(it.replaceFirstChar { c -> c.uppercase() }, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
