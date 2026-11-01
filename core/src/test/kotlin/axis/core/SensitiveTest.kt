package axis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SensitiveTest {
    @Test
    fun `toString and templates never expose the value`() {
        val key = Sensitive("sk-super-secret")
        assertFalse(key.toString().contains("super-secret"))
        assertFalse("key=$key".contains("super-secret"))
    }

    @Test
    fun `reveal returns the original value`() {
        assertEquals("abc", Sensitive("abc").reveal())
    }
}
