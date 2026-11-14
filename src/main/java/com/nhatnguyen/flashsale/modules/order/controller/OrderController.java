package com.nhatnguyen.flashsale.modules.order.controller;

import com.nhatnguyen.flashsale.common.exception.OrderStateConflictException;
import com.nhatnguyen.flashsale.modules.order.dto.CreateOrderRequest;
import com.nhatnguyen.flashsale.modules.order.dto.OrderResponse;
import com.nhatnguyen.flashsale.modules.order.service.OrderExpirationService;
import com.nhatnguyen.flashsale.modules.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@Validated
public class OrderController {

    private final OrderService orderService;
    private final OrderExpirationService orderExpirationService;

    public OrderController(OrderService orderService, OrderExpirationService orderExpirationService) {
        this.orderService = orderService;
        this.orderExpirationService = orderExpirationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<OrderResponse> createOrder(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {

        OrderResponse response = orderService.createOrder(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{orderNumber}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable String orderNumber) {
        return orderService.getOrderByNumber(orderNumber)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{orderNumber}/expire")
    public ResponseEntity<OrderResponse> expireOrder(@PathVariable String orderNumber) {
        boolean expired = orderExpirationService.expireOrderIfUnpaid(orderNumber);
        OrderResponse order = orderService.getOrderByNumber(orderNumber).orElse(null);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        if (!expired) {
            throw new OrderStateConflictException(
                    "Cannot expire order " + orderNumber + " in status " + order.status()
            );
        }
        return ResponseEntity.ok(order);
    }
}
