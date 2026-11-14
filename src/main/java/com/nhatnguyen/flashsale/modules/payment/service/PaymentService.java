package com.nhatnguyen.flashsale.modules.payment.service;

import com.nhatnguyen.flashsale.common.exception.OrderStateConflictException;
import com.nhatnguyen.flashsale.common.metrics.FlashSaleMetrics;
import com.nhatnguyen.flashsale.modules.order.entity.Order;
import com.nhatnguyen.flashsale.modules.order.entity.OrderStatus;
import com.nhatnguyen.flashsale.modules.order.repository.OrderRepository;
import com.nhatnguyen.flashsale.modules.outbox.service.OutboxService;
import com.nhatnguyen.flashsale.modules.payment.dto.ConfirmPaymentRequest;
import com.nhatnguyen.flashsale.modules.payment.dto.PaymentResponse;
import com.nhatnguyen.flashsale.modules.payment.entity.Payment;
import com.nhatnguyen.flashsale.modules.payment.entity.PaymentStatus;
import com.nhatnguyen.flashsale.modules.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final OutboxService outboxService;
    private final FlashSaleMetrics flashSaleMetrics;

    @Value("${flashsale.rabbitmq.exchange:flashsale.order.exchange}")
    private String orderExchangeName;

    public PaymentService(OrderRepository orderRepository,
                          PaymentRepository paymentRepository,
                          OutboxService outboxService,
                          FlashSaleMetrics flashSaleMetrics) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.outboxService = outboxService;
        this.flashSaleMetrics = flashSaleMetrics;
    }

    @Transactional
    public PaymentResponse confirmPayment(ConfirmPaymentRequest request) {
        String orderNumber = request.orderNumber();
        Order order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new OrderStateConflictException("Order not found: " + orderNumber));

        if (order.getStatus() == OrderStatus.CONFIRMED) {
            Optional<Payment> existingPayment = paymentRepository.findByOrderNumber(orderNumber);
            if (existingPayment.isPresent()) {
                log.info("Idempotent payment hit for already-confirmed orderNumber={}", orderNumber);
                return PaymentResponse.from(
                        existingPayment.get(),
                        OrderStatus.CONFIRMED,
                        "Payment already confirmed (idempotent)"
                );
            }
        }

        if (order.getStatus() == OrderStatus.EXPIRED
                || order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.FAILED) {
            throw new OrderStateConflictException(
                    "Cannot pay for order " + orderNumber + " in status " + order.getStatus()
            );
        }

        int updated = orderRepository.transitionStatusAtomically(
                orderNumber,
                OrderStatus.PENDING,
                OrderStatus.CONFIRMED
        );
        if (updated == 0) {
            throw new OrderStateConflictException(
                    "Order " + orderNumber + " expired or was modified concurrently"
            );
        }

        String transactionId = "TXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        Payment payment = new Payment(transactionId, orderNumber, order.getTotalPrice(), PaymentStatus.SUCCESS);
        Payment savedPayment = paymentRepository.save(payment);

        outboxService.enqueue(
                "PAYMENT",
                orderNumber,
                "PAYMENT_CONFIRMED",
                orderExchangeName,
                "flashsale.payment.confirmed",
                Map.of(
                        "transactionId", transactionId,
                        "orderNumber", orderNumber,
                        "amount", order.getTotalPrice(),
                        "paymentMethod", request.paymentMethod() != null ? request.paymentMethod() : "SIMULATED_CARD",
                        "timestamp", System.currentTimeMillis()
                )
        );

        flashSaleMetrics.recordPaymentConfirmed();
        log.info("Payment {} confirmed for orderNumber={}, amount={}", transactionId, orderNumber, order.getTotalPrice());

        return PaymentResponse.from(savedPayment, OrderStatus.CONFIRMED, "Payment confirmed successfully");
    }
}
