from pathlib import Path
import re
import shutil

MAIN = Path('mobile/calllog-android/app/src/main/java/com/onlineimoti/calllog')
TEST = Path('mobile/calllog-android/app/src/test/java/com/onlineimoti/calllog')


def load(name: str) -> str:
    return (MAIN / name).read_text()


def save(name: str, text: str) -> None:
    (MAIN / name).write_text(text)


def sub(text: str, pattern: str, replacement: str, label: str, flags: int = 0) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f'{label}: expected one match, found {count}')
    return updated


def add_after(text: str, needle: str, addition: str, label: str) -> str:
    if text.count(needle) != 1:
        raise SystemExit(f'{label}: expected one match, found {text.count(needle)}')
    return text.replace(needle, needle + addition, 1)


# One ID-first policy shared by History and Home.
save('CallReportAuthorIdentityPolicy.kt', '''package com.onlineimoti.calllog

/** Resolves note ownership without treating a mutable display name as identity. */
internal object CallReportAuthorIdentityPolicy {
    fun isOtherAuthor(event: CallReportHistoryEvent?, principal: CallReportHistoryPrincipal): Boolean {
        event ?: return false
        event.isMine?.let { return !it }

        val authorProfileId = event.authorProfileId.trim()
        val currentProfileId = principal.profileId.trim()
        if (authorProfileId.isNotBlank() && currentProfileId.isNotBlank()) {
            return authorProfileId != currentProfileId
        }

        val authorBrokerId = event.authorBrokerId.trim()
        val currentBrokerId = principal.brokerId.trim()
        if (authorBrokerId.isNotBlank() && currentBrokerId.isNotBlank()) {
            return authorBrokerId != currentBrokerId
        }

        if (event.canEdit == true) return false
        val authorName = event.authorBrokerName.trim()
        val currentName = principal.brokerName.trim()
        if (authorName.isNotBlank() && currentName.isNotBlank()) {
            return !authorName.equals(currentName, ignoreCase = true)
        }
        return false
    }

    fun canEdit(event: CallReportHistoryEvent?, principal: CallReportHistoryPrincipal): Boolean {
        event ?: return true
        event.canEdit?.let { return it }
        event.isMine?.let { return it }
        return !isOtherAuthor(event, principal)
    }
}
''')

# Persist the stable user/profile ID from the authenticated session.
p = load('CompanyAccountApi.kt')
profile_start = p.index('    data class ProfileUser(')
profile_end = p.index('    data class Session(', profile_start)
profile = p[profile_start:profile_end]
profile = add_after(
    profile,
    '        val phoneVerified: Boolean = false,\n',
    '        /** Stable profile/user ID; the display name may change. */\n        val userId: String = "",\n',
    'ProfileUser userId',
)
p = p[:profile_start] + profile + p[profile_end:]
session_start = p.index('    data class Session(')
session_end = p.index('    data class OtpChallenge(', session_start)
session = p[session_start:session_end]
session = add_after(
    session,
    '        val phoneVerified: Boolean = false,\n',
    '        /** Stable profile/user ID returned by the authenticated server. */\n        val userId: String = "",\n',
    'Session userId',
)
session = sub(
    session,
    r'        fun user\(\): ProfileUser = ProfileUser\([^\n]+\)\n',
    '''        fun user(): ProfileUser = ProfileUser(
            name = userName,
            email = userEmail,
            phone = userPhone,
            emailVerified = emailVerified,
            phoneVerified = phoneVerified,
            userId = userId,
        )
''',
    'Session user()',
)
p = p[:session_start] + session + p[session_end:]
p = add_after(
    p,
    '        phoneVerified = user?.optBoolean("phone_verified", false) ?: false,\n',
    '        userId = user?.text("profile_id", "user_id", "id").orEmpty(),\n',
    'parseUser userId',
)
p = add_after(
    p,
    '            phoneVerified = user.phoneVerified,\n',
    '            userId = user.userId,\n',
    'parseSession userId',
)
helper = '''
    private fun JSONObject.text(vararg keys: String): String {
        keys.forEach { key ->
            val value = optString(key).trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }
'''
last_brace = p.rfind('\n}')
if last_brace < 0:
    raise SystemExit('CompanyAccountApi closing brace not found')
