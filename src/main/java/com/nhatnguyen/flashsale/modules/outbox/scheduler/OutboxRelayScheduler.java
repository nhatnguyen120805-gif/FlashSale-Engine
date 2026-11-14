package com.nhatnguyen.flashsale.modules.outbox.scheduler;

import com.nhatnguyen.flashsale.modules.outbox.service.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "flashsale.outbox.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxService outboxService;

    public OutboxRelayScheduler(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @Scheduled(fixedDelayString = "${flashsale.outbox.poll-interval-ms:5000}")
    public void relayPendingEvents() {
        int published = outboxService.processPendingEvents();
        if (published > 0) {
            log.info("OutboxRelayScheduler published {} pending event(s) to RabbitMQ", published);
        }
    }
}
