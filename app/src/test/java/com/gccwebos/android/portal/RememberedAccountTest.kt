package com.gccwebos.android.portal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.EmptyCoroutineContext

/** 「记住账号」: only the account identifier is ever kept; synthetic values only. */
@OptIn(ExperimentalCoroutinesApi::class)
class RememberedAccountTest {
    @get:Rule val folder = TemporaryFolder()
    private val now = Instant.parse("2026-09-30T00:00:00Z")
    private val email = "remember.fixture@example.invalid"
    private val password = "synthetic-never-stored-password"
    private fun key() = SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")
    private fun encrypted() = EncryptedRememberedAccountStore(folder.root) { key() }

    // ---- store ----
    @Test fun encryptedStoreRoundTripsAndNeverWritesTheIdentifierInThePlain() {
        val store = encrypted()
        assertNull(store.read())
        store.put("  $email ")
        assertEquals(email, store.read())
        val bytes = folder.root.resolve(EncryptedRememberedAccountStore.FILE_NAME).readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("remember.fixture"))
        store.clear(); assertNull(store.read())
        assertFalse(folder.root.resolve(EncryptedRememberedAccountStore.FILE_NAME).exists())
    }

    @Test fun tamperedOrWrongKeyOrOversizedReadsAsNothingRemembered() {
        encrypted().put(email)
        val file = folder.root.resolve(EncryptedRememberedAccountStore.FILE_NAME)
        val wrong = EncryptedRememberedAccountStore(folder.root) { SecretKeySpec(ByteArray(16) { 9 }, "AES") }
        assertNull(wrong.read())
        val bytes = file.readBytes(); bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte(); file.writeBytes(bytes)
        assertNull(encrypted().read())
        file.writeBytes(ByteArray(4096)); assertNull(encrypted().read())
    }

    @Test fun onlyPlainSingleLineBoundedIdentifiersAreRemembered() {
        val store = MemoryRememberedAccountStore()
        for (bad in listOf("", "   ", "a\nb", "a\u0000b", "x".repeat(255))) { store.put(bad); assertNull(bad, store.read()) }
        store.put("name_01"); assertEquals("name_01", store.read())
        store.clear(); assertNull(store.read())
    }

    // ---- controller ----
    private fun controller(scope: CoroutineScope, wire: PortalTransport, store: RememberedAccountStore,
                           sessions: SessionStore = MemorySessionStore()): AccountController {
        val delivery = object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = throw AssertionError("No delivery writes")
            override fun clear() = Unit
        }
        return AccountController(PortalRepository(wire, delivery,
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
            subscription = SubscriptionSource { throw AssertionError("No subscription requests") },
            sessions = sessions, profiles = MemoryLocalProfileStore()), scope,
            rememberedAccounts = store, rememberedIo = EmptyCoroutineContext)
    }
    private val ok = object : PortalTransport {
        override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = when (path) {
            "/api/portal/password/session" -> WireResponse(200, """{"session":{"sessionRef":"native_remember_fixture","accountId":"account_remember_fixture",
                "issuedAt":"2026-09-30T00:00:00.000Z","expiresAt":"2026-09-30T00:30:00.000Z"}}""")
            "/api/portal/resources/current/overview" -> WireResponse(200, """{"resource":{"state":"none"}}""")
            else -> throw AssertionError("Unexpected offline path")
        }
    }
    private val denied = object : PortalTransport {
        override suspend fun request(path: String, body: String?, session: Secret?) = WireResponse(401, """{"error":"invalid_credentials"}""")
    }
    private fun AccountController.fill() {
        account = email; password = this@RememberedAccountTest.password; answer = "3"
        challenge = HumanChallenge("remember_fixture_question", "1 + 2")
    }

    @Test fun tickedByDefaultAndPrefillsOnlyAnEmptyField() = runTest {
        val state = controller(backgroundScope, ok, MemoryRememberedAccountStore(email))
        assertTrue(state.rememberAccount); assertEquals("", state.account)
        state.prefillRememberedAccount(); runCurrent()
        assertEquals(email, state.account); assertTrue(state.accountPrefilled)
        val typed = controller(backgroundScope, ok, MemoryRememberedAccountStore(email)).apply { account = "typed" }
        typed.prefillRememberedAccount(); runCurrent()
        assertEquals("typed", typed.account); assertFalse(typed.accountPrefilled)
    }

    @Test fun successfulLoginRemembersTheAccountOnlyAndNeverThePassword() = runTest {
        val store = MemoryRememberedAccountStore()
        val state = controller(backgroundScope, ok, store)
        state.fill(); state.login(); runCurrent()
        assertTrue(state.authenticated); assertEquals(email, store.read())
        assertEquals("", state.password)
        assertFalse(store.read()!!.contains(password))
    }

    @Test fun aFailedLoginRemembersNothing() = runTest {
        val store = MemoryRememberedAccountStore()
        val state = controller(backgroundScope, denied, store)
        state.fill(); state.login(); runCurrent()
        assertFalse(state.authenticated); assertNull(store.read())
    }

    @Test fun untickingClearsAtOnceAndALaterLoginRemembersNothing() = runTest {
        val store = MemoryRememberedAccountStore(email)
        val state = controller(backgroundScope, ok, store)
        state.updateRememberAccount(false); runCurrent()
        assertNull(store.read()); assertFalse(state.rememberAccount)
        state.fill(); state.login(); runCurrent()
        assertTrue(state.authenticated); assertNull(store.read())
    }

    @Test fun logoutKeepsTheRememberedAccountWhenTicked() = runTest {
        val store = MemoryRememberedAccountStore()
        val state = controller(backgroundScope, ok, store)
        state.fill(); state.login(); runCurrent()
        state.logout(); runCurrent()
        assertFalse(state.authenticated); assertEquals(email, store.read()); assertEquals(email, state.account)
    }

    @Test fun logoutWithTheBoxUntickedLeavesNoAccountBehind() = runTest {
        val store = MemoryRememberedAccountStore()
        val state = controller(backgroundScope, ok, store)
        state.fill(); state.updateRememberAccount(false); state.login(); runCurrent()
        state.logout(); runCurrent()
        assertNull(store.read()); assertEquals("", state.account)
    }

    // The production AppShell call passes a repository, which AppShell treats as a test override; the Keystore-backed store
    // therefore has to be handed over explicitly (device finding: nothing was ever written to no_backup/remembered-account.bin).
    @Test fun theProductionAppShellKeepsTheAccountInTheEncryptedStoreNotInMemory() {
        val source = java.io.File("src/main/java/com/gccwebos/android/MainActivity.kt").readText()
        val production = source.substringAfter("AppShell(repositoryOverride = current.repository").substringBefore("onConnectionPowerAction")
        assertTrue(production.contains("rememberedAccountStore = remember(applicationContext) { EncryptedRememberedAccountStore(applicationContext) }"))
        assertTrue(source.contains("rememberedAccounts = rememberedAccountStore ?: if (repositoryOverride == null)"))
    }
}
