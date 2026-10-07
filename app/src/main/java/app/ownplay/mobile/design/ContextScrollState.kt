package app.ownplay.mobile.design

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable

/** A changed context creates index zero before rendering; a preserved context restores its saved state. */
@Composable
fun rememberContextLazyListState(vararg context: Any?): LazyListState =
    rememberSaveable(*context, saver = LazyListState.Saver) { LazyListState() }

@Composable
fun rememberContextLazyGridState(vararg context: Any?): LazyGridState =
    rememberSaveable(*context, saver = LazyGridState.Saver) { LazyGridState() }
