package com.onlineimoti.calllog

import android.app.Application

class RelationshipManagerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PhoneCountrySettingsStore.load(this)
    }
}