p = p[:last_brace] + helper + p[last_brace:]
save('CompanyAccountApi.kt', p)

p = load('CompanySessionStore.kt')
p = add_after(p, '    private const val KEY_USER_NAME = "user_name"\n', '    private const val KEY_USER_ID = "user_id"\n', 'session key')
snapshot_start = p.index('    data class Snapshot(')
snapshot_end = p.index('    fun save(', snapshot_start)
snapshot = p[snapshot_start:snapshot_end]
snapshot = add_after(snapshot, '        val organizationId: String,\n', '        val userId: String = "",\n', 'snapshot userId')
p = p[:snapshot_start] + snapshot + p[snapshot_end:]
p = add_after(p, '            .putString(KEY_USER_NAME, session.userName)\n', '            .putString(KEY_USER_ID, session.userId)\n', 'save session ID')
p = add_after(p, '            .putString(KEY_USER_NAME, user.name)\n', '            .putString(KEY_USER_ID, user.userId)\n', 'update profile ID')
p = add_after(p, '            organizationId = prefs.getString(KEY_ORGANIZATION_ID, "").orEmpty().trim(),\n', '            userId = prefs.getString(KEY_USER_ID, "").orEmpty().trim(),\n', 'load session ID')
p = p.replace(
    '            it.userName.isNotBlank()',
    '            it.userId.isNotBlank() || it.userName.isNotBlank()',
    1,
)
p = sub(
    p,
    r'        return snapshot\.userEmail\.trim\(\)\.lowercase\(\)\n            \.ifBlank \{ PhoneNormalizer\.key\(snapshot\.userPhone\) \}',
    '''        return snapshot.userId.trim()
            .ifBlank { snapshot.userEmail.trim().lowercase() }
            .ifBlank { PhoneNormalizer.key(snapshot.userPhone) }''',
    'profile scope ID',
)
save('CompanySessionStore.kt', p)

p = load('CompanyAccountSessionPersistence.kt')
p = add_after(p, '        return incoming.copy(\n', '            userId = incoming.userId.trim().ifBlank { remembered.userId },\n', 'merge remembered ID')
save('CompanyAccountSessionPersistence.kt', p)

