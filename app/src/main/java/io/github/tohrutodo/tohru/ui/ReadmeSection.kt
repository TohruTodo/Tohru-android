package io.github.tohrutodo.tohru.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.task.ReadmeDoc
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskFile

// A readme node follows the folder path: [] is the repo root, ["tohru"] a
// group, ["tohru", "sync"] a topic. Deeper paths are kept for later roots.
data class ReadmeNode(
    val segments: List<String>,
    val label: String,
    val doc: ReadmeDoc?,
    val children: List<ReadmeNode> = emptyList(),
)

internal fun buildReadmeTree(
    readmes: List<ReadmeDoc>,
    tasks: List<Task>,
    query: String = "",
): List<ReadmeNode> {
    val docs = readmes.associateBy { it.segments }
    val labels = mutableMapOf<List<String>, String>()

    fun labelFor(segments: List<String>, fallback: String): String =
        labels.getOrPut(segments) { fallback }

    tasks.forEach { task ->
        val parsed = task.parsed
        val prefix = parsed.prefix ?: return@forEach
        val group = listOf(TaskFile.slug(prefix))
        labelFor(group, prefix)
        parsed.topic?.let { topic ->
            labelFor(group + TaskFile.slug(topic), topic)
        }
    }

    val root = linkedMapOf<List<String>, LinkedHashSet<List<String>>>()
    root.getOrPut(emptyList()) { linkedSetOf() }

    fun ensure(segments: List<String>) {
        if (segments.isEmpty()) return
        for (depth in 1..segments.size) {
            val parent = segments.take(depth - 1)
            root.getOrPut(parent) { linkedSetOf() }.add(segments.take(depth))
            if (depth == segments.size) root.getOrPut(segments) { linkedSetOf() }
        }
    }

    docs.keys.forEach(::ensure)
    tasks.forEach { task ->
        val parsed = task.parsed
        val prefix = parsed.prefix ?: return@forEach
        ensure(listOf(TaskFile.slug(prefix)))
        parsed.topic?.let { ensure(listOf(TaskFile.slug(prefix), TaskFile.slug(it))) }
    }

    fun build(segments: List<String>): ReadmeNode {
        val fallback = segments.lastOrNull()?.replace('_', ' ').orEmpty()
        val label = if (segments.isEmpty()) "Repo" else labelFor(segments, fallback)
        val children = root[segments].orEmpty()
            .map(::build)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        return ReadmeNode(segments, label, docs[segments], children)
    }

    val tree = build(emptyList())
    return filterReadme(tree, query)
}

private fun filterReadme(node: ReadmeNode, query: String): List<ReadmeNode> {
    val needle = query.trim()
    if (needle.isEmpty()) return listOf(node)
    val selfMatch = node.label.contains(needle, ignoreCase = true) ||
        node.doc?.matches(needle) == true
    val children = if (selfMatch) {
        node.children
    } else {
        node.children.flatMap { filterReadme(it, needle) }
    }
    return if (selfMatch || children.isNotEmpty()) {
        listOf(node.copy(children = children))
    } else {
        emptyList()
    }
}

@Composable
fun ReadmeSection(
    nodes: List<ReadmeNode>,
    query: String,
    onOpen: (List<String>) -> Unit,
) {
    if (nodes.isEmpty()) return
    if (query.isNotBlank()) {
        val matches = flattenDocs(nodes).filter { it.doc?.matches(query) == true }
        if (matches.isEmpty()) return
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_note),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.readme_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    matches.size.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                matches.forEach { node ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(node.segments) }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_note),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            searchLabel(node),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TohruBadge(stringResource(R.string.readme_badge))
                    }
                }
            }
        }
        return
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val open = expanded
    val count = readmeCount(nodes)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_note),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.readme_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (open) 90f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = open) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                nodes.forEach { node ->
                    ReadmeNodeRows(node, depth = 0, onOpen = onOpen)
                }
            }
        }
    }
}

@Composable
private fun ReadmeNodeRows(node: ReadmeNode, depth: Int, onOpen: (List<String>) -> Unit) {
    val hasDoc = node.doc != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(node.segments) }
            .padding(start = (16 + depth * 16).dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(
                if (hasDoc) R.drawable.ic_note else R.drawable.ic_add,
            ),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (hasDoc) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            node.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hasDoc) {
            TohruBadge(stringResource(R.string.readme_badge))
        } else {
            Text(
                stringResource(R.string.readme_add),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    node.children.forEach { child ->
        ReadmeNodeRows(child, depth + 1, onOpen)
    }
}

private fun readmeCount(nodes: List<ReadmeNode>): Int =
    nodes.sumOf { (if (it.doc != null) 1 else 0) + readmeCount(it.children) }

private fun flattenDocs(nodes: List<ReadmeNode>): List<ReadmeNode> =
    nodes.flatMap { node -> listOf(node) + flattenDocs(node.children) }

private fun searchLabel(node: ReadmeNode): String =
    if (node.segments.isEmpty()) "Repo" else node.segments.joinToString(" / ")
