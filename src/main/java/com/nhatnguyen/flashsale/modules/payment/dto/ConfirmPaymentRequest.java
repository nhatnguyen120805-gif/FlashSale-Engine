package com.nhatnguyen.flashsale.modules.payment.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmPaymentRequest(
        @NotBlank(message = "Order number must not be blank")
        String orderNumber,
        String paymentMethod
) {
}
