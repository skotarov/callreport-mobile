#!/usr/bin/env python3
"""Split the remaining oversized Android files after the first structural pass."""

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


def count(name: str) -> int:
    return len(read(name).splitlines())


def dedent_four(content: str) -> str:
    return "\n".join(line[4:] if line.startswith("    ") else line for line in content.splitlines())


def matching_block_end(text: str, start: int) -> int:
    brace = text.index("{", start)
    depth = 0
    for index in range(brace, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return index + 1
    raise RuntimeError("Unclosed Kotlin block")


def member_extensions(content: str, receiver: str) -> str:
    return re.sub(
        r"(?m)^private fun ([A-Za-z_][A-Za-z0-9_]*)\(",
        rf"internal fun {receiver}.\1(",
        content,
    )


def split_post_call_popup() -> list[str]:
    name = "PostCallLookupPopup.kt"
    text = read(name)

    progress_start = text.index("    private fun showProgressive(sessionId: String)")
    progress_end = text.index("    /**\n     * Unknown numbers", progress_start)
    progress = dedent_four(text[progress_start:progress_end])
    text = text[:progress_start] + text[progress_end:]

    normalized_start = text.index("    private fun IncomingCallPopupProgress.normalized()")
    normalized_end = text.index("    private fun buildDataColumn", normalized_start)
    normalized = dedent_four(text[normalized_start:normalized_end]).replace(
        "private fun IncomingCallPopupProgress.normalized()",
        "internal fun IncomingCallPopupProgress.normalized()",
    )
    text = text[:normalized_start] + text[normalized_end:]

    types_start = text.index("    private data class ProgressRow")
    types_end = text.index("    private companion object", types_start)
    types = dedent_four(text[types_start:types_end]).replace("private data class", "internal data class")
    text = text[:types_start] + text[types_end:]

    progress = member_extensions(progress, "PostCallLookupPopup")
    helper = """package com.onlineimoti.calllog

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

""" + progress.strip() + "\n\n" + normalized.strip() + "\n\n" + types.strip() + "\n"

    header_end = text.index("    fun show()")
    header = text[:header_end].replace("private val", "internal val").replace("private var", "internal var")
    text = header + text[header_end:]
    text = text.replace("    private fun identity(", "    internal fun identity(")
    write(name, text)
    write("PostCallLookupPopupProgressiveUi.kt", helper)
    return [name, "PostCallLookupPopupProgressiveUi.kt"]


def split_contact_notes_header() -> list[str]:
    name = "ContactNotesHeaderUi.kt"
    text = read(name)
    start = text.index("    private fun identityBlock(")
    companion_start = text.index("    private companion object", start)
    methods = dedent_four(text[start:companion_start])
    companion_end = matching_block_end(text, companion_start)
    text = text[:start] + text[companion_end:]

    constants = {
        "CRM_SLOT_START_PADDING_DP": "CONTACT_NOTES_CRM_SLOT_START_PADDING_DP",
        "ACTION_ANCHOR_HEIGHT_DP": "CONTACT_NOTES_ACTION_ANCHOR_HEIGHT_DP",
        "ACTION_ROW_HEIGHT_DP": "CONTACT_NOTES_ACTION_ROW_HEIGHT_DP",
        "CRM_SLOT_WEIGHT": "CONTACT_NOTES_CRM_SLOT_WEIGHT",
    }
    for old, new in constants.items():
        text = text.replace(old, new)
        methods = methods.replace(old, new)
    methods = member_extensions(methods, "ContactNotesHeaderUi")

    header_end = text.index("    fun headerRow(")
    header = text[:header_end].replace("private val", "internal val")
    text = header + text[header_end:]

    helper = """package com.onlineimoti.calllog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

internal const val CONTACT_NOTES_CRM_SLOT_START_PADDING_DP = 2
internal const val CONTACT_NOTES_ACTION_ANCHOR_HEIGHT_DP = 50
internal const val CONTACT_NOTES_ACTION_ROW_HEIGHT_DP = 48
internal const val CONTACT_NOTES_CRM_SLOT_WEIGHT = 1.2f

""" + methods.strip() + "\n"
    write(name, text)
    write("ContactNotesHeaderUiSupport.kt", helper)
    return [name, "ContactNotesHeaderUiSupport.kt"]


def replace_restored_callable_references(content: str) -> str:
    replacements = {
        "rerender = ::render": "rerender = { render() }",
        "::render)": "{ render() })",
        "openRmContact = ::openRmContactForm": "openRmContact = { openRmContactForm() }",
        "onEditCompany = ::openGeneralNoteEditor": "onEditCompany = { companyId -> openGeneralNoteEditor(companyId) }",
        "onEditUnscopedServerMainNote = ::openUnscopedServerMainNoteEditor": "onEditUnscopedServerMainNote = { event -> openUnscopedServerMainNoteEditor(event) }",
        "onEditCallNote = ::openCallNoteEditor": "onEditCallNote = { note -> openCallNoteEditor(note) }",
        "onEditSms = ::openSmsCompanyEditor": "onEditSms = { sms, companyId -> openSmsCompanyEditor(sms, companyId) }",
        "openCallNoteEditor = ::openFullLogCallNoteEditor": "openCallNoteEditor = { call, displayName, note -> openFullLogCallNoteEditor(call, displayName, note) }",
        "onRefresh = ::refreshFromPull": "onRefresh = { refreshFromPull() }",
        "onModeSelected = ::selectListMode": "onModeSelected = { mode -> selectListMode(mode) }",
    }
    for old, new in replacements.items():
        content = content.replace(old, new)
    return content


def split_contact_notes_controller() -> list[str]:
    name = "ContactNotesRestoredController.kt"
    text = read(name)
    start = text.index("    private fun render()")
    end = text.index("    private fun roundedRect", start)
    methods = dedent_four(text[start:end])
    text = text[:start] + text[end:]

    methods = member_extensions(methods, "ContactNotesRestoredController")
    methods = replace_restored_callable_references(methods)
    text = replace_restored_callable_references(text)

    header_end = text.index("    fun onCreate(")
    header = text[:header_end].replace("private val", "internal val").replace("private var", "internal var")
    text = header + text[header_end:]
    for method in ["refreshHistoryInBackground", "refreshFromPull", "selectListMode", "roundedRect", "dp"]:
        text = text.replace(f"    private fun {method}", f"    internal fun {method}")

    helper = """package com.onlineimoti.calllog

import android.content.Intent
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat

""" + methods.strip() + "\n"
    write(name, text)
    write("ContactNotesRestoredControllerSupport.kt", helper)
    return [name, "ContactNotesRestoredControllerSupport.kt"]


def split_permission_flow() -> list[str]:
    name = "MainPermissionFlowController.kt"
    text = read(name)
    start = text.index("    private fun requestSmsSetup()")
    final_close = text.rfind("\n}")
    methods = dedent_four(text[start:final_close])
    methods = member_extensions(methods, "MainPermissionFlowController")
    text = text[:start] + "}\n"

    header_end = text.index("    fun start()")
    header = text[:header_end].replace("private val", "internal val").replace("private var", "internal var")
    text = header + text[header_end:]

    helper = """package com.onlineimoti.calllog

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat

""" + methods.strip() + "\n"
    write(name, text)
    write("MainPermissionFlowControllerSupport.kt", helper)
    return [name, "MainPermissionFlowControllerSupport.kt"]


def split_home_activity_dependencies() -> list[str]:
    name = "HomeActivityPaged.kt"
    text = read(name)
    properties = [
        "crmFiltersController",
        "homeCallRowRenderer",
        "crmContactRowRenderer",
        "edgePaging",
        "homeContentRenderer",
        "crmContactsContentView",
        "callsLoader",
        "crmContactsLoader",
        "searchController",
        "searchInputController",
        "timelineCoordinator",
        "runtimeController",
    ]
    factories: list[str] = []

    for property_name in properties:
        pattern = re.compile(
            rf"    private val {property_name}: ([^\n]+) by lazy \{{"
        )
        match = pattern.search(text)
        if match is None:
            raise RuntimeError(f"Property not found: {property_name}")
        property_type = match.group(1).strip()
        block_end = matching_block_end(text, match.start())
        brace = text.index("{", match.start(), block_end)
        body = dedent_four(text[brace + 1:block_end - 1]).strip("\n")
        body = re.sub(r"(?<![A-Za-z0-9_.])::([A-Za-z_])", r"this::\1", body)
        factory_name = "create" + property_name[0].upper() + property_name[1:]
        factory = (
            f"internal fun HomeActivity.{factory_name}(): {property_type} = run {{\n"
            + body
            + "\n}\n"
        )
        factories.append(factory)
        replacement = f"    internal val {property_name}: {property_type} by lazy {{ {factory_name}() }}"
        text = text[:match.start()] + replacement + text[block_end:]

    text = text.replace("    private lateinit var", "    internal lateinit var")
    text = text.replace("    private val", "    internal val")
    text = text.replace("    private var", "    internal var")
    text = text.replace("    private fun", "    internal fun")

    groups: list[list[str]] = [[], []]
    line_totals = [2, 2]
    for factory in factories:
        target = 0 if line_totals[0] <= line_totals[1] else 1
        groups[target].append(factory)
        line_totals[target] += len(factory.splitlines())

    write(name, text)
    generated = [name]
    for index, group in enumerate(groups, start=1):
        helper_name = f"HomeActivityDependencies{index}.kt"
        write(helper_name, "package com.onlineimoti.calllog\n\n" + "\n".join(item.strip() for item in group) + "\n")
        generated.append(helper_name)
    return generated


def main() -> int:
    written: list[str] = []
    written += split_post_call_popup()
    written += split_contact_notes_header()
    written += split_contact_notes_controller()
    written += split_permission_flow()
    written += split_home_activity_dependencies()

    errors = []
    for name in written:
        size = count(name)
        print(f"{size:4d}  {name}")
        if size > MAX_LINES:
            errors.append(f"{size}: {name}")
    if errors:
        raise RuntimeError("Files over 300 lines:\n" + "\n".join(errors))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
