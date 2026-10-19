package com.keyscafe.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorReplaceTest {

    @Test
    fun exactExpression() {
        assertEquals(4, replaceDeleteLength("1+1=", "1+1="))
    }

    @Test
    fun trailingSpaceIsIncluded() {
        assertEquals(5, replaceDeleteLength("1+1= ", "1+1="))
    }

    @Test
    fun keepsLeadingText() {
        assertEquals(4, replaceDeleteLength("abc1+1=", "1+1="))
    }

    @Test
    fun cursorMovedAwayRejects() {
        assertNull(replaceDeleteLength("1+1=abc", "1+1="))
        assertNull(replaceDeleteLength("1+1", "1+1="))
    }

    @Test
    fun emptyInputRejects() {
        assertNull(replaceDeleteLength("", "1+1="))
        assertNull(replaceDeleteLength(null, "1+1="))
        assertNull(replaceDeleteLength("1+1=", ""))
    }
}
