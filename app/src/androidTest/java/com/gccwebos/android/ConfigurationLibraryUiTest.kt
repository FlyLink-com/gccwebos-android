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
        ConfigurationItem(account, "官方", ManualSourceKind.LegacyRemote, nodes, LocalSubscriptionStatus.READY, false),
        ConfigurationItem(first, "合成配置", ManualSourceKind.ImportedLocal, nodes, LocalSubscriptionStatus.READY, true,
            nodeKeys = listOf("k1")),
        ConfigurationItem(second, "合成配置", ManualSourceKind.ExternalRemote, nodes, LocalSubscriptionStatus.READY, false,
            nodeKeys = listOf("k2")),
    )))
    private var busy by mutableStateOf(false)
    private var tunnelActive by mutableStateOf(false)
    private val chosen = mutableListOf<Pair<ConfigurationHandle, NodeTarget>>()
    private val nodeRenames = mutableListOf<Triple<ConfigurationItem, Int, String>>()
    private val nodeDeletes = mutableListOf<Pair<ConfigurationItem, Int>>()
    private val nodeRestores = mutableListOf<Pair<ConfigurationItem, Int>>()
    private val activations = mutableListOf<ConfigurationHandle>()
    private val renames = mutableListOf<Pair<ConfigurationHandle, String>>()
    private val replacements = mutableListOf<ConfigurationHandle>()
    private val removals = mutableListOf<ConfigurationHandle>()
    private var imports = 0
    private var namingEntryId by mutableStateOf<String?>(null)
    private var namingDone = 0
    private var measurements = 0
    private var activeDisposals = 0
    private var latencies by mutableStateOf<Map<String?, Long>>(emptyMap())
    /** Added to a source's latency per node position (negative: later nodes are faster), to exercise sorting inside a source. */
    private var latencyStepPerPosition = 0L

    private fun launch(fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GccTheme(materialMode = GlassMode.Solid) {
                    Column(Modifier.width(320.dp).fillMaxHeight().testTag("library-fixture-host")
                        .verticalScroll(rememberScrollState())) {
                        ConfigurationLibraryContent(snapshot, busy, null, canMeasure = true,
                            onMeasure = { measurements++ }, onImport = { imports++ },
                            latencyFor = { item, position -> latencies[item.handle.entryId]?.let { NodeLatencyObservation.Success(it + position * latencyStepPerPosition, 7) } },
                            onActivate = { activations += it }, onRename = { handle, name -> renames += handle to name },
                            onRemove = { removals += it }, onReplace = { replacements += it },
                            onChooseNode = { handle, target -> chosen += handle to target },
                            onRenameNode = { item, position, alias -> nodeRenames += Triple(item, position, alias) },
                            onDeleteNode = { item, position -> nodeDeletes += item to position },
                            onRestoreNode = { item, position -> nodeRestores += item to position },
                            tunnelActive = tunnelActive,
                            namingEntryId = namingEntryId, onNamingDone = { namingDone++; namingEntryId = null },
                            activeContent = { item, list ->
                                DisposableEffect(Unit) { onDispose { activeDisposals++ } }
                                Box(Modifier.testTag("nodes-configuration-group")) { Column(Modifier.testTag("library-active-fixture")) {
                                    // Like the real card: 节点列表 on the left, the page's sort + 延迟测试 icons at the right.
                                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text("节点列表", Modifier.weight(1f).testTag("nodes-group-title"))
                                        list.headerAction(this)
                                    }
                                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                        Text(item.name, Modifier.weight(1f).testTag("library-active-name"))
                                        list.rowTrailing?.invoke(0)
                                    }
                                    list.extraRows(this)
                                } }
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
        assertTrue(nodeRenames.isEmpty()); assertTrue(nodeDeletes.isEmpty()); assertTrue(nodeRestores.isEmpty())
    }

    @Test fun allConfigurationsRemainVisibleAndEveryNodeOfEverySourceIsChosenInOneTap() {
        launch()
        compose.onNodeWithTag("configuration-group-0").assertExists()
        compose.onNodeWithTag("library-active-name").assertTextEquals("合成配置")
        compose.onNodeWithTag("configuration-group-2").assertExists()
        // No expanding and no separate "选择配置" step: the other sources' nodes are rows of the same list.
        compose.onNodeWithTag("configuration-toggle-2").assertDoesNotExist()
        compose.onNodeWithTag("configuration-activate-2").assertDoesNotExist()
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.onNodeWithTag("configuration-node-2-0").performScrollTo().assertHasClickAction().performClick()
        compose.onNodeWithTag("unknown-node-continue").performClick()
        compose.runOnIdle {
            assertEquals(1, chosen.size); assertSame(second, chosen.single().first)
            assertEquals(NodeTarget(0, "Synthetic Japan", "vless"), chosen.single().second)
        }
        assertNoSourceMutation()
        compose.onNodeWithTag("library-active-name").assertTextEquals("合成配置")
    }

    @Test fun everyNodeRowSaysWhereItComesFrom() {
        launch()
        compose.onNodeWithTag("configuration-node-0-0").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("官方 · 只读", useUnmergedTree = true).assertCountEquals(1)
        // Imported rows carry the source name only (the kind sits on the 来源 card), so the label is never cut off.
        compose.onNode(hasTestTag("node-source-label") and hasAnyAncestor(hasTestTag("configuration-node-2-0")), useUnmergedTree = true)
            .assertTextEquals("合成配置")
        compose.onAllNodesWithText("合成配置 · 第三方订阅", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun accountNodesAreReadOnlyAndImportedNodesCanBeEdited() {
        launch()
        compose.onNodeWithTag("node-menu-0-0").assertDoesNotExist()
        compose.onNodeWithTag("node-menu-2-0").performScrollTo().assertHeightIsAtLeast(40.dp).performClick()
        compose.onNodeWithTag("node-rename-2-0").assertExists()
        compose.onNodeWithTag("node-delete-2-0").assertIsEnabled()
    }

    @Test fun renamingANodeSubmitsOnlyTheLocalAlias() {
        launch()
        compose.onNodeWithTag("node-menu-2-0").performScrollTo().performClick()
        compose.onNodeWithTag("node-rename-2-0").performClick()
        compose.onNodeWithTag("node-name-input").performTextReplacement("我的东京")
        compose.onNodeWithTag("node-dialog-confirm").performClick()
        compose.runOnIdle {
            assertEquals(1, nodeRenames.size); assertSame(second, nodeRenames.single().first.handle)
            assertEquals(0, nodeRenames.single().second); assertEquals("我的东京", nodeRenames.single().third)
            assertTrue(nodeDeletes.isEmpty()); assertTrue(renames.isEmpty())
        }
    }

    @Test fun deletingANodeNeedsConfirmationAndCancelChangesNothing() {
        launch()
        compose.onNodeWithTag("node-menu-2-0").performScrollTo().performClick()
        compose.onNodeWithTag("node-delete-2-0").performClick()
        compose.onNodeWithTag("node-dialog-name").assertTextEquals("Synthetic Japan")
        compose.onNodeWithTag("node-dialog-cancel").performClick()
        compose.runOnIdle { assertTrue(nodeDeletes.isEmpty()) }
        compose.onNodeWithTag("node-menu-2-0").performClick()
        compose.onNodeWithTag("node-delete-2-0").performClick()
        compose.onNodeWithTag("node-dialog-confirm").performClick()
        compose.runOnIdle { assertEquals(1, nodeDeletes.size); assertSame(second, nodeDeletes.single().first.handle) }
    }

    @Test fun whileConnectedOtherSourceNodesAreDisabledWithAHintAndTheActiveSourceCannotDeleteNodes() {
        tunnelActive = true
        launch()
        compose.onNodeWithTag("unified-connected-hint").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("configuration-node-2-0").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("node-menu-active-0").performClick()
        compose.onNodeWithTag("node-delete-active-0").assertIsNotEnabled()
        compose.onNodeWithTag("node-rename-active-0").assertIsEnabled()
        compose.runOnIdle { assertTrue(chosen.isEmpty()) }
    }

    private fun withDeletedNode() {
        snapshot = snapshot.copy(items = snapshot.items.map { item ->
            if (item.handle === second) item.copy(
                nodes = nodes + SafeProxyConfig.NodeSummary(1, "Synthetic Deleted", "vless", true, info = true),
                nodeKeys = listOf("k2", "k3"), nodeDeleted = setOf(1))
            else item
        })
    }

    @Test fun deletedNodesSectionIsHiddenWhenNothingIsDeleted() {
        launch()
        compose.onNodeWithTag("unified-deleted").assertDoesNotExist()
    }

    @Test fun deletedNodesAreCollapsedAtTheBottomNotListedAsNodesAndOnlyRestorable() {
        withDeletedNode()
        launch()
        compose.onNodeWithTag("unified-deleted").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("unified-deleted-count", useUnmergedTree = true).assertTextEquals("1 条")
        compose.onNodeWithTag("deleted-node-2-1").assertDoesNotExist() // collapsed by default
        compose.onNodeWithTag("configuration-node-2-1").assertDoesNotExist() // never a main-list row
        compose.onNodeWithTag("unified-deleted-toggle").performClick()
        compose.onNodeWithTag("deleted-node-2-1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("deleted-node-2-1").assertHasNoClickAction() // not selectable
        compose.onNodeWithTag("deleted-menu-2-1").performClick()
        compose.onNodeWithTag("deleted-restore-2-1").assertExists()
        compose.onAllNodesWithText("重命名").assertCountEquals(0)
        compose.onAllNodesWithText("删除").assertCountEquals(0)
        compose.onNodeWithTag("deleted-restore-2-1").performClick()
        compose.runOnIdle {
            assertEquals(1, nodeRestores.size); assertSame(second, nodeRestores.single().first.handle)
            assertEquals(1, nodeRestores.single().second)
            assertTrue(chosen.isEmpty()); assertTrue(nodeRenames.isEmpty()); assertTrue(nodeDeletes.isEmpty())
        }
    }

    @Test fun deletedSectionFollowsTheSourceFilter() {
        withDeletedNode()
        launch()
        compose.onNodeWithTag("unified-filter-1").performClick()
        compose.onNodeWithTag("unified-deleted").assertDoesNotExist()
        compose.onNodeWithTag("unified-filter-2").performClick()
        compose.onNodeWithTag("unified-deleted").performScrollTo().assertExists()
    }

    @Test fun addButtonEndsTheSwitcherRowAtTheFarRight() {
        launch()
        val plus = compose.onNodeWithTag("nodes-import").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("unified-filter-2").fetchSemanticsNode().boundsInRoot
        val all = compose.onNodeWithTag("unified-filter-all").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("unified-filter").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertTrue("+ is the rightmost control of the switcher row", plus.right >= row.right - 1f)
            assertTrue("+ comes after every tab", plus.left >= last.right - 1f && plus.left > all.right)
            assertTrue("+ shares the row vertically with the tabs", plus.top < row.bottom && plus.bottom > row.top)
        }
        compose.onNodeWithTag("unified-filter-all").assertIsSelected()
        compose.onNodeWithTag("unified-filter-0").assertExists()
        compose.onNodeWithTag("unified-filter-1").assertExists()
        compose.onNodeWithTag("unified-filter-2").assertExists()
        // The capsule bar itself (brand gradient, colours covered by SlidingTabContrastTest) holds every tab and not the +.
        val bar = compose.onNodeWithTag("unified-filter-bar").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { assertTrue(last.right <= bar.right + 1f && all.left >= bar.left - 1f && plus.left >= bar.right - 1f) }
    }

    @Test fun thereIsNoPageTitleAboveTheSwitcherAndNoSelectionHelperCopy() {
        launch()
        compose.onNodeWithTag("configuration-library-header").assertDoesNotExist()
        compose.onNodeWithTag("configuration-library-title").assertDoesNotExist()
        compose.onAllNodesWithText("全部节点", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("nodes-selection-summary").assertDoesNotExist()
        compose.onNodeWithTag("nodes-select-prompt").assertDoesNotExist()
        compose.onAllNodesWithText("请点选下方一个节点", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("nodes-select-lost").assertDoesNotExist()
        compose.onAllNodesWithText("已不在配置里", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("未选择", substring = false).assertCountEquals(0)
    }

    private fun density() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    @Test fun nodeListHeadingAndLatencyTestShareOneRowWithTheActionAtTheRight() {
        launch()
        val title = compose.onNodeWithTag("nodes-group-title").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("nodes-latency-refresh").fetchSemanticsNode().boundsInRoot
        val group = compose.onNodeWithTag("nodes-configuration-group").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("nodes-group-title").assertTextEquals("节点列表")
        compose.onNodeWithTag("nodes-latency-refresh").assertContentDescriptionEquals("延迟测试")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.runOnIdle {
            assertTrue("same row", action.top < title.bottom && action.bottom > title.top)
            assertTrue("action is right of the heading", action.left >= title.right - 1f)
            // Order on the row: 节点列表 ... ⚡ then the sort funnel at the far right.
            assertTrue("lightning sits just left of the sort icon at the card's right edge", action.right >= group.right - 24f - 48f * density())
        }
        // Icon only: no 延迟测试 text anywhere, no toolbar, no last-test caption, no old sort text.
        compose.onNodeWithTag("nodes-latency-one-tap").assertDoesNotExist()
        compose.onNodeWithTag("nodes-latency-toolbar").assertDoesNotExist()
        compose.onAllNodesWithText("延迟测试").assertCountEquals(0)
        compose.onAllNodesWithText("上次延迟测试", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("按延迟排序").assertCountEquals(0)
    }

    @Test fun sortIsAFunnelIconRightOfTheLightningWithADefaultAndLatencyMenu() {
        // 配置 2 has two nodes; the second one is faster. Default order keeps the delivered order; 按延迟 puts the fast one first.
        val two = nodes + SafeProxyConfig.NodeSummary(1, "Synthetic Singapore", "hysteria2", true)
        snapshot = snapshot.copy(items = snapshot.items.map { if (it.handle === second) it.copy(nodes = two, nodeKeys = listOf("k2", "k3")) else it })
        latencies = mapOf(null to 400L, "synthetic-second" to 300L); latencyStepPerPosition = -100L
        launch()
        val sort = compose.onNodeWithTag("nodes-sort").assertContentDescriptionEquals("排序")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        val bolt = compose.onNodeWithTag("nodes-latency-refresh").fetchSemanticsNode().boundsInRoot
        val group = compose.onNodeWithTag("nodes-configuration-group").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertTrue("same row", sort.top < bolt.bottom && sort.bottom > bolt.top)
            assertTrue("directly right of the lightning, at the far right", sort.left >= bolt.right - 1f && sort.left - bolt.right < 24f)
            assertTrue("sort icon ends the row", sort.right >= group.right - 24f)
        }
        fun top(tag: String) = compose.onNodeWithTag(tag).performScrollTo().fetchSemanticsNode().boundsInRoot.top
        assertTrue("default order", top("configuration-node-2-0") < top("configuration-node-2-1"))
        compose.onNodeWithTag("nodes-sort").performScrollTo().performClick()
        compose.onNodeWithTag("nodes-sort-default").assertIsDisplayed()
        compose.onNodeWithText("默认顺序").assertIsDisplayed()
        compose.onNodeWithText("按延迟").assertIsDisplayed().performClick()
        compose.onNodeWithTag("nodes-sort-default").assertDoesNotExist()
        assertTrue("sorted by latency: the 200 ms node above the 300 ms node", top("configuration-node-2-1") < top("configuration-node-2-0"))
        // And back to the default order.
        compose.onNodeWithTag("nodes-sort").performScrollTo().performClick()
        compose.onNodeWithText("默认顺序").performClick()
        assertTrue("default again", top("configuration-node-2-0") < top("configuration-node-2-1"))
    }

    @Test fun nodesOfOtherSourcesShowTheirOwnLatency() {
        latencies = mapOf("synthetic-second" to 187L)
        launch()
        compose.onNodeWithTag("configuration-latency-2-0", useUnmergedTree = true).performScrollTo()
            .assertContentDescriptionEquals("节点延迟 187 毫秒")
    }

    @Test fun filterChipsNarrowTheListToOneSource() {
        launch()
        compose.onNodeWithTag("unified-filter-all").assertExists()
        compose.onNodeWithTag("unified-filter-2").performClick()
        compose.onNodeWithTag("configuration-node-2-0").performScrollTo().assertExists()
        compose.onNodeWithTag("configuration-node-0-0").assertDoesNotExist()
        compose.onNodeWithTag("unified-filter-all").performClick()
        compose.onNodeWithTag("configuration-node-0-0").performScrollTo().assertExists()
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
        compose.onNodeWithTag("nodes-latency-refresh").assertContentDescriptionEquals("延迟测试").performClick()
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

    @Test fun noActiveSourceRetainsAllSavedGroupsAndStillMeasuresTheirNodes() {
        snapshot = snapshot.copy(items = snapshot.items.map { it.copy(active = false) })
        launch()
        compose.onNodeWithTag("configuration-unselected").assertTextEquals("未选择配置")
        compose.onNodeWithTag("configuration-node-2-0").performScrollTo().assertHasClickAction()
        (0..2).forEach { compose.onNodeWithTag("configuration-group-$it").assertExists() }
        compose.onNodeWithTag("library-active-fixture").assertDoesNotExist()
        // The heading row still carries 排序 and 延迟测试: the saved sources' own nodes can be measured without an active one.
        compose.onNodeWithTag("nodes-latency-refresh").assertIsEnabled()
        compose.onNodeWithTag("nodes-sort").assertExists()
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

    @Test fun refreshedOperationHandlesKeepTheOtherSourceNodesAndChooseWithTheNewHandle() {
        launch()
        compose.onNodeWithTag("configuration-node-2-0").performScrollTo().assertExists()
        val replacement = ConfigurationHandle(owner, "synthetic-next-revision", "synthetic-second", 0)
        compose.runOnIdle {
            snapshot = snapshot.copy(items = snapshot.items.map { if (it.handle === second) it.copy(handle = replacement) else it })
        }
        compose.onNodeWithTag("configuration-node-2-0").performScrollTo().assertHasClickAction().performClick()
        compose.onNodeWithTag("unknown-node-continue").performClick()
        compose.runOnIdle { assertSame(replacement, chosen.single().first); assertTrue(activations.isEmpty()) }
    }

    @Test fun choosingAnUnknownNodeAsksFirstAndTheOfficialSourceNeverDoes() {
        launch()
        // 取消: nothing is chosen, the dialog closes.
        compose.onNodeWithTag("configuration-node-2-0").performScrollTo().performClick()
        compose.onNodeWithTag("unknown-node-dialog").assertIsDisplayed()
        compose.onNodeWithTag("unknown-node-body", useUnmergedTree = true).assertTextEquals("未知节点存在数据泄露风险，请谨慎选择")
        compose.onNodeWithTag("unknown-node-cancel").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("unknown-node-dialog").assertDoesNotExist()
        compose.runOnIdle { assertTrue(chosen.isEmpty()) }
        // 继续选择: the same tap path now selects exactly that node.
        compose.onNodeWithTag("configuration-node-2-0").performScrollTo().performClick()
        compose.onNodeWithTag("unknown-node-continue").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertSame(second, chosen.single().first) }
        // 官方 (account) nodes are chosen without any warning.
        compose.onNodeWithTag("configuration-node-0-0").performScrollTo().performClick()
        compose.onNodeWithTag("unknown-node-dialog").assertDoesNotExist()
        compose.runOnIdle { assertSame(account, chosen.last().first) }
    }

    @Test fun doubledFontOtherSourceRowsStayInsideTheViewport() {
        launch(fontScale = 2f)
        val host = compose.onNodeWithTag("library-fixture-host").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("configuration-node-2-0").performScrollTo().assertIsDisplayed()
        val bounds = row.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= host.left); assertTrue(bounds.right <= host.right)
        row.assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("unknown-node-continue").performClick()
        compose.runOnIdle { assertSame(second, chosen.single().first) }
    }

    @Test fun renameToAnotherSavedNameShowsTheDuplicateMessageAndBlocksSaving() {
        launch()
        // Item 2 already shares "合成配置" with item 1 (data written before the rule existed).
        menu(2, "rename")
        compose.onNodeWithTag("configuration-name-duplicate").assertTextEquals("已有同名订阅，请重新命名你的订阅")
        compose.onNodeWithTag("configuration-dialog-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("configuration-name-input").performTextReplacement("  官方 ")
        compose.onNodeWithTag("configuration-dialog-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("configuration-name-input").performTextReplacement("唯一名称")
        compose.onNodeWithTag("configuration-name-duplicate").assertDoesNotExist()
        compose.onNodeWithTag("configuration-dialog-confirm").assertIsEnabled().performClick()
        compose.runOnIdle { assertSame(second, renames.single().first); assertEquals("唯一名称", renames.single().second) }
    }

    @Test fun aNewlyImportedEntryAsksForANameThatSurvivesARefreshAndRejectsDuplicates() {
        snapshot = ConfigurationLibrarySnapshot(listOf(
            ConfigurationItem(account, "官方", ManualSourceKind.LegacyRemote, nodes, LocalSubscriptionStatus.READY, false),
            ConfigurationItem(first, "Kitty", ManualSourceKind.ExternalRemote, nodes, LocalSubscriptionStatus.READY, false),
            ConfigurationItem(second, "未知3", ManualSourceKind.ExternalRemote, nodes, LocalSubscriptionStatus.READY, true)))
        namingEntryId = "synthetic-second"
        launch()
        compose.onNodeWithText("为订阅命名").assertExists()
        compose.onNodeWithTag("configuration-name-input").assertTextContains("未知3")
        compose.runOnIdle { assertEquals(1, namingDone); assertNull(namingEntryId) }
        // The import's settle refresh replaces handles; the dialog stays on the same saved entry.
        val refreshed = ConfigurationHandle(owner, "synthetic-revision-2", "synthetic-second", 0)
        compose.runOnIdle { snapshot = snapshot.copy(items = snapshot.items.map {
            if (it.handle === second) it.copy(handle = refreshed) else it }) }
        compose.onNodeWithTag("configuration-name-input").performTextReplacement("kitty")
        compose.onNodeWithTag("configuration-name-duplicate").assertExists()
        compose.onNodeWithTag("configuration-dialog-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("configuration-name-input").performTextReplacement("Kitty 日本")
        compose.onNodeWithTag("configuration-dialog-confirm").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertSame(refreshed, renames.single().first); assertEquals("Kitty 日本", renames.single().second)
            assertEquals(1, namingDone)
        }
    }

    @Test fun skippingTheNameKeepsTheDefaultWithoutRenaming() {
        namingEntryId = "synthetic-first"
        launch()
        compose.onNodeWithText("为订阅命名").assertExists()
        compose.onNodeWithTag("configuration-dialog-cancel").assertTextEquals("跳过").performClick()
        compose.onNodeWithTag("configuration-dialog").assertDoesNotExist()
        compose.runOnIdle { assertTrue(renames.isEmpty()); assertEquals(1, namingDone) }
    }
}
