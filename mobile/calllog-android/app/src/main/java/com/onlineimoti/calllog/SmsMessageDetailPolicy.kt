package com.onlineimoti.calllog

/** Shared behavior for opening one SMS in a full-message viewer. */
internal object SmsMessageDetailPolicy {
    fun isOutgoing(direction: String): Boolean = direction == "sms_out" || direction == "out"

    fun canReplyTo(address: String): Boolean = CommunicationAddress.from(address).isPhone

    fun directionLabel(direction: String): String {
        val outgoing = isOutgoing(direction)
        return when {
            outgoing && AppLocaleText.isBulgarian() -> "изпратено"
            !outgoing && AppLocaleText.isBulgarian() -> "получено"
            outgoing -> "sent"
            else -> "received"
        }
    }
}
