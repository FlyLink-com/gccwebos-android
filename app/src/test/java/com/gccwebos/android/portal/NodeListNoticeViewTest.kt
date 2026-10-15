package com.gccwebos.android.portal

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NodeListNoticeViewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadingShowsSkeletonRowsAndNoAction() {
        compose.setContent { NodeListNoticeView(nodeListNotice(LocalSubscriptionStatus.MISSING, true, false, true, true, true)!!) { } }
        compose.onAllNodesWithTag("nodes-skeleton-row").assertCountEquals(3)
        compose.onNodeWithTag("nodes-list-notice-text").assertTextEquals("正在加载节点…")
        compose.onAllNodesWithTag("nodes-list-notice-action").assertCountEquals(0)
    }

    @Test fun failureShowsMessageAndRecoveryActionThatFires() {
        var fired: NodeListAction? = null
        compose.setContent {
            NodeListNoticeView(nodeListNotice(LocalSubscriptionStatus.RETRY_REQUIRED, false, false, true, true, true)!!) { fired = it }
        }
        compose.onAllNodesWithTag("nodes-skeleton-row").assertCountEquals(0)
        compose.onNodeWithTag("nodes-list-notice-text").assertTextEquals("节点加载失败，请检查网络后重试")
        compose.onNodeWithTag("nodes-list-notice-action").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(NodeListAction.Retry, fired) }
    }

    @Test fun signedOutEmptyStateOffersLogin() {
        var fired: NodeListAction? = null
        compose.setContent {
            NodeListNoticeView(nodeListNotice(LocalSubscriptionStatus.MISSING, false, false, false, false, true)!!) { fired = it }
        }
        compose.onNodeWithText("登录").performClick()
        compose.runOnIdle { assertEquals(NodeListAction.Account, fired) }
    }
}
