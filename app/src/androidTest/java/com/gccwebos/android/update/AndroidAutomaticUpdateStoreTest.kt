package com.gccwebos.android.update

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Preferences belong to the isolated instrumentation APK, never the target application's data. */
class AndroidAutomaticUpdateStoreTest {
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().context) {
        override fun getApplicationContext(): Context = this
    }
    private val preferences get() = context.getSharedPreferences(AndroidAutomaticUpdateStore.PREFERENCES, Context.MODE_PRIVATE)
    @Before fun clearBefore() {
        assertEquals(InstrumentationRegistry.getInstrumentation().context.packageName, context.packageName)
        assertNotEquals(InstrumentationRegistry.getInstrumentation().targetContext.packageName, context.packageName)
        assertTrue(preferences.edit().clear().commit())
    }
    @After fun clearAfter() { assertTrue(preferences.edit().clear().commit()) }

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
