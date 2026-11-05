package com.evalora.backend.repository;

import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mapping and constraint tests for the employees table, including the
 * self-referencing manager relationship.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EmployeeRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    private User newUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("$2a$12$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOP");
        user.setRole(role);
        return userRepository.saveAndFlush(user);
    }

    private Employee newEmployee(User user, String code, String name, Employee manager) {
        Employee employee = new Employee();
        employee.setUser(user);
        employee.setEmployeeCode(code);
        employee.setFullName(name);
        employee.setManager(manager);
        return employee;
    }

    @Test
    void savesManagerAndEmployeeWithRelationship() {
        Employee manager = employeeRepository.saveAndFlush(
                newEmployee(newUser("manager@evalora.local", Role.MANAGER), "MGR-001", "Maya Manager", null));

        Employee employee = employeeRepository.saveAndFlush(
                newEmployee(newUser("employee@evalora.local", Role.EMPLOYEE), "EMP-001", "Evan Employee", manager));

        assertThat(employee.getStatus()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(employeeRepository.findByManager_Id(manager.getId()))
                .extracting(Employee::getEmployeeCode)
                .containsExactly("EMP-001");

        Employee reloaded = employeeRepository.findByEmployeeCodeIgnoreCase("emp-001").orElseThrow();
        assertThat(reloaded.getManager().getId()).isEqualTo(manager.getId());
    }

    @Test
    void oneProfilePerUser() {
        User user = newUser("employee@evalora.local", Role.EMPLOYEE);
        employeeRepository.saveAndFlush(newEmployee(user, "EMP-001", "Evan Employee", null));

        assertThatThrownBy(() -> employeeRepository.saveAndFlush(newEmployee(user, "EMP-002", "Evan Again", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void employeeCodeMustBeUnique() {
        employeeRepository.saveAndFlush(
                newEmployee(newUser("employee@evalora.local", Role.EMPLOYEE), "EMP-001", "Evan Employee", null));

        assertThatThrownBy(() -> employeeRepository.saveAndFlush(
                newEmployee(newUser("other@evalora.local", Role.EMPLOYEE), "EMP-001", "Other Employee", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void employeeCannotBeTheirOwnManager() {
        Employee employee = employeeRepository.saveAndFlush(
                newEmployee(newUser("employee@evalora.local", Role.EMPLOYEE), "EMP-001", "Evan Employee", null));

        employee.setManager(employee);

        assertThatThrownBy(() -> employeeRepository.saveAndFlush(employee))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
