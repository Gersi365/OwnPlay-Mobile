package app.ownplay.player.testing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceContractTestSupportTest {
    @Test
    fun blockExtractionStopsAtMatchingBraceAndIgnoresLiteralBraces() {
        val source = """
            private fun target() {
                val text = "{ not a block }"
                if (true) {
                    println("nested")
                }
            }

            private fun later() {
                println("later")
            }
        """.trimIndent()

        val body = sourceBlockAfter(source, "private fun target()")

        assertTrue(body.contains("println(\"nested\")"))
        assertFalse(body.contains("println(\"later\")"))
    }

    @Test
    fun expressionExtractionIgnoresDefaultParameterEqualsAndStopsBeforeNextDeclaration() {
        val source = """
            private fun target(
                value: Int = 1,
            ): Boolean =
                call(
                    value = value,
                ) > 0

            private fun later() {
                updateTransfer()
            }
        """.trimIndent()

        val body = sourceExpressionAfter(source, "private fun target(")

        assertTrue(body.contains("call("))
        assertTrue(body.contains("value = value"))
        assertFalse(body.contains("updateTransfer"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blockExtractionRejectsExpressionBodyInsteadOfScanningIntoNextFunction() {
        val source = """
            private fun target(): Boolean = call() > 0

            private fun later() {
                updateTransfer()
            }
        """.trimIndent()

        sourceBlockAfter(source, "private fun target()")
    }
}