# History transport: separate profile/user IDs from broker/employee IDs and read server flags.
p = load('CallReportHistoryLookupClient.kt')
principal_start = p.index('internal data class CallReportHistoryPrincipal(')
principal_end = p.index('\n\n', principal_start)
principal = p[principal_start:principal_end]
principal = principal.replace(
    '    val companies: List<CallReportHistoryCompany> = emptyList(),\n)',
    '    val companies: List<CallReportHistoryCompany> = emptyList(),\n    val profileId: String = "",\n)',
)
p = p[:principal_start] + principal + p[principal_end:]
event_start = p.index('internal data class CallReportHistoryEvent(')
event_end = p.index('\n\n', event_start)
event = p[event_start:event_end]
event = event.replace(
    '    val companyId: String = "",\n)',
    '    val companyId: String = "",\n    val authorProfileId: String = "",\n    val isMine: Boolean? = null,\n    val canEdit: Boolean? = null,\n)',
)
p = p[:event_start] + event + p[event_end:]
p = p.replace(
    '.firstOrNull { it.companies.isNotEmpty() || it.brokerId.isNotBlank() || it.brokerName.isNotBlank() }',
    '.firstOrNull { it.companies.isNotEmpty() || it.profileId.isNotBlank() || it.brokerId.isNotBlank() || it.brokerName.isNotBlank() }',
    1,
)
p = p.replace('return parsePayload(json)', 'return parsePayload(json).withLocalPrincipalFallback(context)', 1)
p = sub(
    p,
    r'        val principal = CallReportHistoryPrincipal\(\n.*?\n        \)\n        val companyNames',
    '''        val principal = CallReportHistoryPrincipal(
            brokerId = principalJson?.text("broker_id", "employee_id").orEmpty(),
            brokerName = principalJson?.text("broker_name", "display_name", "name").orEmpty(),
            companies = companies,
            profileId = principalJson?.text("profile_id", "user_id", "id").orEmpty(),
        )
        val companyNames''',
    'principal parsing',
    re.S,
)
p = add_after(
    p,
    '                        companyId = item.text("company_id"),\n',
    '''                        authorProfileId = item.text(
                            "author_profile_id",
                            "author_user_id",
                            "created_by_profile_id",
                            "created_by_user_id",
                            "note_author_profile_id",
                        ),
                        isMine = item.optionalBoolean("is_mine", "mine", "owned_by_current_user"),
                        canEdit = item.optionalBoolean("can_edit", "editable"),
''',
    'event profile fields',
)
p = p.replace(
    'authorBrokerId = item.text("author_broker_id", "created_by_broker_id", "note_author_broker_id"),',
    'authorBrokerId = item.text("author_broker_id", "created_by_broker_id", "note_author_broker_id", "author_employee_id", "author_id"),',
    1,
)
p = p.replace(
    'authorBrokerName = item.text("author_broker_name", "created_by_broker_name", "note_author_broker_name", "author"),',
    'authorBrokerName = item.text("author_name", "author_broker_name", "created_by_broker_name", "note_author_broker_name", "author"),',
    1,
)
history_helpers = '''    private fun CallReportHistoryLookupResult.withLocalPrincipalFallback(context: Context?): CallReportHistoryLookupResult {
        val session = context?.let { CompanySessionStore.load(it) } ?: return this
        val enriched = principal.copy(
            profileId = principal.profileId.ifBlank { session.userId },
            brokerName = principal.brokerName.ifBlank { session.userName },
        )
        return if (enriched == principal) this else copy(principal = enriched)
    }

    private fun JSONObject.optionalBoolean(vararg keys: String): Boolean? {
        keys.forEach { key ->
            if (!has(key) || isNull(key)) return@forEach
            return when (val value = opt(key)) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> when (value.trim().lowercase()) {
                    "1", "true", "yes", "y" -> true
                    "0", "false", "no", "n" -> false
                    else -> null
                }
                else -> null
            }
        }
        return null
    }

'''
p = p.replace('    private fun JSONObject.text(vararg keys: String): String {\n', history_helpers + '    private fun JSONObject.text(vararg keys: String): String {\n', 1)
save('CallReportHistoryLookupClient.kt', p)

# Cache remains backward compatible: missing new fields decode as blank/null.
p = load('CallReportHistoryDiskCache.kt')
p = add_after(p, '        put("broker_name", brokerName)\n', '        put("profile_id", profileId)\n', 'cache principal write')
p = add_after(p, '        brokerName = optString("broker_name").trim(),\n', '        profileId = optString("profile_id").trim(),\n', 'cache principal read')
p = add_after(
    p,
    '        put("company_id", companyId)\n',
    '        put("author_profile_id", authorProfileId)\n        isMine?.let { put("is_mine", it) }\n        canEdit?.let { put("can_edit", it) }\n',
    'cache event write',
)
p = add_after(
    p,
    '            companyId = optString("company_id").trim(),\n',
    '            authorProfileId = optString("author_profile_id").trim(),\n            isMine = optionalBoolean("is_mine"),\n            canEdit = optionalBoolean("can_edit"),\n',
    'cache event read',
)
cache_helper = '''    private fun JSONObject.optionalBoolean(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase()) {
                "1", "true", "yes" -> true
                "0", "false", "no" -> false
                else -> null
            }
            else -> null
        }
    }

'''
p = p.replace('    private fun CallReportHistoryCompanyMainNote.toJson()', cache_helper + '    private fun CallReportHistoryCompanyMainNote.toJson()', 1)
save('CallReportHistoryDiskCache.kt', p)

# Use the common policy in merged History and Home note views.
p = load('CallReportHistoryMerge.kt')
p = p.replace('isOtherBrokerAuthor(match, principal)', 'CallReportAuthorIdentityPolicy.isOtherAuthor(match, principal)')
p = p.replace('isOtherBrokerAuthor(event, principal)', 'CallReportAuthorIdentityPolicy.isOtherAuthor(event, principal)')
p = p.replace('                editable = !foreignAuthor,', '                editable = CallReportAuthorIdentityPolicy.canEdit(match, principal),', 1)
p = p.replace(
    '                editable = kind == CallReportHistoryRowKind.NOTE && !foreignAuthor,',
    '                editable = kind == CallReportHistoryRowKind.NOTE && CallReportAuthorIdentityPolicy.canEdit(event, principal),',
    1,
)
p = sub(
    p,
    r'    private fun isOtherBrokerAuthor\(.*?\n    private fun legacyTopicCallMatch\(',
    '    private fun legacyTopicCallMatch(',
    'remove history name ownership helper',
    re.S,
)
save('CallReportHistoryMerge.kt', p)

