package com.sunloom.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [Property.forAll] runs a check against many random inputs from a fixed seed. A failure must say enough to reproduce it:
 * which case, the seed that replays just that case, and what the input was.
 */
class PropertyTest {

    private fun failureOf(block: () -> Unit): AssertionError {
        try {
            block()
        } catch (e: AssertionError) {
            return e
        }
        fail("expected the property to fail")
        throw IllegalStateException()
    }

    @Test
    fun `a property that always holds runs every case and passes`() {
        var runs = 0
        Property.forAll("always", cases = 50) { runs++ }
        assertEquals(50, runs)
    }

    @Test
    fun `the same seed gives the same inputs and a different seed gives different ones`() {
        fun inputs(seed: Long): List<Int> = mutableListOf<Int>().also { out -> Property.forAll("draw", cases = 20, seed = seed) { out += it.int(0, 1_000_000) } }
        assertEquals(inputs(7), inputs(7))
        assertNotEquals(inputs(7), inputs(8))
    }

    @Test
    fun `a failure names the property, the case, the seed to replay it and the input`() {
        val error = failureOf {
            Property.forAll("small numbers only", cases = 200) { case ->
                val n = case.int(0, 100)
                case.input = "n=$n"
                assertTrue("$n is not small", n < 90)
            }
        }
        val message = error.message.orEmpty()
        assertTrue(message, "small numbers only" in message)
        assertTrue(message, Regex("case #\\d+").containsMatchIn(message))
        assertTrue(message, Regex("seed=-?\\d+").containsMatchIn(message))
        assertTrue(message, Regex("n=(9\\d|100)").containsMatchIn(message))
        assertNotNull("the original failure is kept as the cause", error.cause)
    }

    @Test
    fun `the reported seed replays exactly the failing case`() {
        val first = failureOf {
            Property.forAll("find a big one", cases = 300) { case ->
                val n = case.int(0, 1_000)
                case.input = "n=$n"
                assertTrue("big", n < 990)
            }
        }.message.orEmpty()
        val seed = Regex("seed=(-?\\d+)").find(first)!!.groupValues[1].toLong()
        val input = Regex("n=\\d+").find(first)!!.value

        val replay = failureOf {
            Property.forAll("find a big one", cases = 1, seed = seed) { case ->
                val n = case.int(0, 1_000)
                case.input = "n=$n"
                assertTrue("big", n < 990)
            }
        }.message.orEmpty()
        assertEquals(input, Regex("n=\\d+").find(replay)!!.value)
    }

    @Test
    fun `asking for no cases is refused instead of passing without checking anything`() {
        try {
            Property.forAll("nothing", cases = 0) { }
            fail("a property with no cases must not pass")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `a whole number can be drawn right up to the ends of the Int range`() {
        Property.forAll("top of the range", cases = 50) { assertTrue(it.int(Int.MAX_VALUE - 5, Int.MAX_VALUE) >= Int.MAX_VALUE - 5) }
        Property.forAll("bottom of the range", cases = 50) { assertTrue(it.int(Int.MIN_VALUE, Int.MIN_VALUE + 5) <= Int.MIN_VALUE + 5) }
    }

    @Test
    fun `an error such as running out of memory is not dressed up as a failed property`() {
        try {
            Property.forAll("oom", cases = 1) { throw OutOfMemoryError("boom") }
            fail("expected the error to escape")
        } catch (e: OutOfMemoryError) {
            assertEquals("boom", e.message)
        }
    }

    @Test
    fun `a crash inside the check is reported with its input like any other failure`() {
        val error = failureOf {
            Property.forAll("never crashes", cases = 10) { case ->
                case.input = "boom"
                throw IllegalArgumentException("Cannot round NaN value.")
            }
        }
        val message = error.message.orEmpty()
        assertTrue(message, "never crashes" in message && "boom" in message && "Cannot round NaN value." in message)
    }
}
