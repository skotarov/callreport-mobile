from pathlib import Path

path = Path('.agent/apply_note_author_id_direct.py')
script = path.read_text()


def replace_block(marker: str, replacement: str) -> None:
    global script
    marker_end = script.index(marker) + len(marker)
    block_start = script.rfind('p = add_after(', 0, marker_end)
    if block_start < 0:
        raise SystemExit(f'Could not find block for {marker}')
    script = script[:block_start] + replacement + script[marker_end:]


replace_block(
    "'cache event write',\n)",
    """event_json_start = p.index('    private fun CallReportHistoryEvent.toJson()')
event_json_end = p.index('    private fun JSONObject.toHistoryEvent()', event_json_start)
event_json = p[event_json_start:event_json_end]
event_json = add_after(
    event_json,
    '        put(\"company_id\", companyId)\\n',
    '        put(\"author_profile_id\", authorProfileId)\\n        isMine?.let { put(\"is_mine\", it) }\\n        canEdit?.let { put(\"can_edit\", it) }\\n',
    'cache event write scoped',
)
p = p[:event_json_start] + event_json + p[event_json_end:]""",
)
replace_block(
    "'cache event read',\n)",
    """event_read_start = p.index('    private fun JSONObject.toHistoryEvent()')
event_read_end = p.index('    private fun CallReportHistoryCompanyMainNote.toJson()', event_read_start)
event_read = p[event_read_start:event_read_end]
event_read = add_after(
    event_read,
    '            companyId = optString(\"company_id\").trim(),\\n',
    '            authorProfileId = optString(\"author_profile_id\").trim(),\\n            isMine = optionalBoolean(\"is_mine\"),\\n            canEdit = optionalBoolean(\"can_edit\"),\\n',
    'cache event read scoped',
)
p = p[:event_read_start] + event_read + p[event_read_end:]""",
)
replace_block(
    "'company pending history identity',\n)",
    """history_event_start = p.index('    fun toHistoryEvent(): CallReportHistoryEvent = CallReportHistoryEvent(')
history_event_end = p.index('    fun toSyncEvent(', history_event_start)
history_event = p[history_event_start:history_event_end]
history_event = add_after(
    history_event,
    '        companyId = companyId,\\n',
    '        authorProfileId = authorProfileId,\\n        authorBrokerName = authorName,\\n        isMine = true,\\n        canEdit = true,\\n',
    'company pending history identity scoped',
)
p = p[:history_event_start] + history_event + p[history_event_end:]""",
)
exec(compile(script, str(path), 'exec'))
