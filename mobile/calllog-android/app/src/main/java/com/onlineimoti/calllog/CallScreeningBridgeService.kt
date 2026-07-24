package com.onlineimoti.calllog

import android.net.Uri
import android.telecom.Call
import android.telecom.CallScreeningService

class CallScreeningBridgeService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(false)
                .setRejectCall(false)
                .setSilenceCall(false)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        )

        // The service remains neutral for everyone. CRM processing begins only
        // after the device has a signed-in company session.
        if (!CorporateAccess.isActive(this)) {
            CallPopupDiagnosticsStore.recordCallScreening(
                context = this,
                number = callDetails.handle?.schemeSpecificPart.orEmpty(),
                direction = callDetails.callDirection.toString(),
                handled = false,
                reason = "няма активна сървърна/корпоративна сесия",
            )
            return
        }

        val handle: Uri = callDetails.handle ?: run {
            CallPopupDiagnosticsStore.recordCallScreening(this, "", callDetails.callDirection.toString(), false, "празен call handle")
            return
        }
        val number = handle.schemeSpecificPart?.trim().orEmpty()
        if (number.isBlank()) {
            CallPopupDiagnosticsStore.recordCallScreening(this, number, callDetails.callDirection.toString(), false, "празен номер от CallScreeningService")
            return
        }

        val direction = when (callDetails.callDirection) {
            Call.Details.DIRECTION_INCOMING -> "in"
            Call.Details.DIRECTION_OUTGOING -> "out"
            else -> {
                CallPopupDiagnosticsStore.recordCallScreening(this, number, callDetails.callDirection.toString(), false, "неподдържана посока")
                return
            }
        }

        CallPopupDiagnosticsStore.recordCallScreening(
            context = this,
            number = number,
            direction = direction,
            handled = true,
            reason = "пускам един прогресивен popup от активната Caller ID/спам роля",
        )
        CallLifecycleStore.markActive(this, number, direction)

        val config = ConfigStore.load(applicationContext)
        IncomingCallLookupCoordinator(
            context = applicationContext,
            config = config,
            phone = number,
            direction = direction,
            fullscreen = direction == "in",
            onLookupFinished = {},
        ).start()
    }
}
