package com.sourabh.smartqueue.workinghour;

import java.time.DayOfWeek;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link WorkingHour}.
 *
 * <p>The day finder serves appointment booking ("intervals for branch +
 * weekday"); the branch listing serves calendar management (Phase
 * 4I) — both genuine requirements, not speculation.
 */
public interface WorkingHourRepository extends JpaRepository<WorkingHour, UUID> {

	List<WorkingHour> findByBranchIdAndDayOfWeek(UUID branchId, DayOfWeek dayOfWeek);

	List<WorkingHour> findByBranchId(UUID branchId);
}
