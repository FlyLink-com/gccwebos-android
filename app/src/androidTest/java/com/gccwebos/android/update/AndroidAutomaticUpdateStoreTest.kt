package com.gccwebos.android.update

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** Instrumentation runs as the target UID; only a unique test-owned preference name is writable. */
class AndroidAutomaticUpdateStoreTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureName = "test-official-update-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(target) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            require(name == AndroidAutomaticUpdateStore.PREFERENCES && mode == Context.MODE_PRIVATE)
            return target.getSharedPreferences(fixtureName, Context.MODE_PRIVATE)
        }
    }
    private val preferences get() = context.getSharedPreferences(AndroidAutomaticUpdateStore.PREFERENCES, Context.MODE_PRIVATE)
    @Before fun clearBefore() {
        assertEquals(Process.myUid(), target.applicationInfo.uid)
        assertSame(context, context.applicationContext)
        assertNotEquals(AndroidAutomaticUpdateStore.PREFERENCES, fixtureName)
        assertSame(target.getSharedPreferences(fixtureName, Context.MODE_PRIVATE), preferences)
        assertTrue(preferences.edit().clear().commit())
    }
    @After fun clearAfter() {
        assertTrue(preferences.edit().clear().commit())
        assertTrue(target.deleteSharedPreferences(fixtureName))
    }

    @Test fun attemptsAndHighestPromptedVersionSurviveStoreRecreation() = runBlocking {
        withContext(Dispatchers.IO) {
            val first = AndroidAutomaticUpdateStore(context)
            assertTrue(first.tryBeginCheck(100, 50)); first.recordPrompt(8)
            val second = AndroidAutomaticUpdateStore(context)
            assertFalse(second.tryBeginCheck(149, 50)); assertTrue(second.tryBeginCheck(150, 50))
            assertTrue(second.wasPrompted(8)); assertTrue(second.wasPrompted(7)); assertFalse(second.wasPrompted(9))
            second.recordPrompt(7); assertTrue(AndroidAutomaticUpdateStore(context).wasPrompted(8))
        }
    }

    @Test fun rollbackAndWrongTypesAreReplacedWithoutLosingValidPromptHistory() = runBlocking {
        withContext(Dispatchers.IO) {
            assertTrue(preferences.edit().putLong(AndroidAutomaticUpdateStore.LAST_ATTEMPT, Long.MAX_VALUE)
                .putLong(AndroidAutomaticUpdateStore.HIGHEST_PROMPTED, 8).commit())
            val state = AndroidAutomaticUpdateStore(context)
            assertTrue(state.tryBeginCheck(100, 50)); assertFalse(state.tryBeginCheck(101, 50)); assertTrue(state.wasPrompted(8))
            assertTrue(preferences.edit().putString(AndroidAutomaticUpdateStore.LAST_ATTEMPT, "invalid")
                .putString(AndroidAutomaticUpdateStore.HIGHEST_PROMPTED, "invalid").commit())
            assertTrue(state.tryBeginCheck(200, 50)); assertFalse(state.wasPrompted(8))
            state.recordPrompt(8); assertTrue(state.wasPrompted(8))
        }
    }

    @Test fun outOfRangePersistedVersionsDoNotSuppressARealFutureUpdate() = runBlocking {
        withContext(Dispatchers.IO) {
            for (bad in listOf(-1L, 2_100_000_001L, Int.MAX_VALUE.toLong(), Long.MAX_VALUE)) {
                assertTrue(preferences.edit().putLong(AndroidAutomaticUpdateStore.HIGHEST_PROMPTED, bad).commit())
                val state = AndroidAutomaticUpdateStore(context)
                assertFalse(state.wasPrompted(8)); state.recordPrompt(8); assertTrue(state.wasPrompted(8))
            }
        }
    }
}
