package com.mrndtvndv.term.ui.workspace

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mrndtvndv.term.ConnectionViewModelStores
import com.mrndtvndv.term.navigation.SftpFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrSftpNavigationTest {
    @Test
    fun reopeningBrowserRetainsNavigationScrollAndTreeMode() {
        val stores = ConnectionViewModelStores()
        fun browser(initialCwd: String): HerdrSftpNavigation = ViewModelProvider.create(
            stores.owner("server", "sftp:workspace"),
            viewModelFactory { initializer { HerdrSftpNavigation(initialCwd) } },
        )[HerdrSftpNavigation::class]

        val original = browser("/project")
        original.backStack.add(SftpFolder("/project/build"))
        original.saveScrollPosition("/project/build", 12, 34)
        original.treeMode = true

        val reopened = browser("/another-pane")

        assertSame(original, reopened)
        assertEquals(SftpFolder("/project/build"), reopened.backStack.last())
        assertEquals(12 to 34, reopened.scrollPosition("/project/build"))
        assertEquals(0 to 0, reopened.scrollPosition("/another-pane"))
        assertTrue(reopened.treeMode)

        stores.clear("server")
        val reconnected = browser("/new-project")
        assertEquals(SftpFolder("/new-project"), reconnected.backStack.last())
        assertEquals(0 to 0, reconnected.scrollPosition("/project/build"))
        assertFalse(reconnected.treeMode)
    }
}
