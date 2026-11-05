package com.evalora.backend.service;

import com.evalora.backend.dto.CreateStaffRequest;
import com.evalora.backend.dto.StaffResponse;
import com.evalora.backend.dto.UpdateStaffRequest;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.EmployeeManagerAssignment;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.exception.HrErrorType;
import com.evalora.backend.repository.EmployeeManagerAssignmentRepository;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.util.EmailNormalizer;
import com.evalora.backend.util.PasswordPolicy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * HR-side management of staff accounts: create, read, update, manager
 * assignment and activation state.
 *
 * <p>Only HR calls this service (enforced by {@code SecurityConfig}). The role
 * of a new account is chosen by the operation, never by the request body, so an
 * HR caller cannot accidentally create a manager through the employee endpoint
 * or vice versa.</p>
 *
 * <p>Reporting-line history lives in {@link EmployeeManagerAssignment}: a
 * reassignment closes the current row and appends a new one, while
 * {@code employees.manager_id} always points at the current manager.</p>
 */
@Service
public class EmployeeManagementService {

    private static final int MAX_FULL_NAME_LENGTH = 150;

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerAssignmentRepository assignmentRepository;
    private final PasswordEncoder passwordEncoder;

    public EmployeeManagementService(UserRepository userRepository,
                                     EmployeeRepository employeeRepository,
                                     EmployeeManagerAssignmentRepository assignmentRepository,
                                     PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public StaffResponse createEmployee(CreateStaffRequest request, Long hrUserId) {
        return create(request, Role.EMPLOYEE, hrUserId);
    }

    @Transactional
    public StaffResponse createManager(CreateStaffRequest request, Long hrUserId) {
        if (request.managerId() != null) {
            throw invalid("Managers cannot be assigned a manager in v1.");
        }
        return create(request, Role.MANAGER, hrUserId);
    }

    private StaffResponse create(CreateStaffRequest request, Role role, Long hrUserId) {
        String email = EmailNormalizer.normalize(request.email());
        String employeeCode = request.employeeCode().trim();

        if (!PasswordPolicy.isValid(request.initialPassword())) {
            throw invalid(PasswordPolicy.MESSAGE);
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw conflict("An account with this email already exists.");
        }
        if (employeeRepository.existsByEmployeeCodeIgnoreCase(employeeCode)) {
            throw conflict("An employee with this code already exists.");
        }

        Employee manager = null;
        if (role == Role.EMPLOYEE) {
            if (request.managerId() == null) {
                throw invalid("A reporting manager is required for employees.");
            }
            // Take the same row lock as manager deactivation and reassignment.
            // Without it, a concurrent deactivation could commit between this
            // validation and the employee insert, leaving a newly created
            // employee reporting to an inactive manager. The lock is held until
            // this transaction commits, and deactivation re-counts reports only
            // after it has acquired the same lock.
            manager = lockActiveManager(request.managerId());
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.initialPassword()));
        user.setRole(role);
        user.setEnabled(true);
        user = saveUser(user);

        Employee employee = new Employee();
        employee.setUser(user);
        employee.setEmployeeCode(employeeCode);
        employee.setFullName(fullName(request.firstName(), request.lastName()));
        employee.setDepartment(trimToNull(request.department()));
        employee.setDesignation(trimToNull(request.designation()));
        employee.setJoiningDate(request.joiningDate());
        employee.setStatus(StaffStatus.ACTIVE);
        employee.setManager(manager);
        employee = saveEmployee(employee);

        if (manager != null) {
            openAssignment(employee, manager, hrUserId, now());
        }
        return toResponse(employee);
    }

