package io.github.tohrutodo.tohru.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownExtendedSpans
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownExtendedSpans
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import io.github.tohrutodo.tohru.md.MarkdownDocument
import io.github.tohrutodo.tohru.md.MarkdownSection
import io.github.tohrutodo.tohru.task.TaskLinks

@Composable
fun tohruMarkdownColors(): MarkdownColors = markdownColor()

@Composable
fun tohruMarkdownTypography(): MarkdownTypography {
    val body = MaterialTheme.typography.bodyLarge
    fun heading(level: Int) = body.copy(
        fontSize = MarkdownDocument.headingSize(level),
        lineHeight = MarkdownDocument.headingLineHeight(level),
        fontWeight = FontWeight.Bold,
    )
    return markdownTypography(
        h1 = heading(1),
        h2 = heading(2),
        h3 = heading(3),
        h4 = heading(4),
        h5 = heading(5),
        h6 = heading(6),
        text = body,
        paragraph = body,
        list = body,
        bullet = body,
        ordered = body,
        textLink = TextLinkStyles(
            style = SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                textDecoration = TextDecoration.Underline,
            ),
        ),
    )
}

// Inline code carries a SpanStyle background, which renders as hard corners.
// The rounded painter turns every background span into a round rect; code
// fences keep their own block surface.
@Composable
fun tohruMarkdownExtendedSpans(): MarkdownExtendedSpans = markdownExtendedSpans {
    ExtendedSpans(
        RoundedCornerSpanPainter(
            cornerRadius = 5.sp,
            padding = RoundedCornerSpanPainter.TextPaddingValues(horizontal = 0.sp, vertical = 1.sp),
            topMargin = 1.sp,
            bottomMargin = 1.sp,
        ),
    )
}

@Composable
fun rememberTohruMarkdownComponents(
    onTaskLink: ((String) -> Unit)?,
    taskExists: ((String) -> Boolean)?,
    onToggleChecklist: ((ASTNode, String) -> Unit)?,
): MarkdownComponents {
    val uriHandler = LocalUriHandler.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val highlightText = MaterialTheme.colorScheme.onSecondaryContainer
    val highlightBackground = MaterialTheme.colorScheme.secondaryContainer
    val inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val inlineCodeText = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val settings = remember(
        onTaskLink,
        uriHandler,
        highlightText,
        highlightBackground,
        inlineCodeBackground,
        inlineCodeText,
        linkColor,
    ) {
        val listener = LinkInteractionListener { link ->
            val url = (link as? LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
            val target = TaskLinks.normalize(url)
            when {
                target != null && onTaskLink != null -> onTaskLink(target)
                target == null -> runCatching { uriHandler.openUri(url) }
                else -> Unit
            }
        }
        DefaultAnnotatorSettings(
            linkTextSpanStyle = TextLinkStyles(
                style = SpanStyle(
                    color = linkColor,
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline,
                ),
            ),
            codeSpanStyle = SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = inlineCodeBackground,
                color = inlineCodeText,
            ),
            annotator = markdownAnnotator(),
            referenceLinkHandler = null,
            linkInteractionListener = listener,
            highlightSpanStyle = SpanStyle(background = highlightBackground, color = highlightText),
        )
    }
    return remember(settings, taskExists, onToggleChecklist, muted) {
        markdownComponents(
            paragraph = { model ->
                TaskText(model, model.typography.paragraph, null, settings, taskExists, muted)
            },
            heading1 = { model ->
                Heading(model, 1, MarkdownTokenTypes.ATX_CONTENT, settings, taskExists, muted)
            },
            heading2 = { model ->
                Heading(model, 2, MarkdownTokenTypes.ATX_CONTENT, settings, taskExists, muted)
            },
            heading3 = { model ->
                Heading(model, 3, MarkdownTokenTypes.ATX_CONTENT, settings, taskExists, muted)
            },
            heading4 = { model ->
                Heading(model, 4, MarkdownTokenTypes.ATX_CONTENT, settings, taskExists, muted)
            },
            heading5 = { model ->
                Heading(model, 5, MarkdownTokenTypes.ATX_CONTENT, settings, taskExists, muted)
            },
            heading6 = { model ->
                Heading(model, 6, MarkdownTokenTypes.ATX_CONTENT, settings, taskExists, muted)
            },
            setextHeading1 = { model ->
                Heading(model, 1, MarkdownTokenTypes.SETEXT_CONTENT, settings, taskExists, muted)
            },
            setextHeading2 = { model ->
                Heading(model, 2, MarkdownTokenTypes.SETEXT_CONTENT, settings, taskExists, muted)
            },
            checkbox = { model -> TaskCheckbox(model, onToggleChecklist) },
        )
    }
}

