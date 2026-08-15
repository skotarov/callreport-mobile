package com.onlineimoti.calllog

import android.app.Service
import android.content.Context
import android.os.Handler
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

/** One overlay editor for both the yellow main note and the blue call note. */
internal class PostCallUnifiedNoteEditor(
    private val service: Service,
    private val ui: PostCallOverlayUi,
    private val handler: Handler,
    private val phone: () -> String,
    private val direction: () -> String,
    private val callAt: () -> Long,
    private val durationSeconds: () -> Long,
    private val actionIssuedAt: () -> Long,
    private val preferredCompanyId: () -> String,
    private val setPreferredCompanyId: (String) -> Unit,
    private val initialNoteText: () -> String,
    private val serverClientEventId: () -> String,
    private val setWindowManager: (WindowManager) -> Unit,
    private val removeOverlay: () -> Unit,
    private val addDraggableOverlay: (View, Boolean, Int, Long) -> Unit,
    private val openCalendarEvent: (String) -> Unit,
    private val openContactNotesScreen: () -> Unit,
    private val pendingCallNote: () -> String?,
    private val setPendingCallNote: (String) -> Unit,
    private val pendingGeneralNote: () -> String?,
    private val setPendingGeneralNote: (String) -> Unit,
    private val notifyNotesChanged: () -> Unit,
    private val stopOverlay: () -> Unit,
) {
    fun show(kind: UnifiedNoteKind) {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        setWindowManager(service.getSystemService(Context.WINDOW_SERVICE) as WindowManager)

        val phoneValue = phone()
        val directionValue = direction()
        val callAtValue = callAt()
        val durationValue = durationSeconds()
        val displayName = ContactGroupFilter.resolveDisplayName(service, phoneValue).orEmpty()
        val titleText = displayName.ifBlank {
            phoneValue.ifBlank { if (kind.isGeneral) "Основна бележка" else "Бележка към обаждане" }
        }
        val existingCallNote = if (kind.isGeneral) null else existingCallNote(phoneValue, callAtValue, directionValue)
        val storedPendingNote = if (kind.isGeneral) null else PendingCallNoteStore.pendingForPhone(service, phoneValue)
        val initialCompanyId = preferredCompanyId().trim()
            .ifBlank { existingCallNote?.companyId.orEmpty() }
            .ifBlank { storedPendingNote?.companyId.orEmpty() }
        if (initialCompanyId.isNotBlank()) setPreferredCompanyId(initialCompanyId)
        val draft = ContactNoteFormDraft(
            phone = phoneValue,
            title = titleText,
            direction = if (kind.isGeneral) "" else directionValue,
            callAt = if (kind.isGeneral) 0L else callAtValue,
            durationSeconds = if (kind.isGeneral) 0L else durationValue,
            actionIssuedAt = if (kind.isGeneral) 0L else actionIssuedAt(),
            isGeneralNote = kind.isGeneral,
            serverClientEventId = if (kind.isGeneral) "" else serverClientEventId().trim(),
        )
        val originalText = if (kind.isGeneral) {
            pendingGeneralNote() ?: ContactNoteReader.generalNoteForPhone(service, phoneValue)
        } else {
            pendingCallNote()
                ?: initialNoteText().takeIf { it.isNotBlank() }
                ?: existingCallNote?.note
                ?: storedPendingNote?.note
                ?: ContactNoteReader.callNoteForPhone(service, phoneValue, callAtValue, directionValue)
        }

        fun setPending(text: String) {
            if (kind.isGeneral) setPendingGeneralNote(text) else setPendingCallNote(text)
        }
        val form = OverlayContactNoteFormController(
            service = service,
            handler = handler,
            dp = ui::dp,
            draft = draft,
            preferredCompanyId = initialCompanyId,
        )

        fun saveCurrent(): Boolean {
            setPending(form.focusedText())
            setPreferredCompanyId(form.effectiveCompanyId().ifBlank { initialCompanyId })
            if (!form.hasChanges()) return true
            if (!form.saveAll()) return false
            notifyNotesChanged()
            return true
        }

        fun failureMessage(): String = if (kind.isGeneral) {
            "Не успях да запиша основната бележка"
        } else {
            "Не успях да запиша бележката"
        }

        fun runAfterSave(action: () -> Unit) {
            if (saveCurrent()) action()
            else Toast.makeText(service, failureMessage(), Toast.LENGTH_SHORT).show()
        }

        val built = UnifiedNoteEditorContentUi(service, ui::dp).build(
            state = UnifiedNoteEditorState(
                kind = kind,
                titleText = titleText,
                phone = phoneValue,
                direction = directionValue,
                callAt = callAtValue,
                durationSeconds = durationValue,
                noteText = originalText,
            ),
            callbacks = UnifiedNoteEditorCallbacks(
                switchMode = { target, _ ->
                    runAfterSave { show(target) }
                },
                save = {
                    runAfterSave {
                        Toast.makeText(
                            service,
                            if (kind.isGeneral) "Основните бележки са записани" else "Бележките към обаждането са записани",
                            Toast.LENGTH_SHORT,
                        ).show()
                        stopOverlay()
                    }
                },
                close = {
                    NoteEditorCloseConfirmation.requestOverlay(
                        service = service,
                        hasUnsavedChanges = form.hasChanges(),
                        closeWithoutSaving = stopOverlay,
                    )
                },
                openCalendar = {
                    runAfterSave { openCalendarEvent(titleText) }
                },
                delete = null,
                openHistory = {
                    runAfterSave(action = openContactNotesScreen)
                },
            ),
            beforeInput = { card, input -> form.addTopicFieldTo(card, input) },
        )
        ui.stylePopupCard(built.card)
        addDraggableOverlay(ui.shadowScroll(built.card), true, ui.dp(135), 0L)
        val focusInput = form.focusInput()
        focusInput?.requestFocus()
        handler.postDelayed({
            focusInput?.let { input ->
                (service.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 250)
    }

    private fun existingCallNote(phoneValue: String, callAtValue: Long, directionValue: String): ContactCallNote? {
        if (callAtValue <= 0L) return null
        return ContactNoteReader.callNotesForPhone(service, phoneValue)
            .asSequence()
            .filter { note ->
                note.callAt == callAtValue &&
                    (directionValue.isBlank() || note.direction.isBlank() || note.direction == directionValue)
            }
            .maxByOrNull { note -> maxOf(note.savedAt, note.callAt) }
    }
}
