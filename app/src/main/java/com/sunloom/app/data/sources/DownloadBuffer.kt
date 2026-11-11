package com.sunloom.app.data.sources

/**
 * Collects a download in one array. When the server announces the length, the array is that size and, if the body is
 * exactly that long, is handed over as it is: no second copy of the payload (a growing stream followed by `toByteArray()`
 * held a 35 MB picture twice). A body that is shorter or longer than announced still works, at the cost of one copy or a
 * few growths. [maxBytes] is enforced as bytes arrive, so an oversized body is refused before it is all in memory.
 */
internal class DownloadBuffer(expectedLength: Long, private val maxBytes: Int) {
    var backingArray: ByteArray = ByteArray(initialCapacity(expectedLength, maxBytes))
        private set
    private var size = 0

    fun append(source: ByteArray, offset: Int, length: Int) {
        require(size.toLong() + length <= maxBytes) { "Asset exceeds maximum permitted download size ($maxBytes bytes)" }
        if (size + length > backingArray.size) {
            val grown = maxOf(size + length, backingArray.size * 2).coerceAtMost(maxBytes)
            backingArray = backingArray.copyOf(grown)
        }
        System.arraycopy(source, offset, backingArray, size, length)
        size += length
    }

    fun toByteArray(): ByteArray = if (size == backingArray.size) backingArray else backingArray.copyOf(size)

    private companion object {
        const val UNKNOWN_LENGTH_START = 64 * 1024

        fun initialCapacity(expected: Long, maxBytes: Int): Int =
            if (expected in 1..maxBytes.toLong()) expected.toInt() else minOf(maxBytes, UNKNOWN_LENGTH_START).coerceAtLeast(1)
    }
}
