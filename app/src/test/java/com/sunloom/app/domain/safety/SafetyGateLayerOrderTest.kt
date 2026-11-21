package com.sunloom.app.domain.safety

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.SafetyVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article VII §4(a): "Every asset passes every layer, in order. Any layer may reject. No single layer may approve on its
 * own." Each test changes one thing about an asset that otherwise passes everything, and checks which layer answers.
 */
@Requirement("VII.4.a")
class SafetyGateLayerOrderTest {

    private val gate = SafetyGate()
    private val good = SafetyFixtures.jpeg()
    private val bounds = SafetyFixtures.ordinaryBounds

    private val unadmitted = SafetyFixtures.candidate(sourceId = "not_a_real_source")
    private val unsafeMetadata = SafetyFixtures.candidate(tags = listOf("landscape", "mountains", "lake", "nude"))

    private fun judge(candidate: com.sunloom.app.domain.model.CandidateAsset, payload: ByteArray = good) =
        gate.evaluatePayload(candidate, payload, headerBounds = if (payload === good) bounds else null)

    private fun layerOf(verdict: SafetyVerdict): String? = (verdict as? SafetyVerdict.Rejected)?.failedLayer

    @Test
    fun `an asset with no fault in any layer is approved, so the other tests change exactly one thing`() {
        val verdict = judge(SafetyFixtures.candidate())
        assertTrue("expected approval, got $verdict", verdict is SafetyVerdict.Approved)
    }

    @Test
    fun `each layer can refuse an asset that every other layer would pass`() {
        assertEquals("L1", layerOf(judge(unadmitted)))
        assertEquals("L2", layerOf(judge(unsafeMetadata)))
        assertEquals("L3", layerOf(judge(SafetyFixtures.candidate(), SafetyFixtures.notAPicture())))
    }

    @Test
    fun `a clean payload cannot rescue an asset the metadata layers refuse`() {
        val withPayload = judge(unsafeMetadata) as SafetyVerdict.Rejected
        val metadataOnly = gate.evaluateMetadata(unsafeMetadata) as SafetyVerdict.Rejected
        // The same layer gives the same reason whether or not a payload came with it.
        assertEquals(metadataOnly.failedLayer, withPayload.failedLayer)
        assertEquals(metadataOnly.reasonCode, withPayload.reasonCode)
        assertEquals("L1", layerOf(judge(unadmitted)))
    }

    @Test
    fun `passing the metadata layers is not an approval, the payload layer has to pass too and is recorded`() {
        val metadataOnly = gate.evaluateMetadata(SafetyFixtures.candidate()) as SafetyVerdict.Approved
        assertTrue("metadata alone records only L1 and L2: ${metadataOnly.gateLayerOutcomes}", !metadataOnly.gateLayerOutcomes.contains("L3"))
        val full = judge(SafetyFixtures.candidate()) as SafetyVerdict.Approved
        assertEquals("L1:PASS;L2:PASS;L3:PASS", full.gateLayerOutcomes)
    }

    @Test
    fun `when several layers would refuse, the earliest is the one that answers`() {
        val badEverywhere = SafetyFixtures.candidate(sourceId = "not_a_real_source", tags = listOf("landscape", "nude"))
        assertEquals("L1", layerOf(judge(badEverywhere, SafetyFixtures.notAPicture())))
        assertEquals("L2", layerOf(judge(unsafeMetadata, SafetyFixtures.notAPicture())))
    }
}
