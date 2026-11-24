package com.freeftp.core.transfer

/**
 * Pure decision logic for keeping the process alive while the transfer queue works.
 *
 * Android freezes cached background processes within seconds of the app leaving the
 * foreground, which silently kills the sockets under a running transfer. The app
 * therefore runs a foreground service whenever anything is queued or running. The
 * service owns all Android interaction; this object carries only the decisions, so
 * they stay unit-testable in :core.
 */
object TransferKeepAlivePolicy {

    /** Everything the keep-alive needs to know about the queue right now. */
    data class Snapshot(
        val transfersLeft: Int,
        val transferred: Long,
        val total: Long,
    )

    /**
     * The active work, or null when the queue is idle (nothing queued or running).
     *
     * A paused queue counts as idle: the user asked for it to stop, so the process
     * need not burn battery holding connections open. Resuming the queue re-triggers
     * the keep-alive through the same path that started it in the first place.
     */
    fun snapshot(transfers: List<TransferStatus>): Snapshot? {
        val active = transfers.filter { it.state.isActive }
        if (active.isEmpty()) return null
        return Snapshot(
            transfersLeft = active.size,
            transferred = active.sumOf { it.transferred },
            // Sizes the server has not reported are -1; they contribute nothing rather
            // than poisoning the sum.
            total = active.sumOf { maxOf(it.total, 0L) },
        )
    }
}
