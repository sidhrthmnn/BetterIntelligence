package com.example

import com.example.grounding.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Fully specified synthetic claims, not model-generated accuracy scores. The
 * unchanged numeric-only legacy validator is the latest-main baseline. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClaimEvaluationTest {
    @Test fun compareDeterministicChecksOnSupportedAndAdversarialClaims() {
        val source = EvidenceSource(1, "Orion documentation", "https://docs.example/orion",
            "Orion requires 4 GB RAM. Orion does not support Vulkan. Orion supports OpenCL only with the vendor SDK. " +
                "Orion is faster than Vega on the tested device. Orion has 2 CPU threads.", 1)
        val cases = listOf(
            "Orion requires 4 GB RAM [1]." to true,
            "Orion does not support Vulkan [1]." to true,
            "Orion supports OpenCL only with the vendor SDK [1]." to true,
            "Orion has 2 CPU threads [1]." to true,
            "Orion is faster than Vega on the tested device [1]." to true,
            "1. Orion requires 4 GB RAM [1]." to true,
            "Orion supports Vulkan [1]." to false,
            "Orion requires 4 MB RAM [1]." to false,
            "Vega requires 4 GB RAM [1]." to false,
            "Orion requires 8 GB RAM [1]." to false,
            "Orion has 4 CPU threads [1]." to false,
            "Orion requires 2 GB RAM [1]." to false,
            "Vega is faster than Orion [1]." to false,
            "Orion supports OpenCL [1]." to false,
            "Orion always supports OpenCL on every device [1]." to false,
            "Orion requires 4 GB RAM [99]." to false,
            "Orion requires 4 GB RAM https://invented.example/proof [1]." to false,
            "I downloaded Orion with 4 GB RAM [1]." to false,
            "Orion can perfectly replace every runtime [1]." to false,
            "Orion needs no RAM [1]." to false,
            "Vega has 2 CPU threads [1]." to false,
            "Orion supports Vulkan and needs 4 GB RAM [1]." to false,
            "Orion requires 4 GB RAM [1, 99]." to false,
            "Orion was published in 2026 [1]." to false)
        val rows = JSONArray(); var oldPass = 0; var newPass = 0
        cases.forEachIndexed { index, (claim, expected) ->
            val old = GroundingPolicy.validateCitations(claim, listOf(source)).second
            val started = System.nanoTime()
            val checked = GroundingPolicy.checkAnswer(claim, listOf(source), "Compare Orion vs Vega")
            val elapsed = (System.nanoTime() - started) / 1_000_000.0
            val actual = checked.support == AnswerSupport.CITED
            if (old == expected) oldPass++
            if (actual == expected) newPass++
            rows.put(JSONObject().put("id", index + 1).put("claim", claim).put("expectedAccepted", expected)
                .put("legacyAccepted", old).put("claimChecksAccepted", actual).put("checkMsJvm", elapsed))
            assertEquals(claim + ": " + checked.claims.joinToString { it.reason }, expected, actual)
        }
        val output = File(System.getProperty("bi.claim.report", "build/reports/claim-checks.json"))
        output.parentFile.mkdirs(); output.writeText(JSONObject().put("scope", "24 synthetic deterministic claim fixtures; not factual accuracy or Android latency")
            .put("baselineRef", "b90955b").put("legacyPass", oldPass).put("newPass", newPass).put("cases", rows).toString(2))
    }
}
