from pathlib import Path

workflow = Path('.github/workflows/apply-note-author-id-fix.yml').read_text().splitlines()
start = next(index for index, line in enumerate(workflow) if "python3 <<'PY'" in line) + 1
end = next(index for index in range(start, len(workflow)) if workflow[index].strip() == 'PY')
script_lines = []
for line in workflow[start:end]:
    script_lines.append(line[10:] if line.startswith('          ') else line)
script = '\n'.join(script_lines) + '\n'


def replace_strict_block(marker: str, replacement: str) -> None:
    global script
    marker_end = script.index(marker) + len(marker)
    block_start = script.rfind('p = replace_once(p,', 0, marker_end)
    if block_start < 0:
        raise SystemExit(f'Could not locate strict patch for {marker}')
    script = script[:block_start] + replacement + script[marker_end:]


replace_strict_block(
    "'CompanySessionStore load ID')",
    """needle = '            organizationId = prefs.getString(KEY_ORGANIZATION_ID, \\\"\\\").orEmpty().trim(),\\n'
if p.count(needle) != 1:
    raise SystemExit(f'CompanySessionStore load ID line: expected one match, found {p.count(needle)}')
p = p.replace(
    needle,
    needle + '            userId = prefs.getString(KEY_USER_ID, \\\"\\\").orEmpty().trim(),\\n',
    1,
)""",
)
replace_strict_block(
    "'CompanySessionStore nonempty')",
    """needle = '            it.userName.isNotBlank()'
if p.count(needle) != 1:
    raise SystemExit(f'CompanySessionStore nonempty line: expected one match, found {p.count(needle)}')
p = p.replace(
    needle,
    '            it.userId.isNotBlank() || it.userName.isNotBlank()',
    1,
)""",
)
replace_strict_block(
    "'CompanySessionStore scope')",
    """needle = '        return snapshot.userEmail.trim().lowercase()\\n'
if p.count(needle) != 1:
    raise SystemExit(f'CompanySessionStore scope line: expected one match, found {p.count(needle)}')
p = p.replace(
    needle,
    '        return snapshot.userId.trim()\\n            .ifBlank { snapshot.userEmail.trim().lowercase() }\\n',
    1,
)""",
)
exec(compile(script, '/tmp/apply-note-author-id.py', 'exec'))
