package com.vaani.android.data

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Source of truth for the languages Vaani supports, keyed by Bhashini language codes.
 */
@Singleton
class LanguageRepository @Inject constructor() {

    private val languages: List<Language> = listOf(
        Language(code = "hi", name = "Hindi", nativeName = "हिन्दी", script = "Devanagari"),
        Language(code = "ta", name = "Tamil", nativeName = "தமிழ்", script = "Tamil"),
        Language(code = "te", name = "Telugu", nativeName = "తెలుగు", script = "Telugu"),
        Language(code = "bn", name = "Bengali", nativeName = "বাংলা", script = "Bengali"),
        Language(code = "kn", name = "Kannada", nativeName = "ಕನ್ನಡ", script = "Kannada"),
        Language(code = "mr", name = "Marathi", nativeName = "मराठी", script = "Devanagari"),
        Language(code = "gu", name = "Gujarati", nativeName = "ગુજરાતી", script = "Gujarati"),
        Language(code = "pa", name = "Punjabi", nativeName = "ਪੰਜਾਬੀ", script = "Gurmukhi"),
        Language(code = "ml", name = "Malayalam", nativeName = "മലയാളം", script = "Malayalam"),
        Language(code = "or", name = "Odia", nativeName = "ଓଡ଼ିଆ", script = "Odia"),
        Language(code = "as", name = "Assamese", nativeName = "অসমীয়া", script = "Bengali-Assamese"),
        Language(code = "en", name = "English", nativeName = "English", script = "Latin")
    )

    fun getAllLanguages(): List<Language> = languages

    fun getLanguageByCode(code: String): Language? = languages.find { it.code == code }

    fun getDefaultSourceLanguage(): Language = getLanguageByCode("hi") ?: languages.first()

    fun getDefaultTargetLanguage(): Language = getLanguageByCode("en") ?: languages.last()
}
