package com.gccwebos.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppShell() }
    }
}

private enum class Destination(@StringRes val title: Int) {
    Connect(R.string.tab_connect), Lines(R.string.tab_lines),
    Plans(R.string.tab_plans), Profile(R.string.tab_profile),
}

@Composable
internal fun AppShell() {
    var selectedName by rememberSaveable { mutableStateOf(Destination.Connect.name) }
    val selected = Destination.entries.firstOrNull { it.name == selectedName } ?: Destination.Connect
    MaterialTheme {
        Surface {
            Scaffold(bottomBar = {
                NavigationBar {
                    Destination.entries.forEach { destination ->
                        val title = stringResource(destination.title)
                        NavigationBarItem(
                            modifier = Modifier.testTag("nav-${destination.name}"),
                            selected = destination == selected,
                            onClick = { selectedName = destination.name },
                            icon = { Text(title.take(1)) },
                            label = { Text(title) },
                        )
                    }
                }
            }) { padding -> Page(selected, padding) }
        }
    }
}

@Composable
private fun Page(destination: Destination, padding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding)
            .verticalScroll(rememberScrollState()).padding(24.dp)
            .testTag("page-${destination.name}"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(destination.title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.build_stage), style = MaterialTheme.typography.bodySmall)
        when (destination) {
            Destination.Connect -> {
                Text(stringResource(R.string.not_integrated))
                Text(stringResource(R.string.connection_explanation))
                Text(stringResource(R.string.unknown_usage))
                Button(modifier = Modifier.testTag("vpn-connect"), onClick = {}, enabled = false) {
                    Text(stringResource(R.string.tab_connect))
                }
            }
            Destination.Lines -> Text(stringResource(R.string.lines_explanation))
            Destination.Plans -> Text(stringResource(R.string.plans_explanation))
            Destination.Profile -> {
                Text(stringResource(R.string.profile_explanation))
                DebugBuildPanel() // Implemented separately in debug/release source sets.
            }
        }
    }
}
