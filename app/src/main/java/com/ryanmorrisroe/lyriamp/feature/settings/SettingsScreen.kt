package com.ryanmorrisroe.lyriamp.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ryanmorrisroe.lyriamp.data.persistence.Appearance
import com.ryanmorrisroe.lyriamp.ui.glyph.BackArrowIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: DisplayPreferencesViewModel,
    onBack: () -> Unit,
    onConnections: () -> Unit,
    onCustomizeHome: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back" }
                    ) {
                        BackArrowIcon(
                            MaterialTheme.colorScheme.onSurface,
                            Modifier.size(24.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Display",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium
            )
            listOf(
                Appearance.SYSTEM to "Follow system",
                Appearance.LIGHT to "Light",
                Appearance.DARK to "Dark"
            ).forEach { (appearance, title) ->
                ListItem(
                    modifier = Modifier.clickable { viewModel.setAppearance(appearance) },
                    headlineContent = { Text(title) },
                    leadingContent = {
                        RadioButton(
                            selected = state.preferences.appearance == appearance,
                            onClick = null
                        )
                    }
                )
            }
            state.error?.let {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::retry) { Text("Retry reading settings") }
            }
            ListItem(
                modifier = Modifier.clickable(onClick = onCustomizeHome),
                headlineContent = { Text("Customize Home") },
                supportingContent = { Text("Pins, dynamic sections, and layout") }
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onConnections),
                headlineContent = { Text("Connections") },
                supportingContent = { Text("Manage servers") }
            )
        }
    }
}
