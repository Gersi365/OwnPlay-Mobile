package app.ownplay.mobile.app

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import app.ownplay.mobile.R
import app.ownplay.mobile.design.OwnPlayColors

@Composable
internal fun OwnPlayBottomBar(
    selected: AppDestination,
    onSelected: (AppDestination) -> Unit,
) {
    NavigationBar(
        containerColor = OwnPlayColors.Surface,
    ) {
        AppDestination.primaryEntries.forEach { destination ->
            val iconRes = when (destination) {
                AppDestination.LIVE -> R.drawable.ic_nav_live
                AppDestination.LIBRARY -> R.drawable.ic_nav_library
                AppDestination.SETTINGS -> R.drawable.ic_nav_settings
                AppDestination.DOWNLOADS -> R.drawable.ic_nav_library
            }
            NavigationBarItem(
                selected = selected == destination,
                onClick = { onSelected(destination) },
                icon = {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                    )
                },
                label = {
                    Text(destination.label)
                },
            )
        }
    }
}
