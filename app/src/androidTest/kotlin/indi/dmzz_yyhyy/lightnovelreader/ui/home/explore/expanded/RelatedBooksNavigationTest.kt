package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.expanded

import androidx.compose.runtime.mutableIntStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import indi.dmzz_yyhyy.lightnovelreader.ui.navigation.MainDestination
import indi.dmzz_yyhyy.lightnovelreader.ui.navigation.Navigator
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.book.RelatedBookKind
import io.nightfish.lightnovelreader.api.book.RelatedBooksRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelatedBooksNavigationTest {
    @Test
    fun repeatedQueryCreatesIndependentEntriesAndBackKeepsOriginalEntry() {
        val navigator = navigator()
        val request = RelatedBooksRequest("book-1", RelatedBookKind.AUTHOR, "原始作者 / 名称")
        navigator.navigateToRelatedBooksDestination("source-1", request)
        val first = navigator.currentRoute as Route.Main.Explore.RelatedBooks
        navigator.navigate(Route.Book.Detail("result-book"))
        assertTrue(navigator.popBackStack())
        assertSame(first, navigator.currentRoute)

        navigator.navigateToRelatedBooksDestination("source-1", request)
        val second = navigator.currentRoute as Route.Main.Explore.RelatedBooks
        assertNotEquals(first.entryId, second.entryId)
        assertNotEquals(first, second)
        assertNotEquals(first.toString(), second.toString())
        assertEquals(first, second.copy(entryId = first.entryId))
        assertTrue(navigator.popBackStack())
        assertSame(first, navigator.currentRoute)
    }

    @Test
    fun serializationRestoresRawQueryAndPageIdentity() {
        val navigator = navigator()
        for (kind in RelatedBookKind.entries) {
            val request = RelatedBooksRequest("book/1", kind, "  原始值 / ?&=繁體  ")
            navigator.navigateToRelatedBooksDestination("source:原始", request)
            val original = navigator.currentRoute as Route.Main.Explore.RelatedBooks
            val restored = Json.decodeFromString<Route.Main.Explore.RelatedBooks>(Json.encodeToString(original))
            assertEquals(original, restored)
            assertEquals(original.entryId, restored.entryId)
            assertEquals("source:原始", restored.sourceId)
            assertEquals(request, RelatedBooksRequest(restored.bookId, restored.kind, restored.value))
        }
    }

    @Test
    fun blankValuesDoNotCreateUnusableBackStackEntries() {
        val navigator = navigator()
        val root = navigator.currentRoute
        navigator.navigateToRelatedBooksDestination(" ", RelatedBooksRequest("1", RelatedBookKind.AUTHOR, "author"))
        navigator.navigateToRelatedBooksDestination("source", RelatedBooksRequest("1", RelatedBookKind.AUTHOR, " \n "))
        assertSame(root, navigator.currentRoute)
    }

    private fun navigator() = Navigator(
        selectedMainIndex = mutableIntStateOf(MainDestination.Explore.index),
        backStacks = mapOf(MainDestination.Explore to NavBackStack<NavKey>(Route.Main.Explore.Home)),
    )
}
