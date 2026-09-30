package io.nightfish.lightnovelreader.source.linovelib

import io.nightfish.lightnovelreader.api.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Test

class LinovelibSourceIdTest {
    @Test
    fun api4SourceIdUsesStableNamespaceAndName() {
        assertEquals(Identifier("lightnovelreader", "linovelib"), LINOVELIB_SOURCE_ID)
    }
}
