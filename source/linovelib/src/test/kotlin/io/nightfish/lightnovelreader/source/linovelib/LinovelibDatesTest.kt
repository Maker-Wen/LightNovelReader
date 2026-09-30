package io.nightfish.lightnovelreader.source.linovelib

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class LinovelibDatesTest {
    @Test
    fun `unknown date uses the desugared runtime compatible constant`() {
        assertEquals(LocalDateTime.MIN, LinovelibDates.unknownDateTime())
    }
}