@Composable
fun MarkdownSectionView(
    section: MarkdownSection,
    content: String,
    components: MarkdownComponents,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        section.nodes.forEach { node ->
            MarkdownElement(node, components, content, includeSpacer = false)
        }
    }
}

@Composable
private fun Heading(
    model: MarkdownComponentModel,
    level: Int,
    childType: IElementType,
    settings: AnnotatorSettings,
    taskExists: ((String) -> Boolean)?,
    muted: Color,
) {
    TaskText(model, model.typography.heading(level), childType, settings, taskExists, muted)
}

private fun MarkdownTypography.heading(level: Int): TextStyle = when (level) {
    1 -> h1
    2 -> h2
    3 -> h3
    4 -> h4
    5 -> h5
    else -> h6
}

@Composable
private fun TaskText(
    model: MarkdownComponentModel,
    style: TextStyle,
    childType: IElementType?,
    settings: AnnotatorSettings,
    taskExists: ((String) -> Boolean)?,
    muted: Color,
) {
    val checked = MarkdownDocument.checkedListItem(model.node, model.content)
    val styled = remember(model.content, model.node, style, childType, settings, taskExists, checked, muted) {
        val node = childType?.let(model.node::findChildOfType) ?: model.node
        buildAnnotatedString {
            buildMarkdownAnnotatedString(model.content, node, settings)
        }
            .withTaskLabels(taskExists)
            .let { if (checked) it.struckThrough(muted) else it }
    }
    MarkdownText(content = styled, node = model.node, style = style, sourceContent = model.content)
}

@Composable
private fun TaskCheckbox(
    model: MarkdownComponentModel,
    onToggle: ((ASTNode, String) -> Unit)?,
) {
    val checked = MarkdownDocument.checkboxChecked(model.node, model.content)
    val density = LocalDensity.current
    val lineHeight = model.typography.text.lineHeight
    // The marker column sizes to the checkbox. A 32dp box matches the bullet
    // indent instead of pushing checklist text 16dp further right than a
    // bullet; the visual box is 20dp, so it is lifted by the difference
    // between the column center and half the text line height.
    val lift = if (lineHeight.type == TextUnitType.Sp) {
        with(density) { lineHeight.toDp() / 2 } - 16.dp
    } else {
        (-4).dp
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Checkbox(
            checked = checked,
            onCheckedChange = onToggle?.let { toggle -> { toggle(model.node, model.content) } },
            modifier = Modifier
                .offset(y = lift)
                .size(32.dp)
                .padding(end = 4.dp)
                .semantics { if (onToggle == null) role = Role.Checkbox },
        )
    }
}

// Renders `file not found` for links that point at a missing task file while
// keeping the destination and the link annotation untouched.
internal fun AnnotatedString.withTaskLabels(
    taskExists: ((String) -> Boolean)?,
): AnnotatedString {
    if (taskExists == null) return this
    val links = getLinkAnnotations(0, length).sortedBy { it.start }
    if (links.isEmpty()) return this
    val source = this
    val end = source.length
    return buildAnnotatedString {
        var cursor = 0
        links.forEach { range ->
            if (range.start < cursor) return@forEach
            append(source.subSequence(cursor, range.start).withoutLinks())
            val target = (range.item as? LinkAnnotation.Url)?.url?.let(TaskLinks::normalize)
            val label = if (target != null && !taskExists(target)) {
                AnnotatedString("file not found")
            } else {
                source.subSequence(range.start, range.end).withoutLinks()
            }
            withLink(range.item) { append(label) }
            cursor = range.end
        }
        append(source.subSequence(cursor, end).withoutLinks())
    }
}

private fun AnnotatedString.withoutLinks(): AnnotatedString =
    AnnotatedString(text, spanStyles, paragraphStyles)

private fun AnnotatedString.struckThrough(color: Color): AnnotatedString =
    AnnotatedString(
        text = text,
        spanStyles = spanStyles + AnnotatedString.Range(
            SpanStyle(textDecoration = TextDecoration.LineThrough, color = color),
            0,
            length,
        ),
        paragraphStyles = paragraphStyles,
    )
