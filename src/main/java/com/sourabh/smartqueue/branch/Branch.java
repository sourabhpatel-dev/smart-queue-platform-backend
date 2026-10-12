package com.sourabh.smartqueue.branch;

import java.time.ZoneId;

import com.sourabh.smartqueue.common.BaseAuditableEntity;
import com.sourabh.smartqueue.organization.Organization;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A physical location belonging to exactly one Organization (see ADR-007).
 *
 * <p>Relationship is deliberately unidirectional ({@code Branch → Organization},
 * lazy, no cascade): nothing in this phase navigates from an Organization to
 * its branches, and persisting a branch must never implicitly persist an
 * organization. The foreign key (plus {@code optional = false}) makes
 * "no branch without an organization" a database guarantee, not just a
 * Java-side one — the anchor future tenant isolation builds on.
 *
 * <p>Address is flat columns for now: enough for a real location, without
 * over-modelling (no separate address entity/value-table until a phase
 * needs geospatial or multi-address support).
 *
 * <p>Every branch declares its IANA time zone (e.g. {@code Asia/Kolkata}):
 * appointment instants are meaningless without knowing whose wall clock
 * they belong to, and a bare offset would lose DST rules. Required at
 * construction — a branch silently defaulting to UTC would book the wrong
 * local times (see ADR-011).
 */
@Entity
@Table(
		name = "branch",
		uniqueConstraints = @UniqueConstraint(
				name = "uq_branch_organization_name",
				columnNames = {"organization_id", "name"}))
public class Branch extends BaseAuditableEntity {

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "organization_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_branch_organization"))
	private Organization organization;

	@NotBlank
	@Size(max = 150)
	@Column(name = "name", nullable = false, length = 150)
	private String name;

	@Size(max = 255)
	@Column(name = "address_line1", length = 255)
	private String addressLine1;

	@Size(max = 255)
	@Column(name = "address_line2", length = 255)
	private String addressLine2;

	@NotBlank
	@Size(max = 100)
	@Column(name = "city", nullable = false, length = 100)
	private String city;

	@Size(max = 100)
	@Column(name = "state", length = 100)
	private String state;

	@Size(max = 20)
	@Column(name = "postal_code", length = 20)
	private String postalCode;

	@NotBlank
	@Size(max = 100)
	@Column(name = "country", nullable = false, length = 100)
	private String country;

	@NotNull
	@Convert(converter = ZoneIdConverter.class)
	@Column(name = "time_zone", nullable = false, length = 100)
	private ZoneId timeZone;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private BranchStatus status = BranchStatus.ACTIVE;

	protected Branch() {
		// JPA only.
	}

	public Branch(Organization organization, String name, String city, String country,
			ZoneId timeZone) {
		this.organization = organization;
		this.name = name;
		this.city = city;
		this.country = country;
		this.timeZone = timeZone;
	}

	public Organization getOrganization() {
		return organization;
	}

	public void setOrganization(Organization organization) {
		this.organization = organization;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getAddressLine1() {
		return addressLine1;
	}

	public void setAddressLine1(String addressLine1) {
		this.addressLine1 = addressLine1;
	}

	public String getAddressLine2() {
		return addressLine2;
	}

	public void setAddressLine2(String addressLine2) {
		this.addressLine2 = addressLine2;
	}

	public String getCity() {
		return city;
	}

	public void setCity(String city) {
		this.city = city;
	}

	public String getState() {
		return state;
	}

	public void setState(String state) {
		this.state = state;
	}

	public String getPostalCode() {
		return postalCode;
	}

	public void setPostalCode(String postalCode) {
		this.postalCode = postalCode;
	}

	public String getCountry() {
		return country;
	}

	public void setCountry(String country) {
		this.country = country;
	}

	public ZoneId getTimeZone() {
		return timeZone;
	}

	public void setTimeZone(ZoneId timeZone) {
		this.timeZone = timeZone;
	}

	public BranchStatus getStatus() {
		return status;
	}

	public void setStatus(BranchStatus status) {
		this.status = status;
	}
}
