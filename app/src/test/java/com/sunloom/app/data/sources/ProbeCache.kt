package com.sunloom.app.data.sources

/** A tab-separated record of the candidate sizes the live probe fetched, so it need not fetch them again. */
internal object ProbeCache {

    data class Entry(val sourceId: String, val category: String, val candidateId: String, val width: Int, val height: Int)

    const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    fun encode(entries: List<Entry>): String =
        entries.joinToString("\n") { "${it.sourceId}\t${it.category}\t${it.candidateId}\t${it.width}\t${it.height}" }

    fun decode(text: String): List<Entry> = text.lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size != 5) return@mapNotNull null
        val w = parts[3].toIntOrNull() ?: return@mapNotNull null
        val h = parts[4].toIntOrNull() ?: return@mapNotNull null
        Entry(parts[0], parts[1], parts[2], w, h)
    }.toList()

    fun isUsable(ageMs: Long, refreshRequested: Boolean, hasEntries: Boolean): Boolean =
        hasEntries && !refreshRequested && ageMs in 0..MAX_AGE_MS
}
