package org.focuslock.app

import android.app.Activity
import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/** Follow the phone's setting by default; the user can pin Light or Dark from the header. */
enum class ThemeMode {
    System, Light, Dark;
    fun next(): ThemeMode = entries[(ordinal + 1) % entries.size]
    val glyph: String get() = when (this) { System -> "◐"; Light -> "☀"; Dark -> "☾" }
    val label: String get() = when (this) { System -> "Theme: follow system"; Light -> "Theme: light"; Dark -> "Theme: dark" }
}

object UiPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("focuslock-ui", Context.MODE_PRIVATE)
    fun load(context: Context): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs(context).getString("theme", null) ?: "System") }.getOrDefault(ThemeMode.System)
    fun save(context: Context, mode: ThemeMode) { prefs(context).edit().putString("theme", mode.name).apply() }
}

@Immutable
class GlassPalette(
    val dark: Boolean,
    val backdrop: List<Color>,
    val blobs: List<Color>,
    val blobAlpha: Float,
    val fillTop: Color,
    val fillBottom: Color,
    val edgeStart: Color,
    val edgeEnd: Color,
    val shadow: Color,
)

private val LightGlass = GlassPalette(
    dark = false,
    backdrop = listOf(Color(0xFFCFEFE4), Color(0xFFD9E6F7), Color(0xFFE8DCF5)),
    blobs = listOf(Color.White, Color(0xFFE9F8F1), Color.White),
    blobAlpha = 0.40f,
    fillTop = Color.White.copy(alpha = 0.72f), fillBottom = Color.White.copy(alpha = 0.40f),
    edgeStart = Color.White.copy(alpha = 0.95f), edgeEnd = Color.White.copy(alpha = 0.25f),
    shadow = Color(0xFF1B3A4B),
)

private val DarkGlass = GlassPalette(
    dark = true,
    backdrop = listOf(Color(0xFF07130F), Color(0xFF0A1815), Color(0xFF0A141B)),
    blobs = listOf(Color(0xFF1F8F72), Color(0xFF2A7F8F), Color(0xFF3C6E5A)),
    blobAlpha = 0.34f,
    fillTop = Color.White.copy(alpha = 0.14f), fillBottom = Color.White.copy(alpha = 0.05f),
    edgeStart = Color.White.copy(alpha = 0.34f), edgeEnd = Color.White.copy(alpha = 0.06f),
    shadow = Color.Black,
)

val LocalGlass = staticCompositionLocalOf { LightGlass }

private val LightColors = lightColorScheme(primary = Color(0xFF17584B), background = Color(0xFFF5F6F1))

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5ED1B0), onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF0F5142), onPrimaryContainer = Color(0xFFB9F2DF),
    secondary = Color(0xFFB1CCC2), tertiary = Color(0xFFF2C46B),
    background = Color(0xFF08120F), onBackground = Color(0xFFE3F0EB),
    surface = Color(0xFF111C19), onSurface = Color(0xFFE3F0EB),
    surfaceVariant = Color(0xFF1D2B27), onSurfaceVariant = Color(0xFFB7C9C2),
    surfaceContainerHigh = Color(0xFF1A2723), surfaceContainerHighest = Color(0xFF22322D),
    outline = Color(0xFF7F948D), error = Color(0xFFFFB4AB),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun FocusLockTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Light -> false; ThemeMode.Dark -> true }
    val view = LocalView.current
    val activity = LocalContext.current as? Activity
    if (!view.isInEditMode && activity != null) SideEffect {
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalGlass provides if (dark) DarkGlass else LightGlass) {
        androidx.compose.material3.MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, content = content)
    }
}

/** Honors the system "remove animations" setting. */
@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
}

/** Soft gradient with three slowly drifting colour glows; the glass surfaces sit on top of it. */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier.fillMaxSize()) {
    val g = LocalGlass.current
    val c0 by animateColorAsState(g.backdrop[0], tween(600), label = "bg0")
    val c1 by animateColorAsState(g.backdrop[1], tween(600), label = "bg1")
    val c2 by animateColorAsState(g.backdrop[2], tween(600), label = "bg2")
    val motion = rememberMotionEnabled()
    val depth = LocalBackdropScroll.current
    val shift by animateFloatAsState(depth?.offset ?: 0f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow), label = "parallax")
    val t: State<Float> = if (motion) rememberInfiniteTransition(label = "blobs")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(48_000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    else remember { mutableFloatStateOf(0.15f) }
    Box(modifier.drawBehind {
        drawRect(Brush.linearGradient(listOf(c0, c1, c2)))
        val radius = maxOf(size.width, size.height) * 0.62f
        val angle = t.value * 2f * PI.toFloat()
        g.blobs.forEachIndexed { i, color ->
            val a = angle * (i + 1) + i * 2.1f
            val center = Offset(size.width * (0.5f + 0.40f * cos(a)), size.height * (0.5f + 0.36f * sin(a + i)) + shift * (0.6f + 0.5f * i))
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = g.blobAlpha), Color.Transparent), center = center, radius = radius),
                radius = radius, center = center,
            )
        }
    })
}

