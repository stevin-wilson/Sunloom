package com.sunloom.app

import java.util.Locale

/**
 * Golden files compare text that contains floating-point numbers. `pow`, `ln` and friends may differ in the last
 * digit between CPU architectures (a golden recorded on ARM must also pass on an x86 CI runner), so numbers with a
 * long fraction are rounded to nine significant digits on both sides before comparing.
 */
object GoldenText {
    private val longNumber = Regex("-?\\d+\\.\\d{9,}(?:[eE][-+]?\\d+)?")

    fun normalize(text: String): List<String> =
        text.lines().map { line ->
            longNumber.replace(line) { String.format(Locale.ROOT, "%.9e", it.value.toDouble()) }
        }
}