for name, next_marker in (
    ('HomeCallNotesResolver.kt', '    private fun claimedNoteKey('),
    ('HomeCrmClientServerNotes.kt', '\n}'),
):
    p = load(name)
    p = p.replace('isOtherBrokerAuthor(event, principal)', 'CallReportAuthorIdentityPolicy.isOtherAuthor(event, principal)')
    start = p.find('    private fun isOtherBrokerAuthor(')
    if start >= 0:
        end = p.find(next_marker, start)
        if end < 0:
            raise SystemExit(f'{name}: ownership helper end missing')
        p = p[:start] + p[end:]
    save(name, p)

# Mid-call note identity survives pending -> real call -> outbox.
p = load('PendingCallNoteStore.kt')
p = add_after(p, '    val companyId: String = "",\n', '    val authorProfileId: String = "",\n    val authorName: String = "",\n', 'pending model identity')
p = add_after(p, '        companyId: String = "",\n', '        authorProfileId: String = "",\n        authorName: String = "",\n', 'pending save identity args')
p = add_after(
    p,
    '        val now = System.currentTimeMillis()\n',
    '        val session = CompanySessionStore.load(context.applicationContext)\n        val resolvedAuthorId = authorProfileId.trim().ifBlank { session?.userId.orEmpty() }\n        val resolvedAuthorName = authorName.trim().ifBlank { session?.userName.orEmpty() }\n',
    'pending resolve identity',
)
p = add_after(p, '            if (companyId.trim().isNotBlank()) put("company_id", companyId.trim())\n', '            if (resolvedAuthorId.isNotBlank()) put("author_profile_id", resolvedAuthorId)\n            if (resolvedAuthorName.isNotBlank()) put("author_name", resolvedAuthorName)\n', 'pending JSON identity')
p = add_after(p, '            companyId = json.optString("company_id").trim(),\n', '            authorProfileId = json.optString("author_profile_id").trim(),\n            authorName = json.optString("author_name").trim(),\n', 'pending read identity')
p = add_after(p, '                companyId = pending.companyId,\n', '                authorProfileId = pending.authorProfileId,\n                authorName = pending.authorName,\n', 'pending reconcile identity')
save('PendingCallNoteStore.kt', p)

p = load('CallNoteTopicWriter.kt')
p = add_after(p, '        existingClientEventId: String = "",\n', '        authorProfileId: String = "",\n        authorName: String = "",\n', 'topic writer identity args')
p = add_after(p, '                companyId = companyId,\n', '                authorProfileId = authorProfileId,\n                authorName = authorName,\n', 'topic pending identity')
# The next companyId occurrence is the outbox call.
needle = '            existingClientEventId = existingClientEventId,\n'
p = add_after(p, needle, '            authorProfileId = authorProfileId,\n            authorName = authorName,\n', 'topic outbox identity')
p = sub(
    p,
    r'(    private fun saveAsPendingCallNote\(.*?        companyId: String,\n)(    \): CallNoteWriteResult \{)',
    r'\1        authorProfileId: String,\n        authorName: String,\n\2',
    'topic pending signature',
    re.S,
)
# Add identity to the PendingCallNoteStore call inside the private function.
private_start = p.index('    private fun saveAsPendingCallNote(')
private_part = p[private_start:]
private_part = add_after(private_part, '            companyId = companyId,\n', '            authorProfileId = authorProfileId,\n            authorName = authorName,\n', 'topic pending store identity')
p = p[:private_start] + private_part
save('CallNoteTopicWriter.kt', p)

