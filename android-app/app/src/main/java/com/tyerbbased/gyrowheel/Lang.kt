package com.tyerbbased.gyrowheel

object Lang {
    @Volatile var current: String = "en"

    fun s(en: String, uk: String): String = if (current == "uk") uk else en
}
