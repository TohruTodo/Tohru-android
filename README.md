# Tohru

[English](README.md) · [Tiếng Việt](README.vi.md)

Tohru is a task manager for Android. Every task is a Markdown file in a Git
repository that you own. The app has no account, no server, and no proprietary
sync service.

[![CI](https://github.com/TohruTodo/Tohru-android/actions/workflows/ci.yml/badge.svg)](https://github.com/TohruTodo/Tohru-android/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/TohruTodo/Tohru-android)](https://github.com/TohruTodo/Tohru-android/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)

## Install

Download the latest APK from [Releases](https://github.com/TohruTodo/Tohru-android/releases).
Tohru requires Android 8.0 (API 26) or later.

The first start creates a repository in app-private storage. To use an existing
repository, set the path in Settings. Android 11 and later can require all-files
access for paths outside the app sandbox.

## Task files

A task lives at `tasks/[<state>/][<group>/][<topic>/]<id>_<slug>.md`.
State folders come first, then the group and topic folders, then the file.

```text
tasks/
├── README.md                    # optional folder notes
├── _order.json                  # manual sibling order
├── work/
│   └── reports/
│       └── 1732000000000-0001_work_reports_weekly_report.md
├── _done/
│   └── work/
│       └── 1732000000000-0002_work_review_pr.md
└── _archived/
    └── _done/
        └── 1732000000000-0003_old_task.md
```

Each file starts with a frontmatter header:

```markdown
---
id: 1732000000000-0001
title: Work(reports): Weekly report
created: 1732000000000
updated: 1732000000000
---

Body text in Markdown.
```

- `id`, `title`, `created`, and `updated` are required. Times are Unix epoch
  milliseconds.
- A title of the form `group(topic): text` sets the folder path. A plain title
  stays ungrouped. Use `_` for a space in a group name.
- The folder stores the state. Open tasks have no state folder, done tasks use
  `_done`, archived tasks use `_archived`, and archived tasks that were done use
  `_archived/_done`.
- Folder names and file slugs follow one rule: Unicode NFC, lowercase, keep
  Unicode letters, marks, and digits, replace every other run with `_`, and cap
  the slug at 48 characters.
- A file outside the path implied by its title is ignored. Tohru does not move
  it.
- Task links are relative Markdown links to the target file. Rename and state
  changes rewrite incoming links.

## Features

### Boards and navigation

- **Manage** shows tasks in an alphabetical grid. Folder READMEs appear on
  the same page.
- **Tasks** shows a tree of groups, topics, and ungrouped tasks. A collapsible
  **Done** section closes the tree.
- Search matches group, topic, title, and body. Group filters are multi-select.
- Switch boards with a swipe or the bottom bar.

### Editing

- Tap a card to edit the title and description in a sheet.
- Swipe a card to reveal the edit action. A full pull opens the task.
- Long-press a card or a group header to start selection.
- The detail screen opens on the raw editor and swipes into the read view. Both
  sides share the same header and body layout.
- A table-of-contents button draws the title and headings as a tree.
- Drafts save when you leave the screen or send the app to the background.
- Selection supports done state, copy, archive, and drag reorder. Copy writes a
  Markdown checklist. The Archive screen restores or deletes selections.
- Dragging reorders siblings only. The result is stored in `_order.json` and
  overrides the automatic order.

### Markdown

- The read view renders standard Markdown: headings, lists, quotes, code
  blocks, and local checklists.
- Ticking a checklist writes the edited body back. The task done state does not
  change.
- The editor has a task picker that inserts the current title and relative link
  at the cursor.
- A missing link target renders as `file not found` and keeps the link.

### Archive

- Archive moves a task under `_archived`. Undo is available right after the action.
- The Archive screen restores or permanently deletes a selection.
- Permanent deletion asks for confirmation.

### Git sync

- Each save under `tasks/` becomes a local commit. Tohru never fetches, pulls,
  or pushes in the background.
- The Git panel shows branch, ahead/behind counts, local changes, the last
  commit, and the last sync.
- Commit, Fetch, Pull, Push, and Sync are explicit actions. Sync commits pending
  changes, pulls with rebase, then pushes.
- Pull and Push are disabled while the worktree is dirty.
- The history screen is a read-only commit browser with graph, refs, search,
  branch scope, commit details, and text diffs.
- Branches can be created, switched, and deleted. Stale local branches are
  marked.

### Conflicts

- Conflicts open a full-screen editor with a line diff, a per-file choice of
  local or remote, and a raw editor for a mixed result.
- Both sides are stored under `.sync-conflict/`. The ten newest sessions are
  kept and the folder never syncs.
- Discarding a conflict keeps the local files unchanged.

### Import and export

- Export writes a ZIP snapshot of `tasks/`, including `_order.json`, and opens
  the system share sheet.
- Import accepts the same layout. For duplicate ids, choose Skip, Overwrite, or
  Duplicate.
- The ZIP never contains `.git` or credentials.

### Quick task overlay

- The **Quick task** tile opens a full-screen task panel over other apps.
- The overlay needs the **Display over other apps** permission and shows a
  foreground notification.
- The panel reuses the task tree and shares collapse state with the main app.
- By default, the overlay closes when the app task closes. Settings can keep it
  running after the app leaves recents.

### Appearance and language

- Dynamic Material You colors on Android 12 and later, with an icon-derived
  fallback below.
- Theme modes: system, light, dark, and black.
- Language: system, Vietnamese, or English.
- Haptics can be disabled in Settings.

### Diagnostics

- Settings → Debug exports a text file with device and app information, the
  latest logcat attempt, and saved crash traces.

## Credentials

- HTTPS remotes use a token. SSH remotes use a private key and an optional
  passphrase.
- Tokens and passphrases are encrypted with a Keystore-backed AES key.
- The SSH private key lives in app-private storage.
- The SSH host key is confirmed on first use.
- The commit author name and email are configurable. The defaults are `Tohru`
  and `tohru@localhost`.

## Maintenance

- Recovery refs are stored under `refs/tohru/recovery/`. Settings control how
  many are kept.
- Automatic Git GC runs at a configurable interval.
- Old ZIP exports are pruned. Settings control how many are kept.

## Permissions

| Permission | Reason |
| --- | --- |
| `INTERNET` | Git fetch, pull, and push |
| `SYSTEM_ALERT_WINDOW` | Quick task overlay |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the overlay service alive |
| `POST_NOTIFICATIONS` | Foreground notification for the overlay |
| `VIBRATE` | Haptic feedback |
| `MANAGE_EXTERNAL_STORAGE` | Optional repository paths outside the app sandbox |

## Build

Requirements: JDK 21 and the Android SDK with platform 37.

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Offline builds work when the pinned dependency versions are already cached.

Release builds use `keystore.properties` when present. The file is never
committed:

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

CI reads `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and
`KEY_PASSWORD` from repository secrets.

Version metadata comes from Git. `versionName` follows the nearest tag and
`versionCode` tracks the commit count. The release workflow runs on `v*` tags
and publishes the APK with `SHA256SUMS`.

## Contributing

Open an issue for bugs and proposals. Pull requests should keep the existing
structure and pass `testDebugUnitTest` and `lintDebug`.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
