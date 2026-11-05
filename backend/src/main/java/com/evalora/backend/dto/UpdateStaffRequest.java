package com.evalora.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * HR profile update payload (PUT = full profile replacement).
 *
 * <p>Role, password, account status and manager are deliberately absent: those
 * are changed only through their dedicated operations.</p>
 */
public record UpdateStaffRequest(
        @NotBlank(message = "First name is required.")
        @Size(max = 75, message = "First name must be at most 75 characters.")
        String firstName,

        @NotBlank(message = "Last name is required.")
        @Size(max = 75, message = "Last name must be at most 75 characters.")
        String lastName,

        @NotBlank(message = "Email is required.")
        @Email(message = "A valid email address is required.")
        @Size(max = 255, message = "Email must be at most 255 characters.")
        String email,

        @NotBlank(message = "Employee code is required.")
        @Size(max = 30, message = "Employee code must be at most 30 characters.")
        String employeeCode,

        @Size(max = 100, message = "Department must be at most 100 characters.")
        String department,

        @Size(max = 100, message = "Designation must be at most 100 characters.")
        String designation,

        LocalDate joiningDate) {

    /** Trims the email before Bean Validation, for consistency with creation. */
    public UpdateStaffRequest {
        email = email == null ? null : email.trim();
    }
}
