package com.nhatnguyen.flashsale.modules.ticket.repository;

import com.nhatnguyen.flashsale.modules.ticket.entity.Ticket;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    Optional<Ticket> findByTicketCode(String ticketCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Ticket t WHERE t.id = :id")
    Optional<Ticket> findByIdForUpdate(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Ticket t SET t.stock = t.stock - :quantity, t.version = t.version + 1 WHERE t.id = :id AND t.version = :version AND t.stock >= :quantity")
    int deductStockOptimistic(@Param("id") Long id, @Param("quantity") int quantity, @Param("version") Long version);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Ticket t SET t.stock = t.stock - :quantity, t.version = t.version + 1 WHERE t.id = :id AND t.stock >= :quantity")
    int deductStockDirect(@Param("id") Long id, @Param("quantity") int quantity);
}
