package app.ownplay.player.persistence.secure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SensitiveValueStoreTest {
    @Test
    fun deleteAllAttemptsEveryDistinctReferenceBeforeRethrowingFirstFailure() {
        val first = SensitiveValueRef("first")
        val second = SensitiveValueRef("second")
        val attempted = mutableListOf<SensitiveValueRef>()
        val failure = IllegalStateException("first delete failed")
        val store = object : SensitiveValueStore {
            override fun put(value: String): SensitiveValueRef = error("Not used")

            override fun get(ref: SensitiveValueRef): String? = error("Not used")

            override fun delete(ref: SensitiveValueRef) {
                attempted += ref
                if (ref == first) throw failure
            }
        }

        val thrown = assertThrows(IllegalStateException::class.java) {
            store.deleteAll(listOf(first, second, second))
        }

        assertEquals(failure, thrown)
        assertEquals(listOf(first, second), attempted)
    }

    @Test
    fun deleteAllDeletesEachDistinctReferenceOnceWhenSuccessful() {
        val first = SensitiveValueRef("first")
        val second = SensitiveValueRef("second")
        val attempted = mutableListOf<SensitiveValueRef>()
        val store = object : SensitiveValueStore {
            override fun put(value: String): SensitiveValueRef = error("Not used")

            override fun get(ref: SensitiveValueRef): String? = error("Not used")

            override fun delete(ref: SensitiveValueRef) {
                attempted += ref
            }
        }

        store.deleteAll(listOf(first, first, second))

        assertEquals(listOf(first, second), attempted)
    }
}
