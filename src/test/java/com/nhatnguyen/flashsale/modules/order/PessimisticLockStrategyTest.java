package com.nhatnguyen.flashsale.modules.order;

import com.nhatnguyen.flashsale.common.exception.OutOfStockException;
import com.nhatnguyen.flashsale.common.exception.TicketNotFoundException;
import com.nhatnguyen.flashsale.modules.order.strategy.DeductionResult;
import com.nhatnguyen.flashsale.modules.order.strategy.PessimisticLockStrategy;
import com.nhatnguyen.flashsale.modules.ticket.entity.Ticket;
import com.nhatnguyen.flashsale.modules.ticket.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PessimisticLockStrategyTest {

    @Mock
    private TicketRepository ticketRepository;

    private PessimisticLockStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new PessimisticLockStrategy(ticketRepository);
    }

    @Test
    @DisplayName("Deduct successfully when sufficient stock exists")
    void testDeductSuccess() {
        Ticket ticket = new Ticket(1L, "Concert", "CODE-1", new BigDecimal("100.00"), 10, 10);
        when(ticketRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ticket));

        DeductionResult result = strategy.deduct(1L, 2, "user-1");

        assertThat(result.success()).isTrue();
        assertThat(result.deductedQuantity()).isEqualTo(2);
        assertThat(result.remainingStock()).isEqualTo(8);
        assertThat(ticket.getStock()).isEqualTo(8);
        verify(ticketRepository).save(ticket);
    }

    @Test
    @DisplayName("Throw OutOfStockException when stock is less than requested quantity")
    void testOutOfStock() {
        Ticket ticket = new Ticket(1L, "Concert", "CODE-1", new BigDecimal("100.00"), 1, 10);
        when(ticketRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> strategy.deduct(1L, 2, "user-1"))
                .isInstanceOf(OutOfStockException.class)
                .hasMessageContaining("out of stock");
    }

    @Test
    @DisplayName("Throw TicketNotFoundException when ticket does not exist")
    void testTicketNotFound() {
        when(ticketRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> strategy.deduct(999L, 1, "user-1"))
                .isInstanceOf(TicketNotFoundException.class);
    }
}