/** Frosted-glass surface: translucent fill, bright specular edge and a soft shadow. */
@Composable
fun Modifier.glass(shape: Shape = RoundedCornerShape(26.dp), elevation: Dp = 14.dp): Modifier {
    val g = LocalGlass.current
    return this
        .shadow(elevation, shape, clip = false, ambientColor = g.shadow.copy(alpha = 0.16f), spotColor = g.shadow.copy(alpha = 0.26f))
        .clip(shape)
        .background(Brush.verticalGradient(listOf(g.fillTop, g.fillBottom)))
        .border(1.dp, Brush.linearGradient(listOf(g.edgeStart, g.edgeEnd, g.edgeStart.copy(alpha = 0.35f))), shape)
}

/** Springy press feedback, like iOS controls. */
@Composable
fun Modifier.pressScale(source: InteractionSource, pressed: Float = 0.96f): Modifier {
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Fade and rise in; later items start a little later. Only the first few items are staggered. */
@Composable
fun Modifier.appear(index: Int = 0): Modifier {
    val motion = rememberMotionEnabled()
    val progress = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            delay(index.coerceIn(0, 8) * 55L)
            progress.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
        }
    }
    return graphicsLayer { alpha = progress.value; translationY = (1f - progress.value) * 26.dp.toPx() }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    val base = modifier.fillMaxWidth().animateContentSize().pressScale(source, 0.975f).glass()
    val tappable = if (onClick != null) base.clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick) else base
    Column(tappable.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

private val ButtonShape = RoundedCornerShape(18.dp)
private val ButtonPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)

@Composable
fun GButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    Button(onClick, modifier.pressScale(source), enabled, shape = ButtonShape, contentPadding = ButtonPadding, interactionSource = source,
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp), content = content)
}

/** Secondary action: a glass pill with a bright edge. */
@Composable
fun GOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val g = LocalGlass.current
    val source = remember { MutableInteractionSource() }
    Button(onClick, modifier.pressScale(source), enabled, shape = ButtonShape, contentPadding = ButtonPadding, interactionSource = source,
        colors = ButtonDefaults.buttonColors(containerColor = g.fillTop, contentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = g.fillBottom, disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)),
        border = BorderStroke(1.dp, g.edgeStart.copy(alpha = 0.7f)),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp), content = content)
}

@Composable
fun GTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    TextButton(onClick, modifier.pressScale(source), enabled, interactionSource = source, content = content)
}

@Composable
fun GTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
               singleLine: Boolean = false, minLines: Int = 1,
               visualTransformation: VisualTransformation = VisualTransformation.None,
               keyboardOptions: KeyboardOptions = KeyboardOptions.Default) {
    val g = LocalGlass.current
    OutlinedTextField(value, onValueChange, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = singleLine, minLines = minLines,
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = g.fillTop.copy(alpha = g.fillTop.alpha * 0.8f), unfocusedContainerColor = g.fillBottom,
            focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = g.edgeStart.copy(alpha = 0.6f)))
}

@Composable
fun GlassChip(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(text, color = color, style = MaterialTheme.typography.labelMedium,
        modifier = modifier.glass(CircleShape, 0.dp).padding(horizontal = 12.dp, vertical = 5.dp))
}

/** Round glass button holding a short glyph or icon. */
@Composable
fun GlassIconButton(onClick: () -> Unit, description: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(modifier.pressScale(source, 0.9f).glass(CircleShape, 6.dp)
        .semantics { contentDescription = description }
        .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = description, onClick = onClick)
        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) { content() }
}

class TabItem(val route: String, val label: String, val icon: ImageVector)

