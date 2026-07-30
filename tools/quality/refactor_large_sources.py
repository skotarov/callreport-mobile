#!/usr/bin/env python3
"""One-time structural split of the remaining oversized Android sources.

The script moves intact method/class blocks into package-local helpers and keeps
all constants, timeouts, cache keys and endpoint behavior unchanged.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA_ROOT = ROOT / "mobile/calllog-android/app/src/main/java/com/onlineimoti/calllog"
MAX_LINES = 300


def read(name: str) -> str:
    return (JAVA_ROOT / name).read_text(encoding="utf-8")


def write(name: str, content: str) -> None:
    if not content.endswith("\n"):
        content += "\n"
    (JAVA_ROOT / name).write_text(content, encoding="utf-8")


def count(content: str) -> int:
    return len(content.splitlines())


def dedent_four(content: str) -> str:
    return "\n".join(line[4:] if line.startswith("    ") else line for line in content.splitlines())


def assert_under_limit(names: list[str]) -> None:
    errors = []
    for name in names:
        size = count(read(name))
        print(f"{size:4d}  {name}")
        if size > MAX_LINES:
            errors.append(f"{size}: {name}")
    if errors:
        raise RuntimeError("Files over 300 lines:\n" + "\n".join(errors))


def split_history_controller() -> list[str]:
    name = "CallReportMergedHistoryController.kt"
    text = read(name)
    marker = "    /** Wait for all source loads, then publish one coherent snapshot. */"
    start = text.index(marker)
    final_close = text.rfind("\n}")
    support = dedent_four(text[start:final_close])
    support = re.sub(
        r"(?m)^private fun ([A-Za-z_][A-Za-z0-9_]*)\(",
        r"internal fun CallReportMergedHistoryController.\1(",
        support,
    )
    support = support.replace("private data class HistoryRenderedState", "internal data class HistoryRenderedState")
    helper = """package com.onlineimoti.calllog

import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView

""" + support.strip() + "\n"

    base = text[:start] + "}\n"
    header_end = base.index("    fun loadOnce")
    header = base[:header_end].replace("private val", "internal val").replace("private var", "internal var")
    base = header + base[header_end:]
    base = base.replace("import android.graphics.Color\n", "").replace("import android.widget.TextView\n", "")
    write(name, base)
    write("CallReportMergedHistoryControllerSupport.kt", helper)
    return [name, "CallReportMergedHistoryControllerSupport.kt"]


def split_home_renderer() -> list[str]:
    name = "HomeContentRenderer.kt"
    text = read(name)
    marker = "    /** Replaces only rows whose visible note/name state changed, preserving the list and scroll position. */"
    start = text.index(marker)
    final_close = text.rfind("\n}")
    support = dedent_four(text[start:final_close])
    support = re.sub(
        r"(?m)^private fun ([A-Za-z_][A-Za-z0-9_]*)\(",
        r"internal fun HomeContentRenderer.\1(",
        support,
    )
    support = support.replace(
        'private companion object {\n    const val HOME_ROW_TAG_PREFIX = "relationship_manager_home_row:"\n}',
        'private const val HOME_ROW_TAG_PREFIX = "relationship_manager_home_row:"',
    )
    helper = """package com.onlineimoti.calllog

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

