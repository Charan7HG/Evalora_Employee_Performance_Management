package com.evalora.backend.support;

import com.evalora.backend.dto.CycleRequest;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.KpiMaster;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;

import java.time.LocalDate;

/**
 * Small builders shared by the Phase 3D integration tests. They only create
 * transient objects; each test persists them through its own repositories.
 */
public final class CycleTestData {

    public static final String PASSWORD_HASH =
            "$2a$12$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOP";

    private CycleTestData() {
    }

    public static User user(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(PASSWORD_HASH);
        user.setRole(role);
        user.setEnabled(true);
        return user;
    }

    public static Employee profile(User user, String employeeCode, String fullName) {
        Employee employee = new Employee();
        employee.setUser(user);
        employee.setEmployeeCode(employeeCode);
        employee.setFullName(fullName);
        employee.setDepartment("Engineering");
        employee.setDesignation("Engineer");
        employee.setJoiningDate(LocalDate.of(2024, 1, 15));
        employee.setStatus(StaffStatus.ACTIVE);
        return employee;
    }

    public static CycleRequest request(String name) {
        return new CycleRequest(
                name,
                "Annual performance review.",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                LocalDate.of(2026, 11, 30),
                LocalDate.of(2026, 12, 15),
                LocalDate.of(2026, 12, 31));
    }

    public static CycleRequest request(String name,
                                       LocalDate startDate,
                                       LocalDate endDate,
                                       LocalDate selfDeadline,
                                       LocalDate managerDeadline,
                                       LocalDate hrDeadline) {
        return new CycleRequest(name, "Annual performance review.", startDate, endDate,
                selfDeadline, managerDeadline, hrDeadline);
    }

    public static KpiMaster kpi(String name, String description, String category) {
        KpiMaster kpi = new KpiMaster();
        kpi.setName(name);
        kpi.setDescription(description);
        kpi.setCategory(category);
        kpi.setActive(true);
        return kpi;
    }
}
