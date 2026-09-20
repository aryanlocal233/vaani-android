package com.vaani.android.translation

/**
 * Describes the two languages a conversation session translates between.
 */
data class TranslationSession(
    val srcLang: String,
    val tgtLang: String,
    val srcLangName: String,
    val tgtLangName: String
)
