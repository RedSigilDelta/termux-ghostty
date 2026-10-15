package com.mrndtvndv.term.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HerdrSubscriptionsTest {
    @Test
    fun `unknownVariantName reads the rejected name from an upstream error`() {
        assertEquals(
            "workspace.reordered",
            unknownVariantName("invalid request: unknown variant `workspace.reordered`, expected one of `a`, `b`"),
        )
        assertNull(unknownVariantName("invalid request: missing field `subscriptions`"))
        assertNull(unknownVariantName("invalid request: unknown variant ``"))
    }
}
