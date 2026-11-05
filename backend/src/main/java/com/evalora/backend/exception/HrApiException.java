package com.evalora.backend.exception;

/**
 * Domain failure raised by an API operation. Carries no stack trace or
 * database detail to the client - only a classified, safe message.
 *
 * <p>Despite the historical name, this is the single classified failure type
 * shared by the HR module and the employee self-assessment module; each
 * controller maps it to its HTTP status.</p>
 */
public class HrApiException extends RuntimeException {

    private final HrErrorType type;

    public HrApiException(HrErrorType type, String message) {
        super(message);
        this.type = type;
    }

    public HrErrorType getType() {
        return type;
    }
}
