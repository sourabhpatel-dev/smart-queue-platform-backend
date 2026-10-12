package com.sourabh.smartqueue.auth.api;

import java.util.UUID;

/**
 * What invitation acceptance established: identifiers only. The
 * employee signs in explicitly next — acceptance never returns a
 * session.
 */
public record InvitationAcceptanceResponse(UUID userId, UUID organizationId) {
}
