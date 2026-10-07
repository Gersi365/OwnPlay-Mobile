package app.ownplay.mobile.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.design.OwnPlayShapes

@Composable
internal fun ProvisioningScreen(
    bootstrapState: AppBootstrapState,
    onManageSources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasExistingSource = bootstrapState == AppBootstrapState.NEEDS_SOURCE_SELECTION_OR_REPAIR
    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "OwnPlay",
            style = MaterialTheme.typography.headlineLarge,
            color = OwnPlayColors.TextPrimary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (hasExistingSource) {
                "Choose or repair a source before opening OwnPlay."
            } else {
                "Add a source to continue your OwnPlay setup."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = OwnPlayColors.TextSecondary,
        )
        Spacer(Modifier.height(28.dp))
        FilledTonalButton(
            onClick = onManageSources,
            shape = OwnPlayShapes.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) {
            Text(if (hasExistingSource) "Manage sources" else "Add source")
        }
    }
}

@Composable
internal fun ProvisioningLoadingScreen(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "OwnPlay",
            style = MaterialTheme.typography.headlineLarge,
            color = OwnPlayColors.TextPrimary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Preparing your sources…",
            style = MaterialTheme.typography.bodyLarge,
            color = OwnPlayColors.TextSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
