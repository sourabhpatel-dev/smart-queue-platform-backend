package com.sourabh.smartqueue.queue;

import java.time.LocalDate;

import com.sourabh.smartqueue.common.BaseAuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The next walk-in token number for one queue on one business date
 * (see ADR-012).
 *
 * <p>Why a row instead of {@code MAX(token)+1}: concurrent joins would read
 * the same maximum and insert duplicates. Here the joining transaction
 * locks this row ({@code SELECT … FOR UPDATE}), takes the number, and
 * increments — concurrent joins serialize on the row lock instead of
 * colliding. First join of the day creates the row; the loser of that
 * insert race retries and finds it present.
 */
@Entity
@Table(
		name = "queue_token_counter",
		uniqueConstraints = @UniqueConstraint(
				name = "uq_token_counter_queue_date",
				columnNames = {"queue_id", "business_date"}))
public class QueueTokenCounter extends BaseAuditableEntity {

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "queue_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_token_counter_queue"))
	private Queue queue;

	@NotNull
	@Column(name = "business_date", nullable = false)
	private LocalDate businessDate;

	@NotNull
	@Min(1)
	@Column(name = "next_number", nullable = false)
	private int nextNumber = 1;

	protected QueueTokenCounter() {
		// JPA only.
	}

	public QueueTokenCounter(Queue queue, LocalDate businessDate) {
		this.queue = queue;
		this.businessDate = businessDate;
	}

	public Queue getQueue() {
		return queue;
	}

	public LocalDate getBusinessDate() {
		return businessDate;
	}

	public int getNextNumber() {
		return nextNumber;
	}

	public void advance() {
		this.nextNumber++;
	}
}
