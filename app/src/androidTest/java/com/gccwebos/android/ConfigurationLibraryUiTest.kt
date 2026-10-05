package com.gccwebos.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.design.GlassMode
import com.gccwebos.android.portal.*
import com.gccwebos.android.vpn.SafeProxyConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Display-only synthetic handles. No repository, network, storage, permission or VPN owner. */
class ConfigurationLibraryUiTest {
    @get:Rule val compose = createComposeRule()
    private val owner = Any()
    private val account = ConfigurationHandle(owner, "synthetic-revision", null, 0)
    private val first = ConfigurationHandle(owner, "synthetic-revision", "synthetic-first", 0)
    private val second = ConfigurationHandle(owner, "synthetic-revision", "synthetic-second", 0)
    private val nodes = listOf(SafeProxyConfig.NodeSummary(0, "Synthetic Japan", "vless", true))
    private var snapshot by mutableStateOf(ConfigurationLibrarySnapshot(listOf(
        ConfigurationItem(account, "账户订阅", ManualSourceKind.LegacyRemote, nodes, LocalSubscriptionStatus.READY, false),
        ConfigurationItem(first, "合成配置", ManualSourceKind.ImportedLocal, nodes, LocalSubscriptionStatus.READY, true),
        ConfigurationItem(second, "合成配置", ManualSourceKind.ExternalRemote, nodes, LocalSubscriptionStatus.READY, false),
    )))
    private var busy by mutableStateOf(false)
    private val activations = mutableListOf<ConfigurationHandle>()
    private val renames = mutableListOf<Pair<ConfigurationHandle, String>>()
    private val replacements = mutableListOf<ConfigurationHandle>()
    private val removals = mutableListOf<ConfigurationHandle>()
    private var imports = 0
    private var measurements = 0
    private var activeDisposals = 0

