package com.evalora.backend.service;

public record VerifyOtpResult(boolean valid, String ticket, String message) {

    private static final String INVALID_OTP_MESSAGE = "Invalid or expired OTP.";
    private static final String VERIFIED_OTP_MESSAGE =
            "OTP verified. Please set your new password.";

    public static VerifyOtpResult invalid() {
        return new VerifyOtpResult(false, null, INVALID_OTP_MESSAGE);
    }

    public static VerifyOtpResult valid(String ticket) {
        return new VerifyOtpResult(true, ticket, VERIFIED_OTP_MESSAGE);
    }
}
