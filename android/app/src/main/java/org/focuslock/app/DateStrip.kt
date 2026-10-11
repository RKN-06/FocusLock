package org.focuslock.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val Weekday = DateTimeFormatter.ofPattern("EEE")

/** Scrollable day picker: tap a day to see that date's classes. A dot marks days that have classes. */
@Composable
fun DateStrip(days: List<LocalDate>, selected: LocalDate, withClasses: Set<LocalDate>, onSelect: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val today = remember { LocalDate.now() }
    LaunchedEffect(selected, days.size) {
        val index = days.indexOf(selected)
        if (index >= 0) listState.animateScrollToItem((index - 2).coerceAtLeast(0))
    }
    LazyRow(modifier, state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(days, key = { it.toEpochDay() }) { day ->
            val on = day == selected
            val fill by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, tween(240), label = "day-fill")
            val ink by animateColorAsState(if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, tween(240), label = "day-ink")
            val source = remember { MutableInteractionSource() }
            Column(Modifier.width(56.dp).pressScale(source, 0.94f).glass(RoundedCornerShape(20.dp), 0.dp).background(fill)
                .clickable(interactionSource = source, indication = null, role = Role.Button) { onSelect(day) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (day == today) "Today" else day.format(Weekday), color = ink.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Text(day.dayOfMonth.toString(), color = ink, style = MaterialTheme.typography.titleLarge)
                Box(Modifier.size(6.dp).background(if (day in withClasses) (if (on) ink else MaterialTheme.colorScheme.primary) else Color.Transparent, CircleShape))
            }
        }
    }
}
