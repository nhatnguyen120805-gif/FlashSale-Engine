package com.nhatnguyen.flashsale.modules.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnguyen.flashsale.common.metrics.FlashSaleMetrics;
import com.nhatnguyen.flashsale.modules.outbox.dto.OutboxStatsResponse;
import com.nhatnguyen.flashsale.modules.outbox.entity.OutboxEvent;
import com.nhatnguyen.flashsale.modules.outbox.entity.OutboxStatus;
import com.nhatnguyen.flashsale.modules.outbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);
    private static final int MAX_RETRIES = 3;

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final FlashSaleMetrics flashSaleMetrics;

    public OutboxService(OutboxEventRepository outboxEventRepository,
                         RabbitTemplate rabbitTemplate,
                         ObjectMapper objectMapper,
                         FlashSaleMetrics flashSaleMetrics) {
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.flashSaleMetrics = flashSaleMetrics;
    }

    @Transactional
    public OutboxEvent enqueue(String aggregateType,
                               String aggregateId,
                               String eventType,
                               String exchange,
                               String routingKey,
                               Object payloadObj) {
        String payloadJson = serializePayload(payloadObj);
        OutboxEvent event = new OutboxEvent(
                aggregateType,
                aggregateId,
                eventType,
                exchange,
                routingKey,
                payloadJson
        );
        OutboxEvent saved = outboxEventRepository.save(event);
        log.debug("Enqueued outbox event id={}, type={}, aggregateId={}", saved.getId(), eventType, aggregateId);
        return saved;
    }

    @Transactional
    public int processPendingEvents() {
        return processPendingEvents(LocalDateTime.now());
    }

    @Transactional
    public int processPendingEvents(LocalDateTime referenceTime) {
        List<OutboxEvent> dueEvents = outboxEventRepository
                .findTop50ByStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(OutboxStatus.PENDING, referenceTime);
        int publishedCount = 0;

        for (OutboxEvent event : dueEvents) {
            try {
                Message amqpMessage = MessageBuilder
                        .withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setHeader("X-Event-Type", event.getEventType())
                        .setHeader("X-Aggregate-Id", event.getAggregateId())
                        .build();

                rabbitTemplate.convertAndSend(event.getExchange(), event.getRoutingKey(), (Object) amqpMessage);

                event.setStatus(OutboxStatus.PUBLISHED);
                event.setProcessedAt(referenceTime);
                event.setLastError(null);
                outboxEventRepository.save(event);

                flashSaleMetrics.recordOutboxPublished();
                publishedCount++;
                log.debug("Published outbox event id={}, type={}, aggregateId={}",
                        event.getId(), event.getEventType(), event.getAggregateId());
            } catch (Exception ex) {
                handlePublishFailure(event, referenceTime, ex);
            }
        }
        return publishedCount;
    }

    @Transactional(readOnly = true)
    public OutboxStatsResponse getStats() {
        return new OutboxStatsResponse(
                outboxEventRepository.countByStatus(OutboxStatus.PENDING),
                outboxEventRepository.countByStatus(OutboxStatus.PUBLISHED),
                outboxEventRepository.countByStatus(OutboxStatus.FAILED)
        );
    }

    private void handlePublishFailure(OutboxEvent event, LocalDateTime referenceTime, Exception ex) {
        int nextRetryCount = event.getRetryCount() + 1;
        event.setRetryCount(nextRetryCount);
        event.setLastError(truncateError(ex.getMessage()));

        long backoffSeconds = 5L * (1L << Math.min(nextRetryCount, 10));
        event.setNextRetryAt(referenceTime.plusSeconds(backoffSeconds));

        if (nextRetryCount >= MAX_RETRIES) {
            event.setStatus(OutboxStatus.FAILED);
            event.setProcessedAt(referenceTime);
            flashSaleMetrics.recordOutboxFailed();
            log.error("Outbox event id={} tripped circuit-breaker to FAILED after {} retries: {}",
                    event.getId(), nextRetryCount, event.getLastError());
        } else {
            log.warn("Outbox event id={} publish failed (retry {}/{}), next retry at {}: {}",
                    event.getId(), nextRetryCount, MAX_RETRIES, event.getNextRetryAt(), event.getLastError());
        }
        outboxEventRepository.save(event);
    }

    private String serializePayload(Object payloadObj) {
        if (payloadObj instanceof String raw) {
            return raw;
        }
        try {
            return objectMapper.writeValueAsString(payloadObj);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize outbox payload", ex);
        }
    }

    private String truncateError(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown error";
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
