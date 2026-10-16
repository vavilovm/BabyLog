package com.mark.babylog

import com.mark.babylog.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FamilySyncBehaviorTest {
    @Test
    fun batchCancellationNeverFallsBackOrFailsAnOperation() = runTest {
        val cancelled = CancellationException("cancelled")
        var legacyCalls = 0
        var failures = 0
        try {
            pushWithLegacyFallback(
                listOf(1, 2),
                { throw cancelled },
                { legacyCalls++ },
                { _, _ -> failures++ },
            )
            fail("Cancellation must propagate")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
        assertEquals(0, legacyCalls)
        assertEquals(0, failures)
    }

    @Test
    fun legacyCancellationStopsRemainingRequestsWithoutFailure() = runTest {
        val cancelled = CancellationException("cancelled")
        val called = mutableListOf<Int>()
        var failures = 0
        try {
            pushWithLegacyFallback(
                listOf(1, 2),
                { error("batch unavailable") },
                {
                    called += it
                    throw cancelled
                },
                { _, _ -> failures++ },
            )
            fail("Cancellation must propagate")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
        assertEquals(listOf(1), called)
        assertEquals(0, failures)
    }

    @Test
    fun normalBatchFailureStillUsesLegacyAndRecordsActualFailure() = runTest {
        val failure = IllegalStateException("private backend detail")
        val called = mutableListOf<Int>()
        val failed = mutableListOf<Int>()
        try {
            pushWithLegacyFallback(
                listOf(1, 2, 3),
                { error("batch unavailable") },
                {
                    called += it
                    if (it == 2) throw failure
                },
                { op, _ -> failed += op },
            )
            fail("Failure must propagate")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
        assertEquals(listOf(1, 2), called)
        assertEquals(listOf(2), failed)
    }

    @Test
    fun cancelledSyncPreservesLastSuccessAndDoesNotReturnRetryOrFailure() = runTest {
        val status = MutableStateFlow(FamilySyncStatus(lastSyncedAt = 123))
        try {
            runFamilySync(status) {
                assertTrue(status.value.running)
                throw CancellationException()
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {}
        assertEquals(FamilySyncStatus(lastSyncedAt = 123), status.value)
    }

    @Test
    fun failedSyncPreservesLastSuccessAndHidesBackendDetails() = runTest {
        val status = MutableStateFlow(FamilySyncStatus(lastSyncedAt = 123))
        assertFalse(runFamilySync(status) { error("secret server detail") })
        assertEquals(123L, status.value.lastSyncedAt)
        assertFalse(status.value.running)
        assertFalse(status.value.error.orEmpty().contains("secret"))
        assertTrue(runFamilySync(status, now = { 456 }) {})
        assertEquals(FamilySyncStatus(lastSyncedAt = 456), status.value)
    }

    @Test
    fun familyActionsDoNotWrapCancellationInOrdinaryResult() = runTest {
        try {
            familyResult<Unit> { throw CancellationException() }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {}
        assertTrue(familyResult<Unit> { error("ordinary failure") }.isFailure)
    }

    @Test
    fun pendingChangesAreNeverLabelledSynced() {
        val success = FamilySyncStatus(lastSyncedAt = 123)
        assertEquals(SyncDisplayState.PENDING, syncDisplayState(true, 1, success))
        assertEquals(SyncDisplayState.SYNCED, syncDisplayState(true, 0, success))
        assertEquals(SyncDisplayState.READY, syncDisplayState(true, -1, success))
        assertEquals(SyncDisplayState.READY, syncDisplayState(true, 0, FamilySyncStatus()))
        assertEquals(
            SyncDisplayState.SYNCING,
            syncDisplayState(true, 1, success.copy(running = true)),
        )
        assertEquals(
            SyncDisplayState.FAILED,
            syncDisplayState(true, 1, success.copy(error = "error")),
        )
        assertEquals(
            SyncDisplayState.LOCAL,
            syncDisplayState(false, 1, success.copy(error = "error")),
        )
    }
}
