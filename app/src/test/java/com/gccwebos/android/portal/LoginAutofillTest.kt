package com.gccwebos.android.portal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The login form is filled and saved by the system password manager; the app never stores the password. */
class LoginAutofillTest {
    private fun source(path: String): String {
        val candidates = listOf(File("src/main/java/com/gccwebos/android/$path"), File("app/src/main/java/com/gccwebos/android/$path"))
        return candidates.first { it.isFile }.readText()
    }

    @Test fun whileSigningInTheFieldKeepsShowingWhatWasTypedSoTheSavePromptHasAValue() {
        // The controller wipes its copy at 登录; the form's own masked copy fills the field only while the request runs.
        assertEquals("secret", loginPasswordShown("", busy = true, submittedCopy = "secret"))
        assertEquals("", loginPasswordShown("", busy = false, submittedCopy = "secret"))        // failed / finished: empty again
        assertEquals("typed", loginPasswordShown("typed", busy = true, submittedCopy = "secret")) // the controller's value wins
        assertEquals("typed", loginPasswordShown("typed", busy = false, submittedCopy = ""))
        assertEquals("", loginPasswordShown("", busy = true, submittedCopy = ""))
    }

    @Test fun bothLoginFormsDeclareUsernameAndPasswordAutofillContentTypes() {
        val panel = source("portal/AccountPanel.kt")
        // Compact (production) form and the full account form: account = username + email, password = password.
        assertEquals(2, Regex("ContentType\\.Username \\+ ContentType\\.EmailAddress").findAll(panel).count())
        assertEquals(2, Regex("contentType = ContentType\\.Password").findAll(panel).count())
        // One manager, committed only for an authenticated form, cancelled otherwise.
        assertTrue(panel.contains("LocalAutofillManager.current"))
        assertTrue(panel.contains("if (state.authenticated) autofill?.commit() else autofill?.cancel()"))
    }

    @Test fun theAppNeverPersistsThePasswordAndLoginHasNoSecureWindowSideEffects() {
        val main = source("MainActivity.kt"); val panel = source("portal/AccountPanel.kt")
        // FLAG_SECURE / secure windows stay on the import dialog and scanner only: a secure login window blocks autofill UI.
        for (text in listOf(main, panel)) {
            assertFalse(text.contains("FLAG_SECURE")); assertFalse(text.contains("SecureFlagPolicy.SecureOn"))
        }
        // The remembered-account store takes an account identifier only (no password parameter anywhere in it).
        val store = source("portal/RememberedAccountStore.kt")
        assertFalse(Regex("(?i)password").containsMatchIn(store.replace(Regex("(?s)/\\*.*?\\*/|//[^\n]*"), "")))
    }
}
