package com.nhatnguyen.flashsale.modules.outbox.repository;

import com.nhatnguyen.flashsale.modules.outbox.entity.OutboxEvent;
import com.nhatnguyen.flashsale.modules.outbox.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop50ByStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
            OutboxStatus status, LocalDateTime now);

    long countByStatus(OutboxStatus status);

    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(String aggregateId);
}
