package com.nhatnguyen.flashsale.modules.order.strategy;

import com.nhatnguyen.flashsale.common.exception.OutOfStockException;
import com.nhatnguyen.flashsale.common.exception.TicketNotFoundException;
import com.nhatnguyen.flashsale.modules.ticket.entity.Ticket;
import com.nhatnguyen.flashsale.modules.ticket.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OptimisticLockStrategy implements StockDeductionStrategy {

    private static final Logger log = LoggerFactory.getLogger(OptimisticLockStrategy.class);
    private static final int MAX_RETRIES = 3;

    private final TicketRepository ticketRepository;

    public OptimisticLockStrategy(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Override
    public StrategyType getType() {
        return StrategyType.OPTIMISTIC;
    }

    @Override
    @Transactional
    public DeductionResult deduct(Long ticketId, int quantity, String userId) {
        int attempts = 0;

        while (attempts < MAX_RETRIES) {
            attempts++;

            Ticket ticket = ticketRepository.findById(ticketId)
                    .orElseThrow(() -> new TicketNotFoundException("Ticket not found with id: " + ticketId));

            if (ticket.getStock() < quantity) {
                log.warn("Optimistic lock: Stock depleted for ticketId={}, currentStock={}, requested={}",
                        ticketId, ticket.getStock(), quantity);
                throw new OutOfStockException("Ticket " + ticketId + " is out of stock! Current stock: " + ticket.getStock());
            }

            int rowsUpdated = ticketRepository.deductStockOptimistic(ticketId, quantity, ticket.getVersion());
            if (rowsUpdated > 0) {
                int newStock = ticket.getStock() - quantity;
                log.debug("Optimistic lock deduction succeeded on attempt {} for ticketId={}", attempts, ticketId);
                return DeductionResult.ok(ticketId, quantity, newStock, ticket.getPrice());
            }

            log.debug("Optimistic lock collision detected for ticketId={} on attempt {}, retrying...", ticketId, attempts);
            try {
                Thread.sleep(10 + (long) (Math.random() * 20));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Thread interrupted during optimistic retry", ie);
            }
        }

        throw new OptimisticLockingFailureException("Failed to deduct stock for ticketId=" + ticketId + " after " + MAX_RETRIES + " optimistic lock retries.");
    }
}
