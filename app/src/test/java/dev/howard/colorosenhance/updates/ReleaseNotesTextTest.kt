package dev.howard.colorosenhance.updates

import android.app.Application
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ReleaseNotesTextTest {
    private val page = ReleaseInfo.MODULE_URL + "/releases/tag/73-1.32.1"
    private fun release(html: String, plain: String = "### 原始说明") =
        ReleaseInfo(73, "1.32.1", plain, html, page, "https://github.com/download.apk", false)

    @Test fun displaysHeadingsListsEmphasisAndInlineCodeAsStyledText() {
        val result = formatReleaseNotes(release("""
            <h3>流体云</h3>
            <ul>
            <li><strong>修复通知</strong></li>
            <li>查看 <code>diagnostics.txt</code></li>
            </ul>
        """.trimIndent()))
        assertFalse(result.text.contains("<li>"))
        assertFalse(result.text.contains("原始说明"))
        assertTrue(result.text.contains("流体云"))
        assertTrue(result.text.lines().any { it.contains("修复通知") })
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(result.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test fun opensOnlyHttpsNoteLinksAndPreservesPlainFallback() {
        val opened = mutableListOf<String>()
        val result = formatReleaseNotes(release("""
            <p><a href="/Howard-Tse/ColorOS-Enhance/issues">反馈</a>
            <a href="intent://unsafe/#Intent;end">其他链接</a></p>
        """.trimIndent()), openLink = { opened.add(it) })
        result.getLinkAnnotations(0, result.length).forEach {
            val link = it.item as LinkAnnotation.Url
            link.linkInteractionListener?.onClick(link)
        }
        assertEquals(listOf("https://github.com/Howard-Tse/ColorOS-Enhance/issues"), opened)
        assertEquals("### 原始说明", formatReleaseNotes(release("")).text)
        assertEquals("### 原始说明", formatReleaseNotes(release("<p></p>")).text)
    }
}
