#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "mobile/calllog-android/app/src/main/java/com/onlineimoti/calllog"


def replace(path: str, old: str, new: str) -> None:
    file = JAVA / path
    text = file.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"Expected text missing in {path}: {old!r}")
    file.write_text(text.replace(old, new), encoding="utf-8")


replace(
    "ContactNotesHeaderUiSupport.kt",
    "import android.content.Context\n",
    "import android.content.Context\nimport android.content.res.ColorStateList\n",
)
replace(
    "IncomingCallPopupProgressFormatter.kt",
    'Regex("\\s+")',
    'Regex("\\\\s+")',
)
replace(
    "MainActivity.kt",
    "    private val defaultSmsSettingsController:",
    "    internal val defaultSmsSettingsController:",
)
replace(
    "MainActivity.kt",
    "    private val callScreeningIntegrationSettingsController:",
    "    internal val callScreeningIntegrationSettingsController:",
)
replace(
    "MainActivityPresentation.kt",
    "intent?.getBooleanExtra(EXTRA_OPEN_SERVER, false)",
    "intent?.getBooleanExtra(MainActivity.EXTRA_OPEN_SERVER, false)",
)
replace(
    "MainActivityPresentation.kt",
    "intent?.getBooleanExtra(EXTRA_OPEN_REGISTRATION, false)",
    "intent?.getBooleanExtra(MainActivity.EXTRA_OPEN_REGISTRATION, false)",
)