p = load('CompanyCallNoteOutbox.kt')
p = add_after(p, '    val updatedAtMs: Long,\n', '    val authorProfileId: String = "",\n    val authorName: String = "",\n', 'company outbox model identity')
p = add_after(
    p,
    '        companyId = companyId,\n',
    '        authorProfileId = authorProfileId,\n        authorBrokerName = authorName,\n        isMine = true,\n        canEdit = true,\n',
    'company pending history identity',
)
p = add_after(p, '        existingClientEventId: String = "",\n', '        authorProfileId: String = "",\n        authorName: String = "",\n', 'company enqueue identity args')
p = add_after(p, '        val operation = QueuedCompanyCallNote(\n', '', 'company operation anchor')
p = p.replace('        val operation = QueuedCompanyCallNote(\n', '        val session = CompanySessionStore.load(appContext)\n        val operation = QueuedCompanyCallNote(\n', 1)
p = add_after(p, '            updatedAtMs = System.currentTimeMillis(),\n', '            authorProfileId = authorProfileId.trim().ifBlank { session?.userId.orEmpty() },\n            authorName = authorName.trim().ifBlank { session?.userName.orEmpty() },\n', 'company operation identity')
p = add_after(p, '        put("updated_at_ms", updatedAtMs)\n', '        if (authorProfileId.isNotBlank()) put("author_profile_id", authorProfileId)\n        if (authorName.isNotBlank()) put("author_name", authorName)\n', 'company JSON identity')
p = add_after(p, '            updatedAtMs = optLong("updated_at_ms", 0L).takeIf { it > 0L } ?: System.currentTimeMillis(),\n', '            authorProfileId = optString("author_profile_id").trim(),\n            authorName = optString("author_name").trim(),\n', 'company JSON read identity')
save('CompanyCallNoteOutbox.kt', p)

TEST.mkdir(parents=True, exist_ok=True)
(TEST / 'CallReportAuthorIdentityPolicyTest.kt').write_text('''package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallReportAuthorIdentityPolicyTest {
    @Test
    fun sameProfileIdStaysMineAfterNameChange() {
        val principal = CallReportHistoryPrincipal(profileId = "profile-12", brokerName = "Ново име")
        val event = CallReportHistoryEvent(authorProfileId = "profile-12", authorBrokerName = "Старо име")
        assertFalse(CallReportAuthorIdentityPolicy.isOtherAuthor(event, principal))
        assertTrue(CallReportAuthorIdentityPolicy.canEdit(event, principal))
    }

    @Test
    fun differentProfileIdWinsOverMatchingName() {
        val principal = CallReportHistoryPrincipal(profileId = "profile-12", brokerName = "Светослав")
        val event = CallReportHistoryEvent(authorProfileId = "profile-99", authorBrokerName = "Светослав")
        assertTrue(CallReportAuthorIdentityPolicy.isOtherAuthor(event, principal))
        assertFalse(CallReportAuthorIdentityPolicy.canEdit(event, principal))
    }

    @Test
    fun authoritativeMineFlagWinsOverLegacyNamespaceMismatch() {
        val principal = CallReportHistoryPrincipal(profileId = "profile-12", brokerId = "employee-5")
        val event = CallReportHistoryEvent(authorProfileId = "legacy", authorBrokerId = "legacy", isMine = true, canEdit = true)
        assertFalse(CallReportAuthorIdentityPolicy.isOtherAuthor(event, principal))
        assertTrue(CallReportAuthorIdentityPolicy.canEdit(event, principal))
    }

    @Test
    fun brokerIdIsASeparateLegacyFallback() {
        val principal = CallReportHistoryPrincipal(brokerId = "employee-5", brokerName = "Ново име")
        assertFalse(CallReportAuthorIdentityPolicy.isOtherAuthor(CallReportHistoryEvent(authorBrokerId = "employee-5", authorBrokerName = "Старо име"), principal))
        assertTrue(CallReportAuthorIdentityPolicy.isOtherAuthor(CallReportHistoryEvent(authorBrokerId = "employee-8", authorBrokerName = "Ново име"), principal))
    }
}
''')

# Final diff must contain no inspection or patch machinery.
if Path('.agent').exists():
    shutil.rmtree('.agent')
for path in Path('.github/workflows').glob('inspect-note-*.yml'):
    path.unlink()
