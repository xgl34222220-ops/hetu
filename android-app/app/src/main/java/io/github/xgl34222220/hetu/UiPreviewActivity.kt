package io.github.xgl34222220.hetu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HetuHomeTheme
import io.github.xgl34222220.hetu.home.HomeActions
import io.github.xgl34222220.hetu.home.HomeRoute
import io.github.xgl34222220.hetu.home.HomeSamples
import io.github.xgl34222220.hetu.panel.PanelActions
import io.github.xgl34222220.hetu.panel.PanelRoute
import io.github.xgl34222220.hetu.panel.PanelSamples
import io.github.xgl34222220.hetu.panel.PanelTab

/**
 * Debug-only visual preview of the Claude-written UI modules.
 *
 * Shows 01 首页 (HomeRoute) and 02 面板 (PanelRoute) with built-in sample data.
 * NOT connected to the real proxy runtime — for UI review only.
 * Only exists on the test/v20.49-ui-preview-apk branch; never merge to main.
 */
class UiPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HetuHomeTheme {
                var tab by remember { mutableStateOf(0) }
                Column(Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("首页") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("面板") })
                    }
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            0 -> HomeRoute(
                                state = HomeSamples.running,
                                actions = HomeActions(),
                                contentPadding = PaddingValues(0.dp),
                            )
                            else -> PanelPreviewHost()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelPreviewHost(modifier: Modifier = Modifier) {
    var panelTab by remember { mutableStateOf(PanelTab.Overview) }
    PanelRoute(
        data = PanelSamples.running,
        tab = panelTab,
        onTabChange = { panelTab = it },
        actions = PanelActions(),
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
    )
}
