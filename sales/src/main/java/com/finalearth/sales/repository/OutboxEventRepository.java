package com.finalearth.sales.repository;

import com.finalearth.sales.entity.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claim a batch of unpublished events for this relay instance.
     *
     * <p><strong>{@code FOR UPDATE SKIP LOCKED} is the whole point of this query.</strong>
     * The relay is just a scheduled method inside the service, so there are as many relays
     * as there are replicas — two, in {@code inventory}'s case. A plain {@code SELECT}
     * would hand both of them the same rows and every event would be published twice.
     * {@code FOR UPDATE} makes each claim exclusive; {@code SKIP LOCKED} means the second
     * relay steps over the locked rows and takes different work instead of blocking behind
     * the first. The lock is held until the transaction commits, which is exactly as long
     * as the publish takes.
     *
     * <p>Duplicates are still possible — if the broker acknowledges and the transaction
     * then fails to commit, the rows unlock unpublished and are sent again. That is
     * at-least-once, and it is why consumers keep an inbox. What this query prevents is the
     * far more common and entirely avoidable case of two relays racing on the same row.
     *
     * <p><strong>Ordering is per key, not global.</strong> {@code ORDER BY created_at}
     * gives a sensible drain order, but two relays claiming disjoint batches can still
     * publish row 5 before row 4. That is harmless here only because each service emits at
     * most one event per order per saga step; a service that emitted two events for the
     * same key in quick succession would need to claim by key, not by age.
     */
    @Query(value = """
            SELECT * FROM sales.outbox
            WHERE published_at IS NULL
            ORDER BY created_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimUnpublished(@Param("batchSize") int batchSize);

    long countByPublishedAtIsNull();
}
