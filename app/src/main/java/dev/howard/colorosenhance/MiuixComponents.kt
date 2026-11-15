package dev.howard.colorosenhance

import android.animation.ValueAnimator
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import dev.howard.colorosenhance.ui.FeatureDemoView
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SectionHeading(title: String) {
    Text(title, Modifier.padding(start = 12.dp, top = 12.dp, bottom = 8.dp).semantics { heading() },
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun FeatureGlyph(key: String, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    Box(modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(colors.primary.copy(alpha = .11f)), contentAlignment = Alignment.Center) {
        Image(SettingsIcons.icon(key), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(colors.primary))
    }
}

@Composable
internal fun DemoCard(page: SettingsLayout.Page, values: Map<String, Any>) {
    val colors=MiuixTheme.colorScheme
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("模拟预览", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("点按重播", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        AndroidView(
            factory = { context -> FeatureDemoView(context, page.demo()).apply { presentation(page.route()); values(values) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 175.dp, max = 230.dp),
            update = {
                it.presentation(page.route()); it.values(values)
                it.palette(colors.onSurface.toArgb(), colors.onSurfaceVariantSummary.toArgb(), colors.primary.toArgb(), colors.surface.toArgb())
            },
            onRelease = { it.stop() }
        )
    }
}

@Composable
internal fun GlassNavigation(selected: Int, glass: Boolean, backdrop: Backdrop, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    val tint = colors.surfaceContainer.copy(alpha = .72f)
    val shape = RoundedCornerShape(30.dp)
    // Backdrop records existing content updates; it does not run an idle animation loop.
    val effect = if (glass && LocalView.current.isHardwareAccelerated) Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = { vibrancy(); blur(8.dp.toPx()); lens(18.dp.toPx(), 22.dp.toPx()) },
        onDrawSurface = { drawRect(tint) }
    ) else Modifier.background(colors.surfaceContainer, shape)
    Box(modifier.widthIn(max = 480.dp).fillMaxWidth().clip(shape).then(effect).padding(horizontal = 8.dp, vertical = 3.dp)) {
        NavigationBar(color = Color.Transparent, showDivider = false, defaultWindowInsetsPadding = false) {
            listOf("首页" to "home", "功能" to "features", "管理" to "manage").forEachIndexed { index, (label, key) ->
                NavigationBarItem(selected == index, { onSelect(index) }, SettingsIcons.icon(key), label)
            }
        }
    }
}

internal object SettingsIcons {
    private val cache = mutableMapOf<String, ImageVector>()
    fun icon(name: String): ImageVector = cache.getOrPut(name) {
        ImageVector.Builder("enhance_$name", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                when (name) {
                    "home" -> { moveTo(3f, 10f); lineTo(12f, 3f); lineTo(21f, 10f); moveTo(5f, 9f); lineTo(5f, 21f); lineTo(10f, 21f); lineTo(10f, 14f); lineTo(14f, 14f); lineTo(14f, 21f); lineTo(19f, 21f); lineTo(19f, 9f) }
                    "cloud" -> { moveTo(7f, 6f); lineTo(17f, 6f); curveTo(24f, 6f, 24f, 18f, 17f, 18f); lineTo(7f, 18f); curveTo(0f, 18f, 0f, 6f, 7f, 6f); moveTo(14f, 10f); lineTo(14f, 14f); moveTo(17f, 9f); lineTo(17f, 15f); moveTo(7f, 11f); lineTo(8f, 11f) }
                    "battery" -> { moveTo(4f, 7f); lineTo(18f, 7f); lineTo(18f, 17f); lineTo(4f, 17f); close(); moveTo(21f, 10f); lineTo(21f, 14f); moveTo(8f, 10f); lineTo(8f, 14f); moveTo(12f, 10f); lineTo(12f, 14f) }
                    "icons" -> { moveTo(4f, 19f); lineTo(4f, 13f); moveTo(9f, 19f); lineTo(9f, 9f); moveTo(14f, 19f); lineTo(14f, 5f); moveTo(20f, 19f); lineTo(20f, 2f) }
                    "aod" -> { ring(12f, 12f, 9f); moveTo(12f, 6f); lineTo(12f, 12f); lineTo(16f, 14f) }
                    "sim" -> { moveTo(8f, 3f); lineTo(19f, 3f); lineTo(19f, 21f); lineTo(5f, 21f); lineTo(5f, 6f); close(); moveTo(9f, 10f); lineTo(15f, 10f); lineTo(15f, 17f); lineTo(9f, 17f); close(); moveTo(12f, 10f); lineTo(12f, 17f) }
                    "installer" -> { moveTo(12f, 3f); lineTo(12f, 14f); moveTo(8f, 10f); lineTo(12f, 14f); lineTo(16f, 10f); moveTo(4f, 15f); lineTo(4f, 21f); lineTo(20f, 21f); lineTo(20f, 15f) }
                    "back" -> { moveTo(15f, 4f); lineTo(7f, 12f); lineTo(15f, 20f) }
                    "manage" -> { ring(12f, 12f, 3f); ring(12f, 12f, 8f); moveTo(12f, 1f); lineTo(12f, 4f); moveTo(12f, 20f); lineTo(12f, 23f); moveTo(1f, 12f); lineTo(4f, 12f); moveTo(20f, 12f); lineTo(23f, 12f) }
                    else -> { moveTo(3f, 3f); lineTo(9f, 3f); lineTo(9f, 9f); lineTo(3f, 9f); close(); moveTo(15f, 3f); lineTo(21f, 3f); lineTo(21f, 9f); lineTo(15f, 9f); close(); moveTo(3f, 15f); lineTo(9f, 15f); lineTo(9f, 21f); lineTo(3f, 21f); close(); moveTo(15f, 15f); lineTo(21f, 15f); lineTo(21f, 21f); lineTo(15f, 21f); close() }
                }
            }
        }.build()
    }
    private fun PathBuilder.ring(x: Float, y: Float, r: Float) {
        val k = r * .5523f
        moveTo(x + r, y); curveTo(x + r, y + k, x + k, y + r, x, y + r)
        curveTo(x - k, y + r, x - r, y + k, x - r, y)
        curveTo(x - r, y - k, x - k, y - r, x, y - r)
        curveTo(x + k, y - r, x + r, y - k, x + r, y); close()
    }
}
