package io.github.tohrutodo.tohru.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskEditor

// Quick edit card: title and description only. Everything else lives on the
// detail screen.
@Composable
fun TaskEditSheet(
    task: Task,
    onSave: (Task) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable(task.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(task.title, TextRange(task.title.length)))
    }
    var body by rememberSaveable(task.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(task.body, TextRange(task.body.length)))
    }
    val titleFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun flush() {
        if (TaskEditor.needsSave(task, title.text, body.text)) {
            onSave(TaskEditor.draft(task, title.text, body.text))
        }
    }
    val latestFlush by rememberUpdatedState(::flush)

    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        title = title.copy(selection = TextRange(title.text.length))
        titleFocus.requestFocus()
        keyboard?.show()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { latestFlush() }

    InstantSheet(onDismissRequest = {
        flush()
        onDismiss()
    }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.ui_title_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    flush()
                    onDismiss()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(titleFocus),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text(stringResource(R.string.ui_description_markdown)) },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
