package com.evalora.backend.exception;

/**
 * Safe, classified failure from a staff-management operation. The controller
 * maps each type to an HTTP status; the message is always safe to return.
 */
public enum HrErrorType {
    NOT_FOUND,
    CONFLICT,
    INVALID_REQUEST
}
