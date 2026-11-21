package dev.howard.colorosenhance.updates

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ReleaseNotesText(release: ReleaseInfo, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val annotated = remember(context, release, colors.primary) {
        val linkStyle = TextLinkStyles(style = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline))
        formatReleaseNotes(release, linkStyle) { UpdateLinks.browser(context, it) }
    }
    BasicText(annotated, modifier, style = TextStyle(color = colors.onSurfaceVariantSummary, fontSize = 14.sp, lineHeight = 21.sp))
}

internal fun formatReleaseNotes(
    release: ReleaseInfo,
    linkStyles: TextLinkStyles? = null,
    openLink: (String) -> Unit = {},
): AnnotatedString {
    if (release.notesHtml().isBlank()) return AnnotatedString(release.notes())
    // Render GitHub's Markdown fragment as native text. Compose has no web or image loading here.
    val html = release.notesHtml().replace("<code>", "<tt>").replace("</code>", "</tt>")
    val listener = LinkInteractionListener { annotation ->
        if (annotation is LinkAnnotation.Url) release.notesLink(annotation.url)?.let(openLink)
    }
    val formatted = runCatching { AnnotatedString.fromHtml(html, linkStyles, listener) }.getOrNull()
    return formatted?.takeIf { it.text.isNotBlank() } ?: AnnotatedString(release.notes())
}