""" + support.strip() + "\n"

    base = text[:start] + "}\n"
    header_end = base.index("    fun replaceCurrentCalls")
    header = base[:header_end].replace("private val", "internal val").replace("private var", "internal var")
    header = header.replace("private set", "internal set")
    base = header + base[header_end:]
    write(name, base)
    write("HomeContentRendererSupport.kt", helper)
    return [name, "HomeContentRendererSupport.kt"]


def split_company_users() -> list[str]:
    name = "RegistrationCompaniesController.kt"
    text = read(name)
    start_marker = "    private fun showUsers(activity: AppCompatActivity, company: CallReportTopicCompany) {"
    end_marker = "    private fun roleLabel(activity: AppCompatActivity, role: String): String"
    start = text.index(start_marker)
    end = text.index(end_marker)
    methods = dedent_four(text[start:end]).strip()
    methods = methods.replace("private fun showUsers(", "fun show(", 1)
    methods = methods.replace("showUsers(activity, company)", "show(activity, company)")
    shared_helpers = """

    private fun roleLabel(activity: AppCompatActivity, role: String): String = activity.getString(
        when (role.lowercase()) {
            "owner" -> R.string.settings_registration_role_owner
            "admin" -> R.string.settings_registration_role_admin
            "member" -> R.string.settings_registration_role_member
            else -> R.string.settings_registration_role_broker
        },
    )

    private fun verticalParams(activity: AppCompatActivity, top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(activity, top) }

    private fun actionParams(activity: AppCompatActivity, start: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(activity, start)
        }

    private fun dp(activity: AppCompatActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
"""
    helper = """package com.onlineimoti.calllog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

internal object RegistrationCompanyUsersUi {
""" + "\n".join("    " + line if line else "" for line in methods.splitlines()) + shared_helpers + "}\n"

    base = text[:start] + text[end:]
    base = base.replace(
        "setOnClickListener { showUsers(activity, company) }",
        "setOnClickListener { RegistrationCompanyUsersUi.show(activity, company) }",
    )
    for unused in [
        "import android.content.ClipData\n",
        "import android.content.ClipboardManager\n",
        "import android.content.Context\n",
        "import android.widget.ScrollView\n",
    ]:
        base = base.replace(unused, "")
    write(name, base)
    write("RegistrationCompanyUsersUi.kt", helper)
    return [name, "RegistrationCompanyUsersUi.kt"]


def matching_block_end(text: str, start: int) -> int:
    brace = text.index("{", start)
    depth = 0
    for index in range(brace, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index + 1
    raise RuntimeError("Unclosed Kotlin block")


def split_incoming_lookup() -> list[str]:
    name = "IncomingCallLookupCoordinator.kt"
    text = read(name)

    standalone_marker = "/** Three permanent information rows in the incoming-call popup. */"
    standalone_start = text.index(standalone_marker)
    standalone = text[standalone_start:].strip()
    text = text[:standalone_start].rstrip() + "\n"
    session_file = """package com.onlineimoti.calllog

import java.util.concurrent.atomic.AtomicLong

""" + standalone + "\n"

    companion_start = text.index("    private companion object {")
    companion_end = matching_block_end(text, companion_start)
    text = text[:companion_start] + text[companion_end:]

    progress_start = text.index("    private fun progressLocked(): IncomingCallPopupProgress {")
    progress_end = text.index("    private fun fallbackLookup", progress_start)
    progress_delegate = """    private fun progressLocked(): IncomingCallPopupProgress =
        IncomingCallPopupProgressFormatter.build(
            remoteAvailable = remoteAvailable,
            localRows = localRows,
            remoteRows = remoteRows,
            historyFinished = historyFinished,
            historyFailed = historyFailed,
            serverSlow = serverSlow,
            lookupFinished = lookupFinished,
            lookupSucceeded = lookupSucceeded,
        )

"""
    text = text[:progress_start] + progress_delegate + text[progress_end:]

    format_start = text.index("    private fun isLocalNoteRow")
    format_end = text.index("    private data class Snapshot", format_start)
    text = text[:format_start] + text[format_end:]

    replacements = {
        "CONTACT_EXECUTOR": "IncomingCallLookupExecutors.contact",
        "LOCAL_ROWS_EXECUTOR": "IncomingCallLookupExecutors.localRows",
        "LOOKUP_EXECUTOR": "IncomingCallLookupExecutors.lookup",
        "HISTORY_EXECUTOR": "IncomingCallLookupExecutors.history",
        "LOOKUP_TIMEOUT_EXECUTOR": "IncomingCallLookupExecutors.timeout",
        "LOOKUP_DEADLINE_MS": "IncomingCallLookupExecutors.LOOKUP_DEADLINE_MS",
        "POPUP_HISTORY_LIMIT": "IncomingCallLookupExecutors.POPUP_HISTORY_LIMIT",
    }
    for old, new in replacements.items():
        text = text.replace(old, new)
    for unused in [
        "import java.util.concurrent.ArrayBlockingQueue\n",
        "import java.util.concurrent.ScheduledThreadPoolExecutor\n",
        "import java.util.concurrent.atomic.AtomicLong\n",
    ]:
        text = text.replace(unused, "")

    executors = """package com.onlineimoti.calllog

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object IncomingCallLookupExecutors {
    const val LOOKUP_DEADLINE_MS = 4_500L
    const val POPUP_HISTORY_LIMIT = 20

    private const val CONTACT_QUEUE_SIZE = 8
    private const val LOCAL_ROWS_QUEUE_SIZE = 8
    private const val LOOKUP_QUEUE_SIZE = 12
    private const val HISTORY_QUEUE_SIZE = 12

    val contact = pool(1, CONTACT_QUEUE_SIZE)
    val localRows = pool(1, LOCAL_ROWS_QUEUE_SIZE)
    val lookup = pool(2, LOOKUP_QUEUE_SIZE)
    val history = pool(1, HISTORY_QUEUE_SIZE)
    val timeout = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }

    private fun pool(threads: Int, queueSize: Int) = ThreadPoolExecutor(
        threads,
        threads,
        20L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(queueSize),
        ThreadPoolExecutor.AbortPolicy(),
    )
}
"""
    formatter = """package com.onlineimoti.calllog

internal object IncomingCallPopupProgressFormatter {
    private const val MAX_LOCAL_NOTES_IN_ROW = 2
    private const val MAX_SERVER_NOTES_IN_ROW = 3
    private const val ICON_GENERAL_NOTE = "☰"
    private const val ICON_CALL_NOTE = "💬"

    fun build(
        remoteAvailable: Boolean,
        localRows: List<String>?,
        remoteRows: List<PostCallLookupRemoteRow>?,
        historyFinished: Boolean,
        historyFailed: Boolean,
        serverSlow: Boolean,
        lookupFinished: Boolean,
        lookupSucceeded: Boolean,
    ): IncomingCallPopupProgress {
        val callLine = when (localRows) {
            null -> IncomingCallPopupProgress.LOADING
            else -> localRows.firstOrNull { !isLocalNoteRow(it) }.orEmpty()
                .ifBlank { "Няма предишни разговори" }
        }
        val localNoteLine = when (localRows) {
            null -> IncomingCallPopupProgress.LOADING
            else -> localRows.asSequence()
                .filter(::isLocalNoteRow)
                .map(::stripLocalNoteIcon)
                .filter { it.isNotBlank() }
                .take(MAX_LOCAL_NOTES_IN_ROW)
                .joinToString(" • ")
                .ifBlank { "Няма локални бележки" }
        }
        val serverNoteLine = when {
            !remoteAvailable -> "Сървърът не е настроен"
            remoteRows?.isNotEmpty() == true -> remoteRows.asSequence()
                .map(::formatRemoteRow)
                .filter { it.isNotBlank() }
                .take(MAX_SERVER_NOTES_IN_ROW)
                .joinToString(" • ")
            historyFinished && historyFailed -> "Сървърът не отговори"
            historyFinished -> "Няма сървърни бележки"
            serverSlow -> "Сървърът отговаря бавно…"
            lookupFinished && !lookupSucceeded -> "Сървърът не отговори"
            else -> IncomingCallPopupProgress.LOADING
        }
        return IncomingCallPopupProgress(callLine, localNoteLine, serverNoteLine)
    }

    private fun isLocalNoteRow(value: String): Boolean =
        value.startsWith(ICON_GENERAL_NOTE) || value.startsWith(ICON_CALL_NOTE)

    private fun stripLocalNoteIcon(value: String): String = value
        .removePrefix(ICON_GENERAL_NOTE)
        .removePrefix(ICON_CALL_NOTE)
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun formatRemoteRow(row: PostCallLookupRemoteRow): String =
        listOf(row.companyName.ifBlank { "Сървър" }, row.note.trim())
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}
"""
    write(name, text)
    write("IncomingCallPopupSessionStore.kt", session_file)
    write("IncomingCallLookupExecutors.kt", executors)
    write("IncomingCallPopupProgressFormatter.kt", formatter)
    return [
        name,
        "IncomingCallPopupSessionStore.kt",
        "IncomingCallLookupExecutors.kt",
        "IncomingCallPopupProgressFormatter.kt",
    ]


def split_main_activity() -> list[str]:
    name = "MainActivity.kt"
    text = read(name)

    server_start = text.index("    private fun saveServerSettings()")
    server_end = text.index("    internal fun requestAppPermissionFromSummary", server_start)
    server_methods = dedent_four(text[server_start:server_end])
    server_methods = re.sub(
        r"(?m)^private fun ([A-Za-z_][A-Za-z0-9_]*)\(",
        r"internal fun MainActivity.\1(",
        server_methods,
    )
    text = text[:server_start] + text[server_end:]

    configure_start = text.index("    private fun configureBuildSpecificSettings()")
    configure_end = text.index("    private fun hydrateFields", configure_start)
    configure_method = dedent_four(text[configure_start:configure_end])
    text = text[:configure_start] + text[configure_end:]

    open_start = text.index("    private fun openRequestedSettingsSection")
    open_end = text.index("    private fun wireSettingsActions", open_start)
    open_method = dedent_four(text[open_start:open_end])
    text = text[:open_start] + text[open_end:]

    presentation = configure_method + open_method
    presentation = re.sub(
        r"(?m)^private fun ([A-Za-z_][A-Za-z0-9_]*)\(",
        r"internal fun MainActivity.\1(",
        presentation,
    )

    text = text.replace("private lateinit var binding", "internal lateinit var binding")
    text = text.replace("private val executor", "internal val executor")
    text = text.replace("private var suppressAutoSave", "internal var suppressAutoSave")
    text = text.replace("private var serverConnectionGeneration", "internal var serverConnectionGeneration")
    text = text.replace("private val serverSyncQueueStatusController", "internal val serverSyncQueueStatusController")
    text = text.replace("::onRemoteEnabledRequested", "{ enabled -> onRemoteEnabledRequested(enabled) }")
    text = text.replace("::onRemoteConnectionInputChanged", "{ onRemoteConnectionInputChanged() }")
    text = text.replace("saveServerSettings = ::saveServerSettings", "saveServerSettings = { saveServerSettings() }")
    text = text.replace("    private fun saveConfig(): AppConfig", "    internal fun saveConfig(): AppConfig")
    text = text.replace("    private fun setStatus(message: String)", "    internal fun setStatus(message: String)")
    text = text.replace("    private fun refreshPermissionSummary()", "    internal fun refreshPermissionSummary()")

    server_file = "package com.onlineimoti.calllog\n\n" + server_methods.strip() + "\n"
    presentation_file = "package com.onlineimoti.calllog\n\nimport android.content.Intent\n\n" + presentation.strip() + "\n"
    write(name, text)
    write("MainActivityServerConnection.kt", server_file)
    write("MainActivityPresentation.kt", presentation_file)
    return [name, "MainActivityServerConnection.kt", "MainActivityPresentation.kt"]


def main() -> int:
    written: list[str] = []
    written += split_history_controller()
    written += split_home_renderer()
    written += split_company_users()
    written += split_incoming_lookup()
    written += split_main_activity()
    assert_under_limit(written)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
