package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsMessageDetailPolicyTest {
    @Test
    fun numericSmsAddressCanOpenReplyComposer() {
        assertTrue(SmsMessageDetailPolicy.canReplyTo("0888123456"))
    }

    @Test
    fun alphanumericSenderIsViewOnly() {
        assertFalse(SmsMessageDetailPolicy.canReplyTo("MyBank"))
    }

    @Test
    fun recognizesBothOutgoingDirectionFormats() {
        assertTrue(SmsMessageDetailPolicy.isOutgoing("sms_out"))
        assertTrue(SmsMessageDetailPolicy.isOutgoing("out"))
        assertFalse(SmsMessageDetailPolicy.isOutgoing("sms_in"))
    }
}
