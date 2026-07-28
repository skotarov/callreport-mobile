package com.onlineimoti.calllog

import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal class MainChatSettingsController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
) {
    fun wire() {
        val enabled = ChatAppVisibilityStore.enabledApps(activity).toSet()
        checkBoxes().forEach { (app, checkBox) ->
            checkBox.setOnCheckedChangeListener(null)
            checkBox.isChecked = app in enabled
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                ChatAppVisibilityStore.setEnabled(activity, app, isChecked)
            }
        }
    }

    private fun checkBoxes() = with(binding.settingsChatsGroup) {
        listOf(
            ChatApp.VIBER to viberChatCheckBox,
            ChatApp.WHATSAPP to whatsappChatCheckBox,
            ChatApp.TELEGRAM to telegramChatCheckBox,
            ChatApp.MESSENGER to messengerChatCheckBox,
            ChatApp.SIGNAL to signalChatCheckBox,
            ChatApp.INSTAGRAM to instagramChatCheckBox,
            ChatApp.MESSAGES to messagesChatCheckBox,
            ChatApp.SNAPCHAT to snapchatChatCheckBox,
            ChatApp.DISCORD to discordChatCheckBox,
            ChatApp.WECHAT to wechatChatCheckBox,
            ChatApp.LINE to lineChatCheckBox,
            ChatApp.KAKAOTALK to kakaoTalkChatCheckBox,
            ChatApp.QQ to qqChatCheckBox,
        )
    }
}
