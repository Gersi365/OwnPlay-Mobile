package app.ownplay.mobile.design

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ContextScrollStateInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newContextStartsAtZeroWhileSameContextRefreshPreservesPosition() {
        val context = mutableStateOf("source-A/category-1")
        val revision = mutableStateOf(0)
        lateinit var list: LazyListState
        compose.setContent {
            list = rememberContextLazyListState(context.value)
            LazyColumn(state = list, modifier = Modifier.height(200.dp)) {
                items(200, key = { it }) { Text("Row $it · refresh ${revision.value}", modifier = Modifier.height(48.dp)) }
            }
        }
        compose.runOnIdle { runBlocking { list.scrollToItem(100, 12) } }
        compose.runOnIdle { revision.value++ }
        compose.runOnIdle { assertEquals(100, list.firstVisibleItemIndex); assertEquals(12, list.firstVisibleItemScrollOffset) }
        compose.runOnIdle { context.value = "source-B/category-1" }
        compose.runOnIdle { assertEquals(0, list.firstVisibleItemIndex); assertEquals(0, list.firstVisibleItemScrollOffset) }
    }

    @Test fun rotationRestorationKeepsSameBrowseContextAndPosition() {
        val restoration = StateRestorationTester(compose)
        lateinit var list: LazyListState
        restoration.setContent {
            val context = rememberSaveable { "source-A/search-query" }
            list = rememberContextLazyListState(context)
            LazyColumn(state = list, modifier = Modifier.height(200.dp)) {
                items(200, key = { it }) { Text("Row $it", modifier = Modifier.height(48.dp)) }
            }
        }
        compose.runOnIdle { runBlocking { list.scrollToItem(100, 12) } }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(100, list.firstVisibleItemIndex); assertEquals(12, list.firstVisibleItemScrollOffset) }
    }
}
