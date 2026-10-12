package com.sourabh.smartqueue.tenant;

/**
 * Thread-local holder for the current {@link TenantContext} (see ADR-019).
 *
 * <p>Lifecycle:
 * <ol>
 *   <li><strong>Set</strong> at the application boundary — a future
 *   authentication filter, a test setup method, or a message-listener
 *   interceptor calls {@link #set(TenantContext)} before dispatching into
 *   application services.</li>
 *   <li><strong>Read</strong> by application services that need to validate
 *   tenant scope — they call {@link #get()} and compare the entity's
 *   organization/branch against the context.</li>
 *   <li><strong>Clear</strong> in a {@code finally} block at the same
 *   boundary — always, even on exception — so no context leaks to the next
 *   request or test on the same thread.</li>
 * </ol>
 *
 * <p>Background tasks (scheduled jobs, event consumers) do NOT use this
 * holder. They discover organization/branch scope from database state or
 * event payloads and operate with explicit IDs — never from a thread-local
 * that was never set for them.
 *
 * <p>This is not a static mutable field: the ThreadLocal is private, the
 * only way to set it is {@link #set(TenantContext)}, and the only way to
 * read it is {@link #get()} (which fails closed) or {@link #getOrNull()}.
 */
public final class TenantContextHolder {

	private static final ThreadLocal<TenantContext> HOLDER = new ThreadLocal<>();

	private TenantContextHolder() {
	}

	/**
	 * Establishes the tenant context for the current thread. Must be paired
	 * with {@link #clear()} in a finally block at the same boundary.
	 */
	public static void set(TenantContext context) {
		HOLDER.set(context);
	}

	/**
	 * @return the current tenant context.
	 * @throws TenantContextRequiredException if no context has been set.
	 * Never returns null — fail closed rather than silently interpreting
	 * null as "all tenants".
	 */
	public static TenantContext get() {
		TenantContext context = HOLDER.get();
		if (context == null) {
			throw new TenantContextRequiredException(
					"no tenant context available — set one at the application boundary");
		}
		return context;
	}

	/**
	 * @return the current tenant context, or {@code null} if none is set.
	 * Use only when the caller explicitly handles the null case (e.g.,
	 * optional scope validation). Prefer {@link #get()} for fail-closed
	 * behavior.
	 */
	public static TenantContext getOrNull() {
		return HOLDER.get();
	}

	/**
	 * Clears the current thread's tenant context. Always call in a finally
	 * block after {@link #set(TenantContext)} to prevent leakage.
	 */
	public static void clear() {
		HOLDER.remove();
	}

	/**
	 * Restores a previously saved context (possibly {@code null}) — the
	 * counterpart to {@link #getOrNull()} for boundaries that install their
	 * own scope temporarily (event bridge, scheduler, controllers). Unlike
	 * {@link #clear()}, this never destroys a context the boundary did not
	 * establish: a boundary must save, set, and restore, not set and clear.
	 */
	public static void restore(TenantContext previous) {
		if (previous == null) {
			HOLDER.remove();
		} else {
			HOLDER.set(previous);
		}
	}
}
