package com.evalora.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyOtpRequest(
        @NotBlank(message = "Email is required.")
        @Email(message = "A valid email address is required.")
        String email,

        @NotBlank(message = "OTP is required.")
        @Pattern(regexp = "\\d{6}", message = "OTP must contain 6 digits.")
        String otp) {
}
