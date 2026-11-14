package com.nhatnguyen.flashsale.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class FlashSaleMetrics {

    private final Counter ordersCreatedCounter;
    private final Counter ordersOutOfStockCounter;
    private final Counter ordersExpiredCompensatedCounter;
    private final Counter paymentsConfirmedCounter;
    private final Counter outboxPublishedCounter;
    private final Counter outboxFailedCounter;
    private final Timer orderDeductionLatencyTimer;

    @Autowired
    public FlashSaleMetrics(MeterRegistry meterRegistry) {
        this.ordersCreatedCounter = Counter.builder("flashsale.orders.created")
                .description("Total orders successfully created and reserved")
                .register(meterRegistry);
        this.ordersOutOfStockCounter = Counter.builder("flashsale.orders.out_of_stock")
                .description("Total order attempts rejected due to insufficient stock")
                .register(meterRegistry);
        this.ordersExpiredCompensatedCounter = Counter.builder("flashsale.orders.expired_compensated")
                .description("Total unpaid orders expired via TTL and compensated back to stock")
                .register(meterRegistry);
        this.paymentsConfirmedCounter = Counter.builder("flashsale.payments.confirmed")
                .description("Total payments successfully confirmed")
                .register(meterRegistry);
        this.outboxPublishedCounter = Counter.builder("flashsale.outbox.published")
                .description("Total outbox events published to RabbitMQ")
                .register(meterRegistry);
        this.outboxFailedCounter = Counter.builder("flashsale.outbox.failed")
                .description("Total outbox events that exhausted retries and entered FAILED state")
                .register(meterRegistry);
        this.orderDeductionLatencyTimer = Timer.builder("flashsale.order.deduction.latency")
                .description("Stock deduction latency across concurrency strategies")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    public FlashSaleMetrics() {
        this(new SimpleMeterRegistry());
    }

    public void recordOrderCreated() {
        ordersCreatedCounter.increment();
    }

    public void recordOrderOutOfStock() {
        ordersOutOfStockCounter.increment();
    }

    public void recordOrderExpiredCompensated() {
        ordersExpiredCompensatedCounter.increment();
    }

    public void recordPaymentConfirmed() {
        paymentsConfirmedCounter.increment();
    }

    public void recordOutboxPublished() {
        outboxPublishedCounter.increment();
    }

    public void recordOutboxFailed() {
        outboxFailedCounter.increment();
    }

    public void recordDeductionLatencyNanos(long elapsedNanos) {
        orderDeductionLatencyTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }
}
