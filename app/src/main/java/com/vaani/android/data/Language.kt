package com.vaani.android.data

/**
 * Represents a supported spoken/written language.
 *
 * @param code Bhashini/BCP-47 style language code (e.g. "hi", "ta").
 * @param name English display name.
 * @param nativeName Name rendered in the language's own script.
 * @param script Name of the writing script (e.g. "Devanagari").
 */
data class Language(
    val code: String,
    val name: String,
    val nativeName: String,
    val script: String
) {
    override fun toString(): String = nativeName
}
