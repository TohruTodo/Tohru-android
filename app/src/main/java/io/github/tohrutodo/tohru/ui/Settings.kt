package io.github.tohrutodo.tohru.ui

import android.annotation.SuppressLint

import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.tohrutodo.tohru.AppLanguage
import io.github.tohrutodo.tohru.AppLocale
import io.github.tohrutodo.tohru.findActivity
import io.github.tohrutodo.tohru.debug.Diagnostics
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.security.CredentialVault
import io.github.tohrutodo.tohru.sync.GitMaintenance
import io.github.tohrutodo.tohru.sync.SyncSettings
import io.github.tohrutodo.tohru.task.TaskSettings
import java.io.File

@SuppressLint("UseKtx")
@Composable
fun SettingsScreen(
    vault: CredentialVault,
    dynamic: Boolean,
    onDynamicChange: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    haptics: Boolean,
    onHapticsChange: (Boolean) -> Unit,
    snackbar: SnackbarHostState,
    onResetOrder: () -> Unit,
    inboxPrefixes: Set<String>,
    notePrefixes: Set<String>,
    onTaskConfigChange: (Set<String>, Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    val prefs = remember {
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    }
    val sshDir = remember { SyncSettings.sshDir(context.filesDir) }
    var remote by remember { mutableStateOf(prefs.getString(SyncSettings.KEY_REMOTE, "").orEmpty()) }
    var username by remember { mutableStateOf(prefs.getString(SyncSettings.KEY_USER, "").orEmpty()) }
    var authorName by remember { mutableStateOf(SyncSettings.readAuthorName(prefs)) }
    var authorEmail by remember { mutableStateOf(SyncSettings.readAuthorEmail(prefs)) }
    val maintenance = remember { SyncSettings.readMaintenance(prefs) }
    var recoveryKeep by remember { mutableStateOf(maintenance.recoveryKeep.toString()) }
    var gcEnabled by remember { mutableStateOf(maintenance.gcEnabled) }
    var gcDays by remember { mutableStateOf(maintenance.gcIntervalDays.toString()) }
    var exportKeep by remember { mutableStateOf(maintenance.exportKeep.toString()) }
    var token by remember { mutableStateOf(vault.get("git-token").orEmpty()) }
    var sshKey by remember { mutableStateOf("") }
    var sshPassphrase by remember { mutableStateOf(vault.get("ssh-passphrase").orEmpty()) }
    var hasSshKey by remember { mutableStateOf(SyncSettings.hasSshKey(sshDir)) }
    var inboxDraft by remember { mutableStateOf(TaskSettings.encode(inboxPrefixes)) }
    var noteDraft by remember { mutableStateOf(TaskSettings.encode(notePrefixes)) }
    var repoDraft by remember { mutableStateOf(SyncSettings.readRepoPath(prefs, context.filesDir)) }
    var repoError by remember { mutableStateOf<String?>(null) }
    var keepOverlay by remember { mutableStateOf(SyncSettings.readKeepOverlay(prefs)) }
    var language by remember { mutableStateOf(AppLanguage.fromPrefs(prefs)) }
    var repoHint by remember { mutableStateOf<String?>(null) }
    val uiScope = rememberCoroutineScope()
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    fun changeLanguage(next: AppLanguage) {
        language = next
        AppLocale.apply(context, next)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            context.findActivity()?.recreate()
        }
    }

    fun saveConfig() {
        saveJob = null
        SyncSettings.save(
            prefs,
            vault,
            sshDir,
            remote,
            username,
            token,
            sshKey,
            sshPassphrase,
            authorName,
            authorEmail,
        )
        val inbox = TaskSettings.parseList(inboxDraft)
        val note = TaskSettings.parseList(noteDraft)
        SyncSettings.saveTaskPrefixes(prefs, inbox, note)
        SyncSettings.saveKeepOverlay(prefs, keepOverlay)
        SyncSettings.saveMaintenance(
            prefs,
            GitMaintenance(
                recoveryKeep = recoveryKeep.toIntOrNull() ?: maintenance.recoveryKeep,
                gcEnabled = gcEnabled,
                gcIntervalDays = gcDays.toIntOrNull() ?: maintenance.gcIntervalDays,
                exportKeep = exportKeep.toIntOrNull() ?: maintenance.exportKeep,
            ),
        )
        if (inbox != inboxPrefixes || note != notePrefixes) onTaskConfigChange(inbox, note)
        sshKey = ""
        hasSshKey = SyncSettings.hasSshKey(sshDir)
    }

    fun scheduleSave() {
        saveJob?.cancel()
        saveJob = uiScope.launch {
            delay(500)
            saveConfig()
        }
    }

    fun flushSave() {
        saveJob?.cancel()
        saveConfig()
    }

    fun saveRepo(path: String = repoDraft.trim()) {
        val repo = File(path)
        val valid = path.isNotEmpty() && (
            repo.isDirectory ||
                (!repo.exists() && repo.parentFile?.let { it.isDirectory && it.canWrite() } == true)
            )
        if (!valid) {
            repoError = resources.getString(R.string.settings_repo_invalid)
            repoHint = null
            return
        }
        val previous = SyncSettings.readRepoPath(prefs, context.filesDir)
        SyncSettings.saveRepoPath(prefs, path)
        repoDraft = path
        repoError = null
        repoHint = if (previous == path) null else resources.getString(R.string.settings_repo_restart)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        flushSave()
        saveRepo()
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = uri?.let { treeUriPath(context, it) }
        if (path != null) {
            repoDraft = path
            repoError = null
            saveRepo(path)
        } else {
            repoError = resources.getString(R.string.settings_repo_pick_failed)
        }
    }
    var appearanceExpanded by rememberSaveable { mutableStateOf(true) }
    var taskExpanded by rememberSaveable { mutableStateOf(true) }
    var syncExpanded by rememberSaveable { mutableStateOf(true) }
    var debugExpanded by rememberSaveable { mutableStateOf(true) }
    var aboutExpanded by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TohruTopBar(
                title = stringResource(R.string.settings_title),
                onBack = {
                    flushSave()
                    saveRepo()
                    onBack()
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 32.dp),
        ) {
            SettingsSection(
                title = stringResource(R.string.settings_section_appearance),
                icon = R.drawable.ic_palette,
                showTopDivider = false,
                expanded = appearanceExpanded,
                onExpandChange = { appearanceExpanded = it },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_dynamic_color), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = dynamic, onCheckedChange = onDynamicChange)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(stringResource(R.string.language_title), style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LanguageChip(stringResource(R.string.language_system), language == AppLanguage.SYSTEM) {
                        changeLanguage(AppLanguage.SYSTEM)
                    }
                    LanguageChip(stringResource(R.string.language_vietnamese), language == AppLanguage.VIETNAMESE) {
                        changeLanguage(AppLanguage.VIETNAMESE)
                    }
                    LanguageChip(stringResource(R.string.language_english), language == AppLanguage.ENGLISH) {
                        changeLanguage(AppLanguage.ENGLISH)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(stringResource(R.string.settings_display_mode), style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
                ThemeModePicker(mode = themeMode, onModeChange = onThemeModeChange)
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_haptics), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = haptics, onCheckedChange = onHapticsChange)
                }
            }

            SettingsSection(
                title = stringResource(R.string.settings_section_tasks),
                icon = R.drawable.ic_task,
                expanded = taskExpanded,
                onExpandChange = { taskExpanded = it },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = noteDraft,
                        onValueChange = { noteDraft = it; scheduleSave() },
                        label = { Text(stringResource(R.string.settings_note_prefix_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.settings_note_prefix_support),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = inboxDraft,
                        onValueChange = { inboxDraft = it; scheduleSave() },
                        label = { Text(stringResource(R.string.settings_inbox_prefix_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.settings_inbox_prefix_support),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_keep_overlay), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.settings_keep_overlay_support),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = keepOverlay, onCheckedChange = { keepOverlay = it })
                    }
                    SettingsGroup(stringResource(R.string.settings_group_sort))
                    TextButton(
                        onClick = onResetOrder,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_reset_order))
                    }
                    Text(
                        text = stringResource(R.string.settings_reset_order_support),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsSection(
                title = stringResource(R.string.settings_section_sync),
                icon = R.drawable.ic_sync,
                expanded = syncExpanded,
                onExpandChange = { syncExpanded = it },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsGroup(stringResource(R.string.settings_group_connection))
                    OutlinedTextField(
                        value = remote,
                        onValueChange = { remote = it; scheduleSave() },
                        label = { Text(stringResource(R.string.settings_remote_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; scheduleSave() },
                        label = { Text(stringResource(R.string.settings_username_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it; scheduleSave() },
                        label = { Text(stringResource(R.string.settings_token_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    SettingsGroup(stringResource(R.string.settings_group_ssh))
                    OutlinedTextField(
                        value = sshKey,
                        onValueChange = { sshKey = it },
                        label = { Text(stringResource(R.string.settings_ssh_key_label)) },
                        minLines = 2,
                        maxLines = 4,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { state ->
                                if (!state.isFocused && sshKey.isNotBlank()) flushSave()
                            },
                    )
                    OutlinedTextField(
                        value = sshPassphrase,
                        onValueChange = { sshPassphrase = it; scheduleSave() },
                        label = { Text(stringResource(R.string.settings_ssh_passphrase_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = if (hasSshKey) stringResource(R.string.settings_ssh_saved) else stringResource(R.string.settings_ssh_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    SettingsGroup(stringResource(R.string.settings_group_author))
                    OutlinedTextField(
                        value = authorName,
                        onValueChange = { authorName = it; scheduleSave() },
                        label = { Text(stringResource(R.string.settings_author_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = authorEmail,
                        onValueChange = { authorEmail = it; scheduleSave() },
                        label = { Text(stringResource(R.string.settings_author_email_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    SettingsGroup(stringResource(R.string.settings_group_repository))
                    OutlinedTextField(
                        value = repoDraft,
                        onValueChange = {
                            repoDraft = it
                            repoError = null
                            repoHint = null
                        },
                        label = { Text(stringResource(R.string.settings_repo_path_label)) },
                        singleLine = true,
                        isError = repoError != null,
                        supportingText = (repoError ?: repoHint)?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { saveRepo() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { state -> if (!state.isFocused) saveRepo() },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { treePicker.launch(null) }) { Text(stringResource(R.string.settings_pick_folder)) }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            !Environment.isExternalStorageManager()
                        ) {
                            TextButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                Uri.parse("package:${context.packageName}"),
                                            ),
                                        )
                                    }
                                },
                            ) { Text(stringResource(R.string.settings_grant_files)) }
                        }
                    }
                    Text(
                        text = stringResource(R.string.settings_repo_support),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    SettingsGroup(stringResource(R.string.settings_group_maintenance))
                    OutlinedTextField(
                        value = recoveryKeep,
                        onValueChange = { recoveryKeep = it; scheduleSave() },
                        label = { Text(stringResource(R.string.settings_recovery_keep_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_gc_auto), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = gcEnabled, onCheckedChange = { gcEnabled = it; scheduleSave() })
                    }
                    OutlinedTextField(
                        value = gcDays,
                        onValueChange = { gcDays = it; scheduleSave() },
                        label = { Text(stringResource(R.string.settings_gc_interval_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = exportKeep,
                        onValueChange = { exportKeep = it; scheduleSave() },
                        label = { Text(stringResource(R.string.settings_export_keep_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SettingsSection(
                title = stringResource(R.string.settings_section_debug),
                icon = R.drawable.ic_bug_report,
                expanded = debugExpanded,
                onExpandChange = { debugExpanded = it },
            ) {
                TextButton(
                    enabled = !exporting,
                    onClick = {
                        exporting = true
                        uiScope.launch {
                            try {
                                flushSave()
                                val file = withContext(Dispatchers.IO) {
                                    Diagnostics.export(context, prefs)
                                }
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(send, resources.getString(R.string.settings_share_log_title)))
                            } catch (error: Exception) {
                                Log.e("TohruSettings", "diagnostics export failed", error)
                                snackbar.showSnackbar(resources.getString(R.string.settings_export_log_failed))
                            } finally {
                                exporting = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.settings_export_log)) }
            }

            SettingsSection(
                title = stringResource(R.string.settings_section_about),
                icon = R.drawable.ic_info,
                expanded = aboutExpanded,
                onExpandChange = { aboutExpanded = it },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.mipmap.ic_launcher),
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("Tohru", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = stringResource(R.string.settings_version, version),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CreditItem(label = stringResource(R.string.settings_credit_author_label), value = "CleveTok3125")
                    CreditItem(
                        label = stringResource(R.string.settings_credit_source_label),
                        value = "github.com/TohruTodo/Tohru-android",
                        onClick = { uriHandler.openUri("https://github.com/TohruTodo/Tohru-android") },
                    )
                    CreditItem(label = stringResource(R.string.settings_credit_license_label), value = "GPL-3.0-or-later")
                }
            }
        }
    }


}

@Composable
private fun LanguageChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun ThemeModePicker(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit) {
    val options = listOf(
        ThemeOption(ThemeMode.LIGHT, stringResource(R.string.settings_theme_light), R.drawable.ic_theme_light),
        ThemeOption(ThemeMode.DARK, stringResource(R.string.settings_theme_dark), R.drawable.ic_theme_dark),
        ThemeOption(ThemeMode.SYSTEM, stringResource(R.string.settings_theme_system), R.drawable.ic_theme_system),
        ThemeOption(ThemeMode.BLACK, stringResource(R.string.settings_theme_black), R.drawable.ic_theme_black),
    )
    val shape = RoundedCornerShape(14.dp)
    val cellSize = 84.dp
    val gridWidth = cellSize * 2 + 1.dp
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.width(gridWidth),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.clip(shape).selectableGroup()) {
                options.chunked(2).forEachIndexed { rowIndex, row ->
                    Row {
                        row.forEachIndexed { cellIndex, option ->
                            ThemeModeCell(
                                option = option,
                                selected = mode == option.mode,
                                onClick = { onModeChange(option.mode) },
                                modifier = Modifier.size(cellSize),
                            )
                            if (cellIndex == 0) {
                                VerticalDivider(
                                    modifier = Modifier.height(cellSize),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        }
                    }
                    if (rowIndex == 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeModeCell(
    option: ThemeOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(option.icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = content,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = option.label,
            style = MaterialTheme.typography.bodySmall,
            color = content,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

private data class ThemeOption(
    val mode: ThemeMode,
    val label: String,
    @DrawableRes val icon: Int,
)

@Composable
private fun CreditItem(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = if (onClick != null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier
            },
        )
    }
}

@Composable
private fun SettingsGroup(title: String) {
    HorizontalDivider(
        modifier = Modifier.padding(top = 6.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun SettingsSection(
    title: String,
    @DrawableRes icon: Int,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    showTopDivider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val angle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = androidx.compose.animation.core.tween(Motion.FAST),
        label = "section-chevron",
    )
    Column(modifier = Modifier.padding(top = 12.dp)) {
        if (showTopDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(4.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = { onExpandChange(!expanded) })
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 90f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, top = 4.dp, bottom = 8.dp),
                content = content,
            )
        }
    }
}


private fun treeUriPath(context: android.content.Context, uri: Uri): String? {
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        ?: return null
    val parts = documentId.split(':', limit = 2)
    if (parts.size != 2) return null
    val volume = parts[0]
    val relative = parts[1]
    val root = if (volume == "primary") {
        Environment.getExternalStorageDirectory()
    } else {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(android.os.storage.StorageManager::class.java)
        manager.storageVolumes.firstOrNull { it.uuid == volume }?.directory
    } ?: return null
    return File(root, relative).absolutePath
}
