package dev.hyperdesk

import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** The non-app results of the start menu's search box: sums, phone settings pages, and the web. */
object Search {
    class Result(val icon: Icon, val title: String, val hint: String, val action: (Desk) -> Unit)

    private val settings = listOf(
        "Wi-Fi" to Settings.ACTION_WIFI_SETTINGS,
        "Bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "Display" to Settings.ACTION_DISPLAY_SETTINGS,
        "Sound and volume" to Settings.ACTION_SOUND_SETTINGS,
        "Battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
        "Apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "Storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "Date and time" to Settings.ACTION_DATE_SETTINGS,
        "Language and keyboard" to Settings.ACTION_INPUT_METHOD_SETTINGS,
        "Developer options" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        "Accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
    )

    fun results(query: String): List<Result> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Result>()
        calc(q)?.let { v ->
            val shown = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else "%.8f".format(v).trimEnd('0').trimEnd('.')
            out += Result(Icon.Equals, "$q = $shown", "Enter to copy") { d ->
                d.svc.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("result", shown))
                d.toast("Copied $shown")
            }
        }
        if ("hyperdesk settings".contains(q, ignoreCase = true) && q.length >= 2) {
            out += Result(Icon.Gear, "HyperDesk settings", "Settings") { d -> d.launch(d.ctx.packageName) }
        }
        for ((name, action) in settings) {
            if (q.length >= 2 && name.contains(q, ignoreCase = true)) {
                out += Result(Icon.Gear, name, "Phone settings") { d -> d.startOnDesk(Intent(action)) }
            }
        }
        val looksLikeUrl = !q.contains(' ') && Regex("^[\\w-]+(\\.[\\w-]+)+(/.*)?$").matches(q.removePrefix("https://").removePrefix("http://"))
        if (looksLikeUrl) {
            val url = if (q.startsWith("http")) q else "https://$q"
            out += Result(Icon.Globe, "Open $q", "Website") { d -> d.startOnDesk(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        out += Result(Icon.Globe, "Search the web for “$q”", "Web") { d ->
            d.startOnDesk(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        }
        return out
    }

    /** Evaluates + - * / ^ % and brackets. Null if the text is not a sum (or is just a number). */
    fun calc(text: String): Double? {
        val s = text.replace(" ", "").replace(',', '.').replace('×', '*').replace('÷', '/')
        if (s.isEmpty() || !s.any { it in "+-*/^%" } || !s.all { it.isDigit() || it in "+-*/^%.()" }) return null
        var i = 0
        fun expr(): Double {
            fun atom(): Double {
                if (i < s.length && s[i] == '(') { i++; val v = expr(); if (i < s.length && s[i] == ')') i++ else throw IllegalArgumentException(); return v }
                if (i < s.length && s[i] == '-') { i++; return -atom() }
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                if (start == i) throw IllegalArgumentException()
                var v = s.substring(start, i).toDouble()
                if (i < s.length && s[i] == '%') { i++; v /= 100 }
                return v
            }
            fun power(): Double { val b = atom(); return if (i < s.length && s[i] == '^') { i++; Math.pow(b, power()) } else b }
            fun term(): Double {
                var v = power()
                while (i < s.length && (s[i] == '*' || s[i] == '/')) { val op = s[i++]; val r = power(); v = if (op == '*') v * r else v / r }
                return v
            }
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }
            return v
        }
        return try {
            val v = expr()
            if (i != s.length || v.isNaN() || v.isInfinite()) null else v
        } catch (e: Exception) { null }
    }
}
