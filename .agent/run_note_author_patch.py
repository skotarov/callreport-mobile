from pathlib import Path

workflow = Path('.github/workflows/apply-note-author-id-fix.yml').read_text().splitlines()
start = next(index for index, line in enumerate(workflow) if "python3 <<'PY'" in line) + 1
end = next(index for index in range(start, len(workflow)) if workflow[index].strip() == 'PY')
script_lines = []
for line in workflow[start:end]:
    script_lines.append(line[10:] if line.startswith('          ') else line)
script = '\n'.join(script_lines) + '\n'

marker = "'CompanySessionStore load ID')"
marker_end = script.index(marker) + len(marker)
block_start = script.rfind('p = replace_once(p,', 0, marker_end)
if block_start < 0:
    raise SystemExit('Could not locate strict CompanySessionStore load-ID patch')
replacement = """needle = '            organizationId = prefs.getString(KEY_ORGANIZATION_ID, \\\"\\\").orEmpty().trim(),\\n'
if p.count(needle) != 1:
    raise SystemExit(f'CompanySessionStore load ID line: expected one match, found {p.count(needle)}')
p = p.replace(
    needle,
    needle + '            userId = prefs.getString(KEY_USER_ID, \\\"\\\").orEmpty().trim(),\\n',
    1,
)"""
script = script[:block_start] + replacement + script[marker_end:]
exec(compile(script, '/tmp/apply-note-author-id.py', 'exec'))
