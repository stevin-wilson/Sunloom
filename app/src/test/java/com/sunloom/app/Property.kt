package com.sunloom.app

import kotlin.random.Random

/**
 * A small property-based testing helper: run a check against many random inputs drawn from a fixed seed.
 *
 * Example-based tests pick the inputs a person thought of; properties state a rule (a crop always lies inside the
 * picture) and let many inputs look for the case nobody thought of. There is no external library: the seed is fixed so
 * a run is the same every time, and a failure reports the case, the seed that replays just that case, and the input.
 * There is no shrinking; keep generated inputs small and readable instead.
 *
 * ```
 * Property.forAll("a crop lies inside the picture") { case ->
 *     val w = case.int(1, 4000)
 *     case.input = "w=$w"          // what a failure should show
 *     assertTrue(crop(w).right <= w)
 * }
 * ```
 * To replay a reported failure: `Property.forAll(name, cases = 1, seed = <the reported seed>) { ... }`.
 */
object Property {
    const val DEFAULT_SEED = 20261121L

    /** One generated case. Describe what was generated in [input] so a failure can show it. */
    class Case(val random: Random, val number: Int, val seed: Long) {
        var input: String = ""

        /** A whole number from [from] to [to], both included (the ends of the Int range work). */
        fun int(from: Int, to: Int): Int {
            require(from <= to) { "empty range $from..$to" }
            return random.nextLong(from.toLong(), to.toLong() + 1).toInt()
        }

        /** A number from [from] up to (not including) [until]. */
        fun double(from: Double, until: Double): Double = random.nextDouble(from, until)

        fun boolean(): Boolean = random.nextBoolean()

        /** One of [items], chosen evenly. */
        fun <T> oneOf(items: List<T>): T = items[random.nextInt(items.size)]
    }

    private fun failure(name: String, case: Case, cause: Throwable): AssertionError {
        val shown = case.input.ifEmpty { "(the check did not describe its input)" }
        return AssertionError(
            "property '$name' failed on case #${case.number} (replay: seed=${case.seed}, cases=1) for input $shown: " +
                "${cause::class.simpleName}: ${cause.message}",
            cause
        )
    }

    /** Runs [check] for [cases] cases; case `i` is seeded with `seed + i`, so any one case can be replayed alone. */
    fun forAll(name: String, cases: Int = 300, seed: Long = DEFAULT_SEED, check: (Case) -> Unit) {
        require(cases > 0) { "a property needs at least one case, or it checks nothing" }
        for (number in 0 until cases) {
            val caseSeed = seed + number
            val case = Case(Random(caseSeed), number, caseSeed)
            try {
                check(case)
            } catch (e: AssertionError) {
                throw failure(name, case, e)
            } catch (e: Exception) {
                // A crash in the code under test counts as a failed property; a real Error (out of memory) does not.
                throw failure(name, case, e)
            }
        }
    }
}
