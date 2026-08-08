package com.claustrum.objects

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LatestOnlyQueueTest {
    @Test
    fun replacesPendingWithLatestWithoutStartingSecondWorker() {
        val discarded = mutableListOf<Int>()
        val queue = LatestOnlyQueue<Int>(discarded::add)

        assertThat(queue.offer(1)).isEqualTo(LatestOffer(startWorker = true, replacedPending = false))
        assertThat(queue.offer(2)).isEqualTo(LatestOffer(startWorker = false, replacedPending = true))
        assertThat(discarded).containsExactly(1)
        assertThat(queue.takeOrReleaseWorker()).isEqualTo(2)
        assertThat(queue.takeOrReleaseWorker()).isNull()
    }

    @Test
    fun releasedWorkerLetsNextOfferStartOneWorker() {
        val queue = LatestOnlyQueue<Int> {}
        assertThat(queue.offer(1).startWorker).isTrue()
        assertThat(queue.takeOrReleaseWorker()).isEqualTo(1)
        assertThat(queue.takeOrReleaseWorker()).isNull()

        assertThat(queue.offer(2).startWorker).isTrue()
        assertThat(queue.takeOrReleaseWorker()).isEqualTo(2)
    }

    @Test
    fun clearDiscardsPendingOwnership() {
        val discarded = mutableListOf<Int>()
        val queue = LatestOnlyQueue<Int>(discarded::add)
        queue.offer(7)

        queue.clear()

        assertThat(discarded).containsExactly(7)
        assertThat(queue.takeOrReleaseWorker()).isNull()
    }
}
