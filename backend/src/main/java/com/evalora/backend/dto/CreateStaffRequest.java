package com.evalora.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * HR-only account creation payload, shared by employees and managers.
 *
 * <p>The role is never taken from the request: the endpoint decides whether the
 * new account is EMPLOYEE or MANAGER. {@code managerId} is accepted only for
 * employees; a manager must not have a manager in v1.</p>
 */
public record CreateStaffRequest(
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

        LocalDate joiningDate,

        Long managerId) {

    /**
     * Trims the email before Bean Validation runs, so a pasted value with
     * surrounding spaces is validated and stored in its normalised form rather
     * than rejected by {@code @Email}.
     */
    public CreateStaffRequest {
        email = email == null ? null : email.trim();
    }
}
