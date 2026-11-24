package com.freeftp.core.transfer

import com.freeftp.core.transfer.TransferDirection.DOWNLOAD
import com.freeftp.core.transfer.TransferState.CANCELLED
import com.freeftp.core.transfer.TransferState.COMPLETED
import com.freeftp.core.transfer.TransferState.FAILED
import com.freeftp.core.transfer.TransferState.PAUSED
import com.freeftp.core.transfer.TransferState.QUEUED
import com.freeftp.core.transfer.TransferState.RUNNING
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TransferKeepAlivePolicyTest {

    private fun status(id: String, state: TransferState, transferred: Long = 0, total: Long = -1) =
        TransferStatus(
            request = TransferRequest(id, DOWNLOAD, "/$id", FileTarget(File("/tmp", id))),
            state = state,
            transferred = transferred,
            total = total,
        )

    @Test
    fun `empty queue is idle`() {
        assertNull(TransferKeepAlivePolicy.snapshot(emptyList()))
    }

    @Test
    fun `only queued and running transfers keep the process alive`() {
        val snapshot = TransferKeepAlivePolicy.snapshot(
            listOf(
                status("q", QUEUED),
                status("r", RUNNING, transferred = 100, total = 1000),
                status("done", COMPLETED, transferred = 1000, total = 1000),
                status("failed", FAILED, transferred = 5, total = 1000),
                status("cancelled", CANCELLED),
            ),
        )!!
        assertEquals(2, snapshot.transfersLeft)
    }

    @Test
    fun `paused queue counts as idle`() {
        assertNull(TransferKeepAlivePolicy.snapshot(listOf(status("p", PAUSED))))
    }

    @Test
    fun `aggregate progress sums active transfers`() {
        val snapshot = TransferKeepAlivePolicy.snapshot(
            listOf(
                status("a", RUNNING, transferred = 100, total = 1000),
                status("b", QUEUED, transferred = 20, total = -1),
                status("c", COMPLETED, transferred = 9999, total = 9999),
            ),
        )!!
        assertEquals(120, snapshot.transferred)
        // A size the server never reported adds nothing rather than poisoning the sum.
        assertEquals(1000, snapshot.total)
    }

    @Test
    fun `all-active queue with no finished work still reports a snapshot`() {
        val snapshot = TransferKeepAlivePolicy.snapshot(listOf(status("a", QUEUED)))!!
        assertEquals(1, snapshot.transfersLeft)
        assertEquals(0, snapshot.transferred)
        assertEquals(0, snapshot.total)
    }
}
