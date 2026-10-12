package com.sourabh.smartqueue.common.api;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.sourabh.smartqueue.analytics.AnalyticsScopeMismatchException;
import com.sourabh.smartqueue.appointment.AppointmentConflictException;
import com.sourabh.smartqueue.appointment.AppointmentBranchMismatchException;
import com.sourabh.smartqueue.auth.EmailNotVerifiedException;
import com.sourabh.smartqueue.auth.RateLimitExceededException;
import com.sourabh.smartqueue.holiday.HolidayConflictException;
import com.sourabh.smartqueue.workinghour.WorkingHourConflictException;
import com.sourabh.smartqueue.appointment.AppointmentInvalidTransitionException;
import com.sourabh.smartqueue.appointment.BranchClosedException;
import com.sourabh.smartqueue.appointment.InvalidAppointmentRequestException;
import com.sourabh.smartqueue.appointment.OutsideWorkingHoursException;
import com.sourabh.smartqueue.auth.AuthService;
import com.sourabh.smartqueue.branch.BranchNameConflictException;
import com.sourabh.smartqueue.branch.InvalidBranchRequestException;
import com.sourabh.smartqueue.counter.CapabilityAlreadyExistsException;
import com.sourabh.smartqueue.counter.CapabilityBranchMismatchException;
import com.sourabh.smartqueue.counter.CapabilityNotFoundException;
import com.sourabh.smartqueue.counter.CounterNameConflictException;
import com.sourabh.smartqueue.platform.OrganizationTransitionException;
import com.sourabh.smartqueue.service.ServiceNameConflictException;
import com.sourabh.smartqueue.staff.StaffAssignmentBranchMismatchException;
import com.sourabh.smartqueue.staff.StaffAssignmentConflictException;
import com.sourabh.smartqueue.staff.StaffAssignmentNotFoundException;
import com.sourabh.smartqueue.user.InvalidUserRequestException;
import com.sourabh.smartqueue.user.UserEmailConflictException;
import com.sourabh.smartqueue.queue.CounterCannotServeQueueException;
import com.sourabh.smartqueue.queue.InvalidQueueRequestException;
import com.sourabh.smartqueue.queue.NoWaitingQueueEntryException;
import com.sourabh.smartqueue.queue.QueueBranchMismatchException;
import com.sourabh.smartqueue.queue.QueueConflictException;
import com.sourabh.smartqueue.queue.QueueEntryNotFoundException;
import com.sourabh.smartqueue.queue.QueueEntryStateException;
import com.sourabh.smartqueue.queue.QueueNotActiveException;
import com.sourabh.smartqueue.queue.QueueNotFoundException;
import com.sourabh.smartqueue.queue.ServiceSessionNotFoundException;
import com.sourabh.smartqueue.tenant.TenantContextRequiredException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Centralized REST failure translation (see ADR-022).
 *
 * <p>Replaces the narrow Phase 3F {@code AuthExceptionHandler}: login
 * failures keep their exact status codes and generic messages, now rendered
 * in the project-wide {@link ApiError} envelope. Every branch below maps a
 * known application exception to a stable {@code code} + HTTP status; the
 * final fallback logs the failure (without credentials — see below) and
 * returns a generic {@code 500}.
 *
 * <p>Logging discipline: the request URI and failure kind are logged; the
 * {@code Authorization} header, tokens, passwords, and hashes are never read
 * here and never logged. Unexpected failures log the stack trace server-side
 * only — the response body stays generic.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalRestExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalRestExceptionHandler.class);

	private static final String INTERNAL_MESSAGE = "Unexpected server error";

	private ApiError body(HttpStatus status, String code, String message,
			HttpServletRequest request, List<ApiError.FieldError> fieldErrors) {
		return new ApiError(Instant.now(), status.value(), code, message,
				request.getRequestURI(), fieldErrors == null ? List.of() : fieldErrors);
	}

	private ResponseEntity<ApiError> entity(HttpStatus status, String code,
			String message, HttpServletRequest request) {
		return ResponseEntity.status(status)
				.body(body(status, code, message, request, List.of()));
	}

	// -- Request shape ---------------------------------------------------

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e,
			HttpServletRequest request) {
		List<ApiError.FieldError> fields = e.getBindingResult().getFieldErrors().stream()
				.map(f -> new ApiError.FieldError(f.getField(),
						f.getDefaultMessage() == null ? "invalid value" : f.getDefaultMessage()))
				.sorted(Comparator.comparing(ApiError.FieldError::field)
						.thenComparing(ApiError.FieldError::message))
				.toList();
		return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST,
				"VALIDATION_ERROR", "Request validation failed", request, fields));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e,
			HttpServletRequest request) {
		return entity(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
				"Malformed JSON request", request);
	}

	@ExceptionHandler({MethodArgumentTypeMismatchException.class,
			MissingServletRequestParameterException.class})
	public ResponseEntity<ApiError> invalidRequest(Exception e, HttpServletRequest request) {
		return entity(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
				"Invalid request parameter", request);
	}

	@ExceptionHandler(InvalidRequestException.class)
	public ResponseEntity<ApiError> invalidApiRequest(InvalidRequestException e,
			HttpServletRequest request) {
		return entity(HttpStatus.BAD_REQUEST, e.getCode(), e.getMessage(), request);
	}

	@ExceptionHandler(InvalidBranchRequestException.class)
	public ResponseEntity<ApiError> invalidBranch(InvalidBranchRequestException e,
			HttpServletRequest request) {
		return entity(HttpStatus.BAD_REQUEST, "INVALID_BRANCH_REQUEST",
				e.getMessage(), request);
	}

	@ExceptionHandler(InvalidUserRequestException.class)
	public ResponseEntity<ApiError> invalidUser(InvalidUserRequestException e,
			HttpServletRequest request) {
		return entity(HttpStatus.BAD_REQUEST, "INVALID_USER_REQUEST",
				e.getMessage(), request);
	}

	// -- Authentication / authorization ----------------------------------

	@ExceptionHandler(BadCredentialsException.class)
	public ResponseEntity<ApiError> badCredentials(BadCredentialsException e,
			HttpServletRequest request) {
		// Generic by design (no account enumeration — see AuthService).
		// The constant lives with the login flow as the single source.
		return entity(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
				AuthService.GENERIC_FAILURE, request);
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ApiError> denied(AccessDeniedException e, HttpServletRequest request) {
		// Fixed message mirrors the filter-level 403 body: authenticated
		// callers learn nothing about the resource beyond the denial.
		return entity(HttpStatus.FORBIDDEN, "FORBIDDEN",
				"Insufficient authorization", request);
	}

	@ExceptionHandler(EmailNotVerifiedException.class)
	public ResponseEntity<ApiError> emailNotVerified(EmailNotVerifiedException e,
			HttpServletRequest request) {
		// Actionable by design (unlike FORBIDDEN): the client should
		// offer the resend-verification flow. Raised only after the
		// password verifies, so nothing is probeable.
		return entity(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED",
				e.getMessage(), request);
	}

	@ExceptionHandler(RateLimitExceededException.class)
	public ResponseEntity<ApiError> rateLimited(RateLimitExceededException e,
			HttpServletRequest request) {
		// Fixed message: no key, count, or account signal — back off
		// and retry after the window.
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.body(body(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
						"Too many attempts; please wait and try again", request, List.of()));
	}

	// -- Not found ---------------------------------------------------------

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiError> noResource(NoResourceFoundException e,
			HttpServletRequest request) {
		return entity(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", request);
	}

	@ExceptionHandler(ResourceNotFoundException.class)
	public ResponseEntity<ApiError> resourceNotFound(ResourceNotFoundException e,
			HttpServletRequest request) {
		return entity(HttpStatus.NOT_FOUND, e.getCode(), e.getMessage(), request);
	}

	@ExceptionHandler(QueueEntryNotFoundException.class)
	public ResponseEntity<ApiError> queueEntryNotFound(QueueEntryNotFoundException e,
			HttpServletRequest request) {
		return entity(HttpStatus.NOT_FOUND, "QUEUE_ENTRY_NOT_FOUND", e.getMessage(), request);
	}

	// -- Domain conflicts (409) --------------------------------------------

	@ExceptionHandler(OrganizationTransitionException.class)
	public ResponseEntity<ApiError> organizationTransition(OrganizationTransitionException e,
			HttpServletRequest request) {
		return entity(HttpStatus.CONFLICT, e.getCode(), e.getMessage(), request);
	}

	@ExceptionHandler(AppointmentConflictException.class)
	public ResponseEntity<ApiError> appointmentConflict(AppointmentConflictException e,
			HttpServletRequest request) {
		return entity(HttpStatus.CONFLICT, "APPOINTMENT_CONFLICT", e.getMessage(), request);
	}

	@ExceptionHandler(AppointmentInvalidTransitionException.class)
	public ResponseEntity<ApiError> appointmentTransition(AppointmentInvalidTransitionException e,
			HttpServletRequest request) {
		return entity(HttpStatus.CONFLICT, "APPOINTMENT_INVALID_TRANSITION",
				e.getMessage(), request);
	}

	@ExceptionHandler(AppointmentBranchMismatchException.class)
	public ResponseEntity<ApiError> appointmentMismatch(AppointmentBranchMismatchException e,
			HttpServletRequest request) {
		return entity(HttpStatus.UNPROCESSABLE_ENTITY, "APPOINTMENT_BRANCH_MISMATCH",
				e.getMessage(), request);
	}

	@ExceptionHandler(QueueConflictException.class)
	public ResponseEntity<ApiError> queueConflict(QueueConflictException e,
			HttpServletRequest request) {
		return entity(HttpStatus.CONFLICT, "QUEUE_CONFLICT", e.getMessage(), request);
	}

	@ExceptionHandler(QueueEntryStateException.class)
	public ResponseEntity<ApiError> queueState(QueueEntryStateException e,
			HttpServletRequest request) {
		return entity(HttpStatus.CONFLICT, "INVALID_QUEUE_STATE", e.getMessage(), request);
	}

	@ExceptionHandler(QueueNotActiveException.class)
	public ResponseEntity<ApiError> queueNotActive(QueueNotActiveException e,
			HttpServletRequest request) {
		return entity(HttpStatus.CONFLICT, "QUEUE_NOT_ACTIVE", e.getMessage(), request);
	}

	@ExceptionHandler(QueueNotFoundException.class)
	public ResponseEntity<ApiError> queueNotFound(QueueNotFoundException e,
			HttpServletRequest request) {
		return entity(HttpStatus.NOT_FOUND, "QUEUE_NOT_FOUND", e.getMessage(), request);
	}

	@ExceptionHandler(QueueBranchMismatchException.class)
	public ResponseEntity<ApiError> queueMismatch(QueueBranchMismatchException e,
			HttpServletRequest request) {
		return entity(HttpStatus.UNPROCESSABLE_ENTITY, "QUEUE_BRANCH_MISMATCH",
				e.getMessage(), request);
	}

	@ExceptionHandler(WorkingHourConflictException.class)
	public ResponseEntity<ApiError> workingHourConflict(WorkingHourConflictException e,
			HttpServletRequest request) {
		return entity(HttpStatus.CONFLICT, "WORKING_HOUR_CONFLICT",
				e.getMessage(), request);
	}

	@ExceptionHandler(HolidayConflictException.class)
	public ResponseEntity<ApiError> holidayConflict(HolidayConflictException e,
			HttpServletRequest request) {
		return entity(HttpStatus.CONFLICT, "HOLIDAY_CONFLICT",
				e.getMessage(), request);
	}

	@ExceptionHandler(AnalyticsScopeMismatchException.class)
	public ResponseEntity<ApiError> analyticsMismatch(AnalyticsScopeMismatchException e,
			HttpServletRequest request) {
		return entity(HttpStatus.UNPROCESSABLE_ENTITY, "ANALYTICS_SCOPE_MISMATCH",
				e.getMessage(), request);
	}

	@ExceptionHandler(ServiceSessionNotFoundException.class)
	public ResponseEntity<ApiError> serviceSessionNotFound(ServiceSessionNotFoundException e,
			HttpServletRequest request) {
		return entity(HttpStatus.NOT_FOUND, "SERVICE_SESSION_NOT_FOUND",
				e.getMessage(), request);
	}

	@ExceptionHandler(NoWaitingQueueEntryException.class)
	public ResponseEntity<ApiError> noWaiting(NoWaitingQueueEntryException e,
			HttpServletRequest request) {
		return entity(HttpStatus.CONFLICT, "NO_WAITING_ENTRY", e.getMessage(), request);
	}

	@ExceptionHandler(BranchNameConflictException.class)
	public ResponseEntity<ApiError> branchNameConflict(BranchNameConflictException e,
			HttpServletRequest request) {
		return entity(HttpStatus.CONFLICT, "BRANCH_NAME_CONFLICT", e.getMessage(), request);
	}

	@ExceptionHandler(ServiceNameConflictException.class)
	public ResponseEntity<ApiError> serviceNameConflict(ServiceNameConflictException e,
			HttpServletRequest request) {
		return entity(HttpStatus.CONFLICT, "SERVICE_NAME_CONFLICT", e.getMessage(), request);
	}

	@ExceptionHandler(CounterNameConflictException.class)
	public ResponseEntity<ApiError> counterNameConflict(CounterNameConflictException e,
			HttpServletRequest request) {
		return entity(HttpStatus.CONFLICT, "COUNTER_NAME_CONFLICT", e.getMessage(), request);
	}

	@ExceptionHandler(CapabilityAlreadyExistsException.class)
	public ResponseEntity<ApiError> capabilityExists(CapabilityAlreadyExistsException e,
			HttpServletRequest request) {
		return entity(HttpStatus.CONFLICT, "CAPABILITY_ALREADY_EXISTS",
				e.getMessage(), request);
	}

	@ExceptionHandler(CapabilityNotFoundException.class)
	public ResponseEntity<ApiError> capabilityNotFound(CapabilityNotFoundException e,
			HttpServletRequest request) {
		return entity(HttpStatus.NOT_FOUND, "CAPABILITY_NOT_FOUND", e.getMessage(), request);
	}

	@ExceptionHandler(CapabilityBranchMismatchException.class)
	public ResponseEntity<ApiError> capabilityMismatch(CapabilityBranchMismatchException e,
			HttpServletRequest request) {
		return entity(HttpStatus.UNPROCESSABLE_ENTITY, "CAPABILITY_BRANCH_MISMATCH",
				e.getMessage(), request);
	}

	@ExceptionHandler(UserEmailConflictException.class)
	public ResponseEntity<ApiError> userEmailConflict(UserEmailConflictException e,
			HttpServletRequest request) {
		return entity(HttpStatus.CONFLICT, "USER_EMAIL_CONFLICT", e.getMessage(), request);
	}

	@ExceptionHandler(StaffAssignmentConflictException.class)
	public ResponseEntity<ApiError> assignmentConflict(StaffAssignmentConflictException e,
			HttpServletRequest request) {
		return entity(HttpStatus.CONFLICT, "STAFF_ASSIGNMENT_CONFLICT",
				e.getMessage(), request);
	}

	@ExceptionHandler(StaffAssignmentNotFoundException.class)
	public ResponseEntity<ApiError> assignmentNotFound(StaffAssignmentNotFoundException e,
			HttpServletRequest request) {
		return entity(HttpStatus.NOT_FOUND, "STAFF_ASSIGNMENT_NOT_FOUND",
				e.getMessage(), request);
	}

	@ExceptionHandler(StaffAssignmentBranchMismatchException.class)
	public ResponseEntity<ApiError> assignmentMismatch(StaffAssignmentBranchMismatchException e,
			HttpServletRequest request) {
		return entity(HttpStatus.UNPROCESSABLE_ENTITY, "STAFF_ASSIGNMENT_BRANCH_MISMATCH",
				e.getMessage(), request);
	}

	// -- Domain request errors (400) ----------------------------------------

	@ExceptionHandler(InvalidAppointmentRequestException.class)
	public ResponseEntity<ApiError> invalidAppointment(InvalidAppointmentRequestException e,
			HttpServletRequest request) {
		return entity(HttpStatus.BAD_REQUEST, "INVALID_APPOINTMENT_REQUEST",
				e.getMessage(), request);
	}

	@ExceptionHandler(InvalidQueueRequestException.class)
	public ResponseEntity<ApiError> invalidQueue(InvalidQueueRequestException e,
			HttpServletRequest request) {
		return entity(HttpStatus.BAD_REQUEST, "INVALID_QUEUE_REQUEST", e.getMessage(), request);
	}

	@ExceptionHandler(OutsideWorkingHoursException.class)
	public ResponseEntity<ApiError> outsideHours(OutsideWorkingHoursException e,
			HttpServletRequest request) {
		return entity(HttpStatus.BAD_REQUEST, "OUTSIDE_WORKING_HOURS", e.getMessage(), request);
	}

	@ExceptionHandler(BranchClosedException.class)
	public ResponseEntity<ApiError> branchClosed(BranchClosedException e,
			HttpServletRequest request) {
		return entity(HttpStatus.BAD_REQUEST, "BRANCH_CLOSED", e.getMessage(), request);
	}

	@ExceptionHandler(CounterCannotServeQueueException.class)
	public ResponseEntity<ApiError> cannotServe(CounterCannotServeQueueException e,
			HttpServletRequest request) {
		return entity(HttpStatus.BAD_REQUEST, "COUNTER_CANNOT_SERVE_QUEUE",
				e.getMessage(), request);
	}

	// -- Server faults (500, generic body) ----------------------------------

	@ExceptionHandler(TenantContextRequiredException.class)
	public ResponseEntity<ApiError> missingScope(TenantContextRequiredException e,
			HttpServletRequest request) {
		// A missing tenant context on an authenticated request is a server
		// wiring fault, never a client error: generic body, logged detail.
		log.error("Tenant scope missing for {} {}", request.getMethod(), request.getRequestURI());
		return entity(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
				INTERNAL_MESSAGE, request);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
		log.error("Unexpected failure for {} {}", request.getMethod(),
				request.getRequestURI(), e);
		return entity(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
				INTERNAL_MESSAGE, request);
	}
}
