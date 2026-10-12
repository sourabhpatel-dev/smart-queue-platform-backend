package com.sourabh.smartqueue.queue;

/**
 * The single definition of queue rank (see ADR-014, reused by ADR-015).
 *
 * <p>Both queue claiming and wait-time estimation order entries identically:
 * EMERGENCY first by construction, then a numeric score of base weight
 * ({@code PRIORITY = 1}, else {@code 0}) plus capped aging steps, then
 * {@code joined_at}, then id. These fragments are the <em>only</em> place
 * that ordering lives — the claim query and the ahead-count queries
 * assemble their SQL from them, so the two can never drift apart.
 *
 * <p>Parameters (bound by callers, never interpolated): {@code :nowEpoch}
 * (epoch seconds from the project Clock), {@code :intervalMinutes},
 * {@code :maxPromotions}, {@code :agingEnabled}.
 */
final class QueueRankingSql {

	private QueueRankingSql() {
	}

	/** 0 for EMERGENCY, 1 otherwise — the structural hard ceiling. */
	static String emergencyFlag(String prioritySql) {
		return "(CASE WHEN " + prioritySql + " = 'EMERGENCY' THEN 0 ELSE 1 END)";
	}

	/**
	 * Effective score for the row whose join time is {@code joinedEpochSql}
	 * (either {@code EXTRACT(EPOCH FROM e.joined_at)} or a bound epoch).
	 */
	static String score(String prioritySql, String joinedEpochSql) {
		return "(CASE " + prioritySql + " WHEN 'PRIORITY' THEN 1 ELSE 0 END"
				+ " + LEAST(GREATEST(FLOOR((:nowEpoch - " + joinedEpochSql + ")"
				+ " / 60 / :intervalMinutes), 0), :maxPromotions)"
				+ " * CASE WHEN :agingEnabled THEN 1 ELSE 0 END)";
	}

	/** The claim/estimate ordering, ascending rank = claimed first. */
	static String orderByClause() {
		return emergencyFlag("e.priority") + ",\n"
				+ "  " + score("e.priority", "EXTRACT(EPOCH FROM e.joined_at)") + " DESC,\n"
				+ "  e.joined_at ASC,\n"
				+ "  e.id ASC";
	}
}
