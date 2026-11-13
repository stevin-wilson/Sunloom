package com.sunloom.app.data.sources

/**
 * Source APIs sometimes send the literal text "null" instead of a JSON null.
 * Returns the trimmed value, or null when it is blank or that placeholder.
 */
internal fun String.cleanedOrNull(): String? =
    trim().takeIf { it.isNotEmpty() && it != "null" }

/** Every entry of the array as clean text (see [cleanedOrNull]), in order, skipping unusable ones. */
internal fun SimpleJson.JsonArr.cleanStrings(): List<String> =
    (0 until size).mapNotNull { str(it, "").cleanedOrNull() }
