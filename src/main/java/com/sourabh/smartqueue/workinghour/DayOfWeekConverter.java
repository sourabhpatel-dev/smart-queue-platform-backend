package com.sourabh.smartqueue.workinghour;

import java.time.DayOfWeek;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link DayOfWeek} by name ({@code MONDAY}…{@code SUNDAY}).
 *
 * <p>JPA has no built-in {@code DayOfWeek} mapping, and an ordinal or magic
 * number would corrupt meaning on reorder — names stay readable in
 * {@code psql} and stable forever, consistent with the project's
 * string-persisted enums (see ADR-010).
 */
@Converter
public class DayOfWeekConverter implements AttributeConverter<DayOfWeek, String> {

	@Override
	public String convertToDatabaseColumn(DayOfWeek attribute) {
		return attribute == null ? null : attribute.name();
	}

	@Override
	public DayOfWeek convertToEntityAttribute(String dbData) {
		return dbData == null ? null : DayOfWeek.valueOf(dbData);
	}
}
