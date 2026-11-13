package com.nhatnguyen.flashsale.modules.order.strategy;

import com.nhatnguyen.flashsale.common.exception.OutOfStockException;
import com.nhatnguyen.flashsale.common.exception.TicketNotFoundException;
import com.nhatnguyen.flashsale.modules.ticket.entity.Ticket;
import com.nhatnguyen.flashsale.modules.ticket.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PessimisticLockStrategy implements StockDeductionStrategy {

    private static final Logger log = LoggerFactory.getLogger(PessimisticLockStrategy.class);

    private final TicketRepository ticketRepository;

    public PessimisticLockStrategy(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Override
    public StrategyType getType() {
        return StrategyType.PESSIMISTIC;
    }

    @Override
    @Transactional
    public DeductionResult deduct(Long ticketId, int quantity, String userId) {
        log.debug("Executing Pessimistic Lock deduction for ticketId={}, quantity={}, userId={}", ticketId, quantity, userId);

        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new TicketNotFoundException("Ticket not found with id: " + ticketId));

        if (ticket.getStock() < quantity) {
            log.warn("Pessimistic lock: Stock depleted for ticketId={}, currentStock={}, requested={}",
                    ticketId, ticket.getStock(), quantity);
            throw new OutOfStockException("Ticket " + ticketId + " is out of stock! Current stock: " + ticket.getStock());
        }

        int newStock = ticket.getStock() - quantity;
        ticket.setStock(newStock);
        ticketRepository.save(ticket);

        return DeductionResult.ok(ticketId, quantity, newStock, ticket.getPrice());
    }
}
