package com.sourabh.smartqueue.branch;

import java.time.ZoneId;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link ZoneId} as its IANA identifier (e.g. {@code Asia/Kolkata}).
 *
 * <p>IANA IDs carry full DST rules; a bare offset such as {@code UTC+05:30}
 * would silently misinterpret instants across DST transitions. Invalid IDs
 * cannot reach the database: {@code ZoneId.of} rejects them when the entity
 * is built (see ADR-011).
 */
@Converter
public class ZoneIdConverter implements AttributeConverter<ZoneId, String> {

	@Override
	public String convertToDatabaseColumn(ZoneId attribute) {
		return attribute == null ? null : attribute.getId();
	}

	@Override
	public ZoneId convertToEntityAttribute(String dbData) {
		return dbData == null ? null : ZoneId.of(dbData);
	}
}
