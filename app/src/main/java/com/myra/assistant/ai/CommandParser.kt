package com.myra.assistant.ai

import com.myra.assistant.model.AppCommand

object CommandParser {

    fun parse(text: String): AppCommand? {
        val t = text.lowercase().trim()

        // ─── App Open ───
        val openKeywords = listOf("kholo", "open", "chalu karo", "start karo", "launch")
        val closeKeywords = listOf("band karo", "close", "band kar", "hatao")

        for (kw in openKeywords) {
            if (t.contains(kw)) {
                val appName = extractAppName(t) ?: return null
                return AppCommand(AppCommand.OPEN_APP, mapOf("app_name" to appName))
            }
        }

        for (kw in closeKeywords) {
            if (t.contains(kw)) {
                val appName = extractAppName(t)
                return AppCommand(AppCommand.CLOSE_APP,
                    if (appName != null) mapOf("app_name" to appName) else emptyMap())
            }
        }

        // ─── Prime Contact ───
        if (t.contains("close friend") || t.contains("mere close friend") ||
            t.contains("meri jaan") && (t.contains("call") || t.contains("baat"))) {
            return AppCommand(AppCommand.PRIME_CALL, mapOf("index" to "0"))
        }

        if (t.contains("close friend") && (t.contains("msg") || t.contains("message") ||
            t.contains("whatsapp"))) {
            return AppCommand(AppCommand.PRIME_MSG, mapOf("index" to "0"))
        }

        if (t.contains("second contact") || t.contains("doosra contact")) {
            if (t.contains("call")) return AppCommand(AppCommand.PRIME_CALL, mapOf("index" to "1"))
            if (t.contains("msg") || t.contains("message"))
                return AppCommand(AppCommand.PRIME_MSG, mapOf("index" to "1"))
        }

        // ─── Call ───
        if (t.contains("call karo") || t.contains("call kar") ||
            (t.contains("call") && !t.contains("incoming"))) {
            val name = extractContactName(t) ?: return null
            if (name.contains("close friend") || name.contains("jaan")) return null
            return AppCommand(AppCommand.CALL, mapOf("name" to name))
        }

        // ─── SMS ───
        if (t.contains("sms") || t.contains("message bhejo") || t.contains("msg karo")) {
            val name = extractContactName(t) ?: return null
            val msg = extractMessageContent(t)
            return AppCommand(AppCommand.SMS, mapOf("name" to name, "message" to (msg ?: "")))
        }

        // ─── WhatsApp ───
        if (t.contains("whatsapp") || t.contains("whatsapp karo")) {
            val name = extractContactName(t) ?: return null
            return if (t.contains("call"))
                AppCommand(AppCommand.WHATSAPP_CALL, mapOf("name" to name))
            else
                AppCommand(AppCommand.WHATSAPP_MSG, mapOf("name" to name))
        }

        // ─── Volume ───
        if (t.contains("volume badhao") || t.contains("volume up") ||
            t.contains("awaaz badhao") || t.contains("loud karo"))
            return AppCommand(AppCommand.VOLUME_UP)

        if (t.contains("volume kam") || t.contains("volume down") ||
            t.contains("awaaz kam") || t.contains("silent karo"))
            return AppCommand(AppCommand.VOLUME_DOWN)

        // ─── Flashlight ───
        if ((t.contains("torch") || t.contains("flashlight")) && t.contains("on"))
            return AppCommand(AppCommand.FLASHLIGHT_ON)
        if ((t.contains("torch") || t.contains("flashlight")) && t.contains("off"))
            return AppCommand(AppCommand.FLASHLIGHT_OFF)

        // ─── WiFi ───
        if (t.contains("wifi on") || t.contains("wifi chalu"))
            return AppCommand(AppCommand.WIFI_ON)
        if (t.contains("wifi off") || t.contains("wifi band"))
            return AppCommand(AppCommand.WIFI_OFF)

        // ─── Bluetooth ───
        if (t.contains("bluetooth on") || t.contains("bluetooth chalu"))
            return AppCommand(AppCommand.BLUETOOTH_ON)
        if (t.contains("bluetooth off") || t.contains("bluetooth band"))
            return AppCommand(AppCommand.BLUETOOTH_OFF)

        return null
    }

    private fun extractAppName(text: String): String? {
        val apps = listOf(
            "youtube", "whatsapp", "instagram", "facebook", "chrome",
            "gmail", "maps", "spotify", "netflix", "twitter", "telegram",
            "snapchat", "settings", "calculator", "calendar", "clock",
            "phone", "contacts", "play store", "amazon", "flipkart",
            "paytm", "phonepe", "gpay", "zoom", "meet", "teams",
            "tiktok", "discord", "linkedin", "camera", "gallery"
        )
        return apps.firstOrNull { text.contains(it) }
    }

    private fun extractContactName(text: String): String? {
        val patterns = listOf(
            Regex("call ([a-zA-Z]+) ko"),
            Regex("([a-zA-Z]+) ko call"),
            Regex("call ([a-zA-Z]+)"),
            Regex("message ([a-zA-Z]+) ko"),
            Regex("([a-zA-Z]+) ko message"),
            Regex("whatsapp ([a-zA-Z]+) ko"),
            Regex("([a-zA-Z]+) ko whatsapp")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private fun extractMessageContent(text: String): String? {
        val idx = text.indexOf("message:")
        if (idx != -1) return text.substring(idx + 8).trim()
        val idx2 = text.indexOf("bolna hai")
        if (idx2 != -1) return text.substring(idx2 + 9).trim()
        return null
    }
}
