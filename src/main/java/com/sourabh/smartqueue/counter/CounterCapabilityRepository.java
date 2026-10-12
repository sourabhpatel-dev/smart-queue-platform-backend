package com.sourabh.smartqueue.counter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sourabh.smartqueue.service.Service;

/**
 * Persistence access for {@link CounterCapability}.
 *
 * <p>The two finders serve the Phase 2B relationship tests and are exactly
 * the reads the future queue engine needs ("services supported by a
 * counter", "counters supporting a service"). Nothing beyond that.
 */
public interface CounterCapabilityRepository extends JpaRepository<CounterCapability, UUID> {

	List<CounterCapability> findByService(Service service);

	List<CounterCapability> findByCounter(ServiceCounter counter);

	List<CounterCapability> findByCounterId(UUID counterId);

	Optional<CounterCapability> findByCounterIdAndServiceId(UUID counterId,
			UUID serviceId);
}
