package com.sourabh.smartqueue.holiday;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link Holiday}.
 *
 * <p>The existence check serves appointment booking ("is this branch
 * date a holiday?"); the date-ordered listing serves calendar
 * management (Phase 4I); the single-day load serves availability
 * (ADR-039, which echoes the closure in its response) — all genuine
 * requirements, not speculation.
 */
public interface HolidayRepository extends JpaRepository<Holiday, UUID> {

	boolean existsByBranchIdAndDate(UUID branchId, LocalDate date);

	List<Holiday> findByBranchIdOrderByDateAsc(UUID branchId);

	Optional<Holiday> findByBranchIdAndDate(UUID branchId, LocalDate date);
}