    // -------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public List<StaffResponse> listEmployees(StaffStatus status) {
        return list(Role.EMPLOYEE, status);
    }

    @Transactional(readOnly = true)
    public List<StaffResponse> listManagers(StaffStatus status) {
        return list(Role.MANAGER, status);
    }

    @Transactional(readOnly = true)
    public StaffResponse getEmployee(Long employeeId) {
        return toResponse(requireProfile(employeeId, Role.EMPLOYEE));
    }

    @Transactional(readOnly = true)
    public StaffResponse getManager(Long managerId) {
        return toResponse(requireProfile(managerId, Role.MANAGER));
    }

    private List<StaffResponse> list(Role role, StaffStatus status) {
        return employeeRepository.findByUser_RoleAndStatusOrderByFullNameAsc(role, status)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ------------------------------------------------------------------ update

    @Transactional
    public StaffResponse updateEmployee(Long employeeId, UpdateStaffRequest request) {
        return update(employeeId, Role.EMPLOYEE, request);
    }

    @Transactional
    public StaffResponse updateManager(Long managerId, UpdateStaffRequest request) {
        return update(managerId, Role.MANAGER, request);
    }

    private StaffResponse update(Long profileId, Role role, UpdateStaffRequest request) {
        Employee employee = lockProfile(profileId, role);

        String email = EmailNormalizer.normalize(request.email());
        String employeeCode = request.employeeCode().trim();
        User user = employee.getUser();

        if (!email.equals(user.getEmail()) && userRepository.existsByEmailIgnoreCase(email)) {
            throw conflict("An account with this email already exists.");
        }
        if (!employeeCode.equalsIgnoreCase(employee.getEmployeeCode())
                && employeeRepository.existsByEmployeeCodeIgnoreCase(employeeCode)) {
            throw conflict("An employee with this code already exists.");
        }

        user.setEmail(email);
        employee.setEmployeeCode(employeeCode);
        employee.setFullName(fullName(request.firstName(), request.lastName()));
        employee.setDepartment(trimToNull(request.department()));
        employee.setDesignation(trimToNull(request.designation()));
        employee.setJoiningDate(request.joiningDate());

        saveUser(user);
        return toResponse(employee);
    }

    // -------------------------------------------------------------- assignment

    /**
     * Assigns (or reassigns) an employee's reporting manager. The previous
     * current assignment is closed, never deleted, and exactly one open row
     * remains.
     */
    @Transactional
    public StaffResponse assignManager(Long employeeId, Long managerId, Long hrUserId) {
        if (Objects.equals(employeeId, managerId)) {
            throw invalid("An employee cannot be their own manager.");
        }

        Employee employee = lockProfile(employeeId, Role.EMPLOYEE);
        if (employee.getStatus() != StaffStatus.ACTIVE) {
            throw conflict("Only an active employee can be assigned a manager.");
        }

        Employee manager = lockActiveManager(managerId);
        OffsetDateTime now = now();

        closeCurrentAssignments(employee.getId(), now);
        openAssignment(employee, manager, hrUserId, now);

        employee.setManager(manager);
        return toResponse(employee);
    }

    // ------------------------------------------------------- activate/deactivate

    @Transactional
    public StaffResponse deactivateEmployee(Long employeeId) {
        Employee employee = lockProfile(employeeId, Role.EMPLOYEE);
        if (employee.getStatus() == StaffStatus.INACTIVE) {
            throw conflict("Employee is already inactive.");
        }

        employee.setStatus(StaffStatus.INACTIVE);
        employee.getUser().setEnabled(false);
        closeCurrentAssignments(employee.getId(), now());
        employee.setManager(null);
        return toResponse(employee);
    }

    @Transactional
    public StaffResponse reactivateEmployee(Long employeeId) {
        Employee employee = lockProfile(employeeId, Role.EMPLOYEE);
        if (employee.getStatus() == StaffStatus.ACTIVE) {
            throw conflict("Employee is already active.");
        }

        employee.setStatus(StaffStatus.ACTIVE);
        employee.getUser().setEnabled(true);
        // A manager must be assigned again explicitly; history is not replayed.
        return toResponse(employee);
    }

    @Transactional
    public StaffResponse deactivateManager(Long managerId) {
        Employee manager = lockProfile(managerId, Role.MANAGER);
        if (manager.getStatus() == StaffStatus.INACTIVE) {
            throw conflict("Manager is already inactive.");
        }

        long activeReports = employeeRepository.countByManager_IdAndStatus(managerId, StaffStatus.ACTIVE);
        if (activeReports > 0) {
            throw conflict("This manager still has " + activeReports
                    + " active employee(s). Reassign them before deactivating the manager.");
        }

        OffsetDateTime now = now();
        // Only inactive reports can still point here; clear them defensively.
        for (Employee report : employeeRepository.findByManager_Id(managerId)) {
            if (report.getStatus() == StaffStatus.INACTIVE) {
                report.setManager(null);
            }
        }
        for (EmployeeManagerAssignment open : assignmentRepository.findByManager_IdAndEffectiveToIsNull(managerId)) {
            open.setEffectiveTo(now);
        }

        manager.setStatus(StaffStatus.INACTIVE);
        manager.getUser().setEnabled(false);
        return toResponse(manager);
    }

    @Transactional
    public StaffResponse reactivateManager(Long managerId) {
        Employee manager = lockProfile(managerId, Role.MANAGER);
        if (manager.getStatus() == StaffStatus.ACTIVE) {
            throw conflict("Manager is already active.");
        }

        manager.setStatus(StaffStatus.ACTIVE);
        manager.getUser().setEnabled(true);
        // Old employee assignments are not recreated.
        return toResponse(manager);
    }

    // ----------------------------------------------------------------- helpers

    private Employee requireProfile(Long profileId, Role role) {
        return employeeRepository.findByIdAndUser_Role(profileId, role)
                .orElseThrow(() -> notFound(role));
    }

    private Employee lockProfile(Long profileId, Role role) {
        Employee employee = employeeRepository.findByIdForUpdate(profileId)
                .orElseThrow(() -> notFound(role));
        if (employee.getUser().getRole() != role) {
            // Same 404 as "missing": the caller cannot probe other roles by id.
            throw notFound(role);
        }
        return employee;
    }

    /**
     * Locks the manager row for the rest of the transaction before checking its
     * role and state. Used by both assignment and employee creation so the two
     * paths and manager deactivation all serialise on the same row.
     */
    private Employee lockActiveManager(Long managerId) {
        Employee manager = employeeRepository.findByIdForUpdate(managerId)
                .orElseThrow(() -> notFound(Role.MANAGER));
        assertManagerRoleAndState(manager);
        return manager;
    }

    private void assertManagerRoleAndState(Employee manager) {
        if (manager.getUser().getRole() != Role.MANAGER) {
            throw invalid("The selected account is not a manager.");
        }
        if (manager.getStatus() != StaffStatus.ACTIVE || !manager.getUser().isEnabled()) {
            throw conflict("The selected manager is inactive.");
        }
    }

    private void closeCurrentAssignments(Long employeeId, OffsetDateTime at) {
        List<EmployeeManagerAssignment> open =
                assignmentRepository.findByEmployee_IdAndEffectiveToIsNull(employeeId);
        if (open.isEmpty()) {
            return;
        }
        open.forEach(assignment -> assignment.setEffectiveTo(at));
        // Inserts are flushed before updates by Hibernate. Flush the closing
        // update first, otherwise PostgreSQL's partial unique index would see
        // two open rows for this employee when the new row is inserted.
        assignmentRepository.flush();
    }

    private void openAssignment(Employee employee, Employee manager, Long hrUserId, OffsetDateTime from) {
        EmployeeManagerAssignment assignment = new EmployeeManagerAssignment();
        assignment.setEmployee(employee);
        assignment.setManager(manager);
        assignment.setAssignedBy(userRepository.getReferenceById(hrUserId));
        assignment.setEffectiveFrom(from);
        assignmentRepository.save(assignment);
    }

    private User saveUser(User user) {
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw conflict("An account with this email already exists.");
        }
    }

    private Employee saveEmployee(Employee employee) {
        try {
            return employeeRepository.saveAndFlush(employee);
        } catch (DataIntegrityViolationException ex) {
            throw conflict("An employee with this code already exists.");
        }
    }

    private StaffResponse toResponse(Employee employee) {
        Employee manager = employee.getManager();
        User user = employee.getUser();
        return new StaffResponse(
                employee.getId(),
                user.getId(),
                employee.getEmployeeCode(),
                employee.getFullName(),
                user.getEmail(),
                user.getRole().name(),
                employee.getDepartment(),
                employee.getDesignation(),
                employee.getJoiningDate(),
                employee.getStatus().name(),
                user.isEnabled(),
                manager == null ? null : manager.getId(),
                manager == null ? null : manager.getFullName(),
                employee.getCreatedAt(),
                employee.getUpdatedAt());
    }

    private static String fullName(String firstName, String lastName) {
        String fullName = firstName.trim() + " " + lastName.trim();
        if (fullName.length() > MAX_FULL_NAME_LENGTH) {
            throw invalid("Full name must be at most " + MAX_FULL_NAME_LENGTH + " characters.");
        }
        return fullName;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static HrApiException notFound(Role role) {
        String label = role == Role.MANAGER ? "Manager" : "Employee";
        return new HrApiException(HrErrorType.NOT_FOUND, label + " not found.");
    }

    private static HrApiException conflict(String message) {
        return new HrApiException(HrErrorType.CONFLICT, message);
    }

    private static HrApiException invalid(String message) {
        return new HrApiException(HrErrorType.INVALID_REQUEST, message);
    }
}
