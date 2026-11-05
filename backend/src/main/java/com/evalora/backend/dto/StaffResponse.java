package com.evalora.backend.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Safe HR view of a staff profile and its login account.
 *
 * <p>Contains no password hash, reset token, JWT, lockout counter or any other
 * security state. The role and status are the enum names ({@code EMPLOYEE},
 * {@code MANAGER}, {@code ACTIVE}, {@code INACTIVE}).</p>
 */
public record StaffResponse(
        Long id,
        Long userId,
        String employeeCode,
        String fullName,
        String email,
        String role,
        String department,
        String designation,
        LocalDate joiningDate,
        String status,
        boolean accountEnabled,
        Long managerId,
        String managerName,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