/** Floating glass tab bar; the selected tab grows a tinted pill and shows its label. */
@Composable
fun GlassTabBar(tabs: List<TabItem>, selected: String?, onSelect: (String) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
    Row(Modifier.navigationBarsPadding().padding(horizontal = 36.dp, vertical = 12.dp).fillMaxWidth().glass(CircleShape, 12.dp).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEach { tab ->
            val on = tab.route == selected
            val pill by animateColorAsState(if (on) primary.copy(alpha = 0.18f) else Color.Transparent, tween(260), label = "pill")
            val tint by animateColorAsState(if (on) primary else idle, tween(260), label = "tint")
            val source = remember { MutableInteractionSource() }
            Row(Modifier.weight(1f).semantics { this.selected = on }.pressScale(source, 0.94f).clip(CircleShape).background(pill)
                .clickable(interactionSource = source, indication = null, role = Role.Tab) { onSelect(tab.route) }.padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(tab.icon, contentDescription = if (on) null else tab.label, tint = tint)
                AnimatedVisibility(on, enter = fadeIn(tween(220)) + expandHorizontally(tween(260)), exit = fadeOut(tween(120)) + shrinkHorizontally(tween(200))) {
                    Text(tab.label, color = tint, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** Full-screen backdrop for content shown in its own window (such as the resume lock dialog). */
@Composable
fun GlassScreen(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        GlassBackdrop()
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) { content() }
    }
}

/** Glowing status orb for the focus screen: it breathes while focus is on and sits still otherwise. */
@Composable
fun FocusOrb(focusState: String, modifier: Modifier = Modifier) {
    val color by animateColorAsState(
        when (focusState) { "active" -> MaterialTheme.colorScheme.primary; "paused" -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.outline },
        tween(500), label = "orb-color")
    val motion = rememberMotionEnabled()
    val pulse: State<Float> = if (motion && focusState == "active") rememberInfiniteTransition(label = "orb")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    else remember { mutableFloatStateOf(0f) }
    Box(modifier.size(132.dp).drawBehind {
        val base = size.minDimension / 2f
        val glow = base * (0.78f + 0.22f * pulse.value)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.50f), Color.Transparent), radius = base), radius = glow)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.55f)), radius = base * 0.5f), radius = base * (0.40f + 0.04f * pulse.value))
        drawCircle(Color.White.copy(alpha = 0.55f), radius = base * (0.40f + 0.04f * pulse.value), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
    })
}

/** Accumulated scroll, shared with the backdrop so the glows drift at a slower rate than the content. */
class BackdropScroll { var offset by mutableFloatStateOf(0f) }
val LocalBackdropScroll = staticCompositionLocalOf<BackdropScroll?> { null }

@Composable
fun Modifier.trackBackdropScroll(scroll: BackdropScroll): Modifier {
    val connection = remember(scroll) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                scroll.offset = (scroll.offset + consumed.y * 0.22f).coerceIn(-460f, 460f)
                return Offset.Zero
            }
        }
    }
    return nestedScroll(connection)
}

/** The first list item moves at about half speed and fades as it leaves, so the header seems to sit behind the cards. */
fun Modifier.heroParallax(state: LazyListState): Modifier = graphicsLayer {
    val offset = if (state.firstVisibleItemIndex == 0) state.firstVisibleItemScrollOffset.toFloat() else 600f
    translationY = offset * 0.45f
    alpha = 1f - (offset / 520f).coerceIn(0f, 0.75f)
}

/** Amber is reserved for alerts; classes and content use the teal brand colour. */
@Composable
fun alertColor(): Color = if (LocalGlass.current.dark) Color(0xFFF2C46B) else Color(0xFFB45F06)

@Composable
fun SectionLabel(text: String, color: Color, icon: ImageVector, modifier: Modifier = Modifier, count: Int? = null) {
    val ink = if (LocalGlass.current.dark) Color(0xFF1D1500) else Color.White
    Row(modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text.uppercase(), color = color, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold))
        if (count != null && count > 0) Text(count.toString(), color = ink, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.background(color, CircleShape).padding(horizontal = 8.dp, vertical = 1.dp))
    }
}

/** An alert: amber edge, bell, and an optional action. Looks different from class cards on purpose. */
@Composable
fun AlertCard(title: String, message: String? = null, actionLabel: String? = null, modifier: Modifier = Modifier, onAction: (() -> Unit)? = null) {
    val amber = alertColor()
    val ink = if (LocalGlass.current.dark) Color(0xFF1D1500) else Color.White
    Row(modifier.fillMaxWidth().animateContentSize().glass(RoundedCornerShape(22.dp), 8.dp).background(amber.copy(alpha = 0.13f)).height(IntrinsicSize.Min)) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(amber))
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Notifications, contentDescription = null, tint = amber, modifier = Modifier.size(18.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                val source = remember { MutableInteractionSource() }
                Button(onAction, Modifier.padding(top = 4.dp).pressScale(source), shape = ButtonShape, contentPadding = ButtonPadding, interactionSource = source,
                    colors = ButtonDefaults.buttonColors(containerColor = amber, contentColor = ink),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp)) { Text(actionLabel) }
            }
        }
    }
}