    private fun launch(fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GccTheme(materialMode = GlassMode.Solid) {
                    Column(Modifier.width(320.dp).fillMaxHeight().testTag("library-fixture-host")
                        .verticalScroll(rememberScrollState())) {
                        ConfigurationLibraryContent(snapshot, busy, null, canMeasure = true,
                            onMeasure = { measurements++ }, onImport = { imports++ },
                            onActivate = { activations += it }, onRename = { handle, name -> renames += handle to name },
                            onRemove = { removals += it }, onReplace = { replacements += it },
                            activeContent = { item, menu ->
                                DisposableEffect(Unit) { onDispose { activeDisposals++ } }
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("library-active-fixture")) {
                                    Text(item.name, Modifier.weight(1f).testTag("library-active-name"))
                                    menu()
                                }
                            })
                    }
                }
            }
        }
    }

    private fun menu(index: Int, action: String) {
        compose.onNodeWithTag("configuration-menu-$index").performScrollTo().performClick()
        compose.onNodeWithTag("configuration-$action-$index").performClick()
    }

    private fun assertNoSourceMutation() = compose.runOnIdle {
        assertTrue(activations.isEmpty()); assertTrue(renames.isEmpty())
        assertTrue(replacements.isEmpty()); assertTrue(removals.isEmpty())
    }

    @Test fun allConfigurationsRemainVisibleAndInactiveRowsCannotSelectNodes() {
        launch()
        compose.onNodeWithTag("configuration-group-0").assertExists()
        compose.onNodeWithTag("library-active-name").assertTextEquals("合成配置")
        compose.onNodeWithTag("configuration-group-2").assertExists()
        compose.onAllNodesWithText("合成配置", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithTag("configuration-toggle-2").performScrollTo().performClick()
        compose.onNodeWithTag("configuration-node-2-0").performScrollTo().assertHasNoClickAction()
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.onNodeWithTag("configuration-toggle-2").performScrollTo().performClick()
        compose.onNodeWithTag("configuration-node-2-0").assertDoesNotExist()
        assertNoSourceMutation()
        compose.onNodeWithTag("configuration-toggle-2").performClick()
        compose.onNodeWithTag("configuration-activate-2").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, activations.size); assertSame(second, activations.single()) }
        compose.onNodeWithTag("library-active-name").assertTextEquals("合成配置")
    }

    @Test fun accountConfigurationHasOnlyMetadataView() {
        launch()
        compose.onNodeWithTag("configuration-menu-0").performClick()
        compose.onNodeWithTag("configuration-rename-0").assertDoesNotExist()
        compose.onNodeWithTag("configuration-replace-0").assertDoesNotExist()
        compose.onNodeWithTag("configuration-remove-0").assertDoesNotExist()
        compose.onNodeWithTag("configuration-details-0").performClick()
        compose.onNodeWithText("1 条节点").assertExists()
        compose.onNodeWithTag("configuration-name-input").assertDoesNotExist()
        compose.onNodeWithTag("configuration-dialog-confirm").performClick()
        compose.onNodeWithTag("configuration-dialog").assertDoesNotExist()
        assertNoSourceMutation()
    }

    @Test fun duplicateNamesKeepRenameReplaceAndConfirmedDeleteBoundToTheCorrectHandle() {
        launch()
        menu(2, "rename")
        compose.onNodeWithTag("configuration-name-input").performTextReplacement("新的合成名称")
        compose.onNodeWithTag("configuration-dialog-confirm").performClick()
        compose.runOnIdle {
            assertEquals(1, renames.size); assertSame(second, renames.single().first)
            assertEquals("新的合成名称", renames.single().second)
            assertEquals("合成配置", snapshot.items[1].name)
        }
        menu(2, "replace")
        compose.runOnIdle { assertEquals(1, replacements.size); assertSame(second, replacements.single()) }
        menu(2, "remove")
        compose.runOnIdle { assertTrue(removals.isEmpty()) }
        compose.onNodeWithTag("configuration-dialog-cancel").performClick()
        compose.runOnIdle { assertTrue(removals.isEmpty()) }
        menu(2, "remove")
        compose.onNodeWithTag("configuration-dialog-confirm").performClick()
        compose.runOnIdle {
            assertEquals(1, removals.size); assertSame(second, removals.single())
            assertTrue(activations.isEmpty())
        }
    }

    @Test fun oneGlobalImportAndLightningNeverReplaceAnExistingConfiguration() {
        launch()
        compose.onAllNodesWithTag("nodes-import").assertCountEquals(1)
        compose.onAllNodesWithTag("nodes-latency-refresh").assertCountEquals(1)
        compose.onNodeWithTag("nodes-import").assertContentDescriptionEquals("新增配置")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("nodes-latency-refresh").assertContentDescriptionEquals("测试节点延迟").performClick()
        compose.onNodeWithText("测试节点延迟").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, imports); assertEquals(1, measurements) }
        assertNoSourceMutation()
        compose.runOnIdle { busy = true }
        compose.onNodeWithTag("nodes-import").assertIsNotEnabled()
        compose.onNodeWithTag("nodes-latency-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("configuration-menu-2").assertIsNotEnabled()
        compose.runOnIdle { busy = false; snapshot = snapshot.copy(storageError = true) }
        compose.onNodeWithTag("nodes-import").assertIsNotEnabled()
        compose.onNodeWithTag("nodes-latency-refresh").assertIsNotEnabled()
        assertNoSourceMutation()
    }

    @Test fun changedLibraryCannotSubmitAnOldDialogHandle() {
        launch()
        menu(2, "rename")
        compose.onNodeWithTag("configuration-name-input").performTextReplacement("迟到名称")
        compose.runOnIdle {
            val replacement = ConfigurationHandle(owner, "synthetic-new-revision", "synthetic-second", 0)
            snapshot = snapshot.copy(items = snapshot.items.map { if (it.handle === second) it.copy(handle = replacement) else it })
        }
        compose.onNodeWithTag("configuration-dialog").assertDoesNotExist()
        assertNoSourceMutation()
    }

    @Test fun noActiveSourceRetainsAllSavedGroupsAndDisablesLatency() {
        snapshot = snapshot.copy(items = snapshot.items.map { it.copy(active = false) })
        launch()
        compose.onNodeWithTag("configuration-unselected").assertTextEquals("未选择配置")
        (0..2).forEach { compose.onNodeWithTag("configuration-group-$it").assertExists() }
        compose.onNodeWithTag("library-active-fixture").assertDoesNotExist()
        compose.onNodeWithTag("nodes-latency-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("nodes-import").assertIsEnabled()
        assertNoSourceMutation()
    }

    @Test fun refreshedOperationHandlesDoNotDisposeTheActiveNodePanel() {
        launch()
        compose.runOnIdle {
            snapshot = snapshot.copy(items = snapshot.items.mapIndexed { index, item ->
                item.copy(handle = ConfigurationHandle(owner, "synthetic-next-revision", "synthetic-$index", 0))
            })
        }
        compose.onNodeWithTag("library-active-fixture").assertExists()
        compose.runOnIdle { assertEquals(0, activeDisposals) }
        assertNoSourceMutation()
    }

    @Test fun doubledFontInactiveRowsAndActivationStayInsideTheViewport() {
        launch(fontScale = 2f)
        compose.onNodeWithTag("configuration-toggle-2").performScrollTo().performClick()
        val host = compose.onNodeWithTag("library-fixture-host").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("configuration-node-2-0").performScrollTo().assertIsDisplayed()
        val bounds = row.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= host.left); assertTrue(bounds.right <= host.right)
        compose.onNodeWithTag("configuration-activate-2").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertSame(second, activations.single()) }
    }
}
