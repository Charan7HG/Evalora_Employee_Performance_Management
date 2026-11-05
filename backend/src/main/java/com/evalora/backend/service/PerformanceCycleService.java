package com.evalora.backend.service;

import com.evalora.backend.dto.CycleEmployeeResponse;
import com.evalora.backend.dto.CycleEmployeeSelectionRequest;
import com.evalora.backend.dto.CycleKpiResponse;
import com.evalora.backend.dto.CycleKpiSelectionRequest;
import com.evalora.backend.dto.CycleRequest;
import com.evalora.backend.dto.CycleResponse;
import com.evalora.backend.dto.ConfigureCycleEmployeesRequest;
import com.evalora.backend.dto.ConfigureCycleKpisRequest;
import com.evalora.backend.entity.CycleKpi;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.EmployeeAssignment;
import com.evalora.backend.entity.EmployeeKpi;
import com.evalora.backend.entity.enums.KpiType;
import com.evalora.backend.entity.EmployeeManagerAssignment;
import com.evalora.backend.entity.KpiMaster;
import com.evalora.backend.entity.PerformanceCycle;
import com.evalora.backend.entity.enums.AssignmentStatus;
import com.evalora.backend.entity.enums.CycleStatus;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.exception.HrErrorType;
import com.evalora.backend.repository.CycleKpiRepository;
import com.evalora.backend.repository.EmployeeAssignmentRepository;
import com.evalora.backend.repository.EmployeeKpiRepository;
import com.evalora.backend.repository.EmployeeManagerAssignmentRepository;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.KpiMasterRepository;
import com.evalora.backend.repository.PerformanceCycleRepository;
import com.evalora.backend.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * HR-side management of performance cycles: the cycle itself, its KPI
 * selection and weights, its employee roster, and publication.
 *
 * <p>Only a {@code DRAFT} cycle can be changed. The roster of a draft cycle is
 * stored directly in {@code employee_assignments}, because that table already
 * has exactly the shape of "this employee, this cycle, this reviewing manager,
 * this workflow status". Publishing validates the draft roster, freezes the
 * cycle KPIs into {@code employee_kpis}, and flips the cycle to
 * {@code PUBLISHED} in the same transaction. That is why the database needed no
 * new table for Phase 3D.</p>
 *
 * <p>Every mutation locks the cycle row first. Staff rows are locked in
 * ascending id order, and always employee before manager, matching
 * {@link EmployeeManagementService}, so cycle configuration serialises with
 * reassignment and deactivation instead of racing them.</p>
 */
@Service
public class PerformanceCycleService {

    private static final int MAX_NAME_LENGTH = 150;
    private static final BigDecimal MAX_WEIGHTAGE = new BigDecimal("100");
    private static final BigDecimal REQUIRED_TOTAL = new BigDecimal("100");

    private final PerformanceCycleRepository cycleRepository;
    private final CycleKpiRepository cycleKpiRepository;
    private final EmployeeAssignmentRepository assignmentRepository;
    private final EmployeeKpiRepository employeeKpiRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerAssignmentRepository managerAssignmentRepository;
    private final KpiMasterRepository kpiRepository;
    private final UserRepository userRepository;

    public PerformanceCycleService(PerformanceCycleRepository cycleRepository,
                                   CycleKpiRepository cycleKpiRepository,
                                   EmployeeAssignmentRepository assignmentRepository,
                                   EmployeeKpiRepository employeeKpiRepository,
                                   EmployeeRepository employeeRepository,
                                   EmployeeManagerAssignmentRepository managerAssignmentRepository,
                                   KpiMasterRepository kpiRepository,
                                   UserRepository userRepository) {
        this.cycleRepository = cycleRepository;
        this.cycleKpiRepository = cycleKpiRepository;
        this.assignmentRepository = assignmentRepository;
        this.employeeKpiRepository = employeeKpiRepository;
        this.employeeRepository = employeeRepository;
        this.managerAssignmentRepository = managerAssignmentRepository;
        this.kpiRepository = kpiRepository;
        this.userRepository = userRepository;
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public CycleResponse create(CycleRequest request, Long hrUserId) {
        String name = normalizeName(request.name());
        validateDates(request);

        if (cycleRepository.existsByNameIgnoreCase(name)) {
            throw conflict("A cycle with this name already exists.");
        }

        PerformanceCycle cycle = new PerformanceCycle();
        cycle.setName(name);
        cycle.setDescription(normalizeOptional(request.description()));
        cycle.setStartDate(request.startDate());
        cycle.setEndDate(request.endDate());
        cycle.setSelfAssessmentDeadline(request.selfAssessmentDeadline());
        cycle.setManagerReviewDeadline(request.managerReviewDeadline());
        cycle.setHrReviewDeadline(request.hrReviewDeadline());
        cycle.setStatus(CycleStatus.DRAFT);
        cycle.setCreatedBy(userRepository.getReferenceById(hrUserId));

        return toResponse(saveCycle(cycle));
    }

    // -------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public List<CycleResponse> list() {
        return cycleRepository.findAllByOrderByIdDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CycleResponse get(Long cycleId) {
        return toResponse(requireCycle(cycleId));
    }

    @Transactional(readOnly = true)
    public List<CycleKpiResponse> getKpis(Long cycleId) {
        requireCycle(cycleId);
        return cycleKpiRepository.findByCycle_IdOrderByIdAsc(cycleId)
                .stream()
                .map(this::toKpiResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CycleEmployeeResponse> getEmployees(Long cycleId) {
        requireCycle(cycleId);
        return assignmentRepository.findByCycle_IdOrderByEmployee_IdAsc(cycleId)
                .stream()
                .map(this::toEmployeeResponse)
                .toList();
    }

    // ------------------------------------------------------------------ update

    @Transactional
    public CycleResponse update(Long cycleId, CycleRequest request) {
        PerformanceCycle cycle = lockDraftCycle(cycleId, "Only a DRAFT cycle can be updated.");

        String name = normalizeName(request.name());
        validateDates(request);

        if (cycleRepository.existsByNameIgnoreCaseAndIdNot(name, cycleId)) {
            throw conflict("A cycle with this name already exists.");
        }

        cycle.setName(name);
        cycle.setDescription(normalizeOptional(request.description()));
        cycle.setStartDate(request.startDate());
        cycle.setEndDate(request.endDate());
        cycle.setSelfAssessmentDeadline(request.selfAssessmentDeadline());
        cycle.setManagerReviewDeadline(request.managerReviewDeadline());
        cycle.setHrReviewDeadline(request.hrReviewDeadline());

        return toResponse(saveCycle(cycle));
    }

    // -------------------------------------------------------------- KPI roster

    /**
     * Replaces the whole KPI selection of a DRAFT cycle. The complete request
     * is validated before anything is deleted, so an invalid selection leaves
     * the previous configuration untouched.
     */
    @Transactional
    public List<CycleKpiResponse> configureKpis(Long cycleId, ConfigureCycleKpisRequest request) {
        lockDraftCycle(cycleId, "Only a DRAFT cycle can be modified.");

        List<CycleKpiSelectionRequest> selections =
                request.kpis() == null ? List.of() : request.kpis();

        Set<Long> kpiIds = new HashSet<>();
        for (CycleKpiSelectionRequest selection : selections) {
            if (selection.kpiId() == null) {
                throw invalid("KPI id is required.");
            }
            if (!kpiIds.add(selection.kpiId())) {
                throw invalid("The same KPI cannot be selected twice in one cycle.");
            }
        }

        Map<Long, KpiMaster> masters = new HashMap<>();
        for (KpiMaster kpi : kpiRepository.findAllById(kpiIds)) {
            masters.put(kpi.getId(), kpi);
        }

        List<SelectedKpi> selected = new ArrayList<>(selections.size());
        for (CycleKpiSelectionRequest selection : selections) {
            KpiMaster kpi = masters.get(selection.kpiId());
            if (kpi == null) {
                throw notFound("KPI not found.");
            }
            if (!kpi.isActive()) {
                throw invalid("Only an active KPI can be selected.");
            }
            selected.add(new SelectedKpi(kpi, normalizeWeightage(selection.weightage())));
        }

        validateWeightage(selected);

        List<CycleKpi> existing = cycleKpiRepository.findByCycle_IdOrderByIdAsc(cycleId);
        cycleKpiRepository.deleteAll(existing);
        // Hibernate flushes inserts before deletes. Flushing the replacement
        // boundary first avoids tripping uq_cycle_kpi when the new selection
        // contains the same KPI ids as the old one.
        cycleKpiRepository.flush();

        List<CycleKpi> replacements = new ArrayList<>(selected.size());
        for (SelectedKpi choice : selected) {
            CycleKpi cycleKpi = new CycleKpi();
            cycleKpi.setCycle(cycleRepository.getReferenceById(cycleId));
            cycleKpi.setKpi(choice.kpi());
            cycleKpi.setWeightage(choice.weightage());
            replacements.add(cycleKpi);
        }
        cycleKpiRepository.saveAll(replacements);
        cycleKpiRepository.flush();

        return replacements.stream().map(this::toKpiResponse).toList();
    }

    // --------------------------------------------------------- employee roster

    /**
     * Replaces the whole employee roster of a DRAFT cycle. Explicit
     * {@code managerId} values select the reviewing manager for this cycle; they
     * never rewrite the employee's reporting-line history. Omitting the manager
     * uses the employee's current reporting manager.
     */
    @Transactional
    public List<CycleEmployeeResponse> configureEmployees(Long cycleId,
                                                          ConfigureCycleEmployeesRequest request) {
        PerformanceCycle cycle = lockDraftCycle(cycleId, "Only a DRAFT cycle can be modified.");

        List<CycleEmployeeSelectionRequest> selections =
                request.employees() == null ? List.of() : request.employees();

        Set<Long> employeeIds = new HashSet<>();
        for (CycleEmployeeSelectionRequest selection : selections) {
            if (selection.employeeId() == null) {
                throw invalid("Employee id is required.");
            }
            if (!employeeIds.add(selection.employeeId())) {
                throw invalid("The same employee cannot be assigned twice in one cycle.");
            }
        }

        // Lock employees in ascending id order. The same order is used by
        // EmployeeManagementService, so reassignment/deactivation and cycle
        // configuration queue on the same row instead of interleaving.
        Map<Long, Employee> lockedStaff = new HashMap<>();
        for (Long employeeId : new TreeSet<>(employeeIds)) {
            Employee employee = employeeRepository.findByIdForUpdate(employeeId).orElse(null);
            if (employee == null) {
                throw notFound("Employee not found.");
            }
            lockedStaff.put(employeeId, employee);
        }

        // Resolve the reviewing manager of every selection while the employee
        // row is locked, then lock managers in ascending id order.
        Map<Long, Long> selectedManagerIds = new LinkedHashMap<>();
        Set<Long> managerIdsToLock = new TreeSet<>();
        for (CycleEmployeeSelectionRequest selection : selections) {
            Employee employee = lockedStaff.get(selection.employeeId());
            Long managerId = selection.managerId() != null
                    ? selection.managerId()
                    : currentManagerId(employee);
            if (managerId == null) {
                throw invalid("A reviewing manager is required for every assigned employee.");
            }
            selectedManagerIds.put(selection.employeeId(), managerId);
            if (!lockedStaff.containsKey(managerId)) {
                managerIdsToLock.add(managerId);
            }
        }
        for (Long managerId : managerIdsToLock) {
            Employee manager = employeeRepository.findByIdForUpdate(managerId).orElse(null);
            if (manager == null) {
                throw notFound("Manager not found.");
            }
            lockedStaff.put(managerId, manager);
        }

        List<SelectedEmployee> selected = new ArrayList<>(selections.size());
        for (CycleEmployeeSelectionRequest selection : selections) {
            Employee employee = lockedStaff.get(selection.employeeId());
            Employee manager = lockedStaff.get(selectedManagerIds.get(selection.employeeId()));

            if (Objects.equals(employee.getId(), manager.getId())) {
                throw invalid("An employee cannot be their own manager.");
            }
            assertAppraisee(employee);
            assertReviewer(manager);

            selected.add(new SelectedEmployee(employee, manager));
        }

        List<EmployeeAssignment> existing =
                assignmentRepository.findByCycle_IdOrderByEmployee_IdAsc(cycleId);
        assignmentRepository.deleteAll(existing);
        assignmentRepository.flush();

        List<EmployeeAssignment> replacements = new ArrayList<>(selected.size());
        for (SelectedEmployee choice : selected) {
            EmployeeAssignment assignment = new EmployeeAssignment();
            assignment.setCycle(cycle);
            assignment.setEmployee(choice.employee());
            assignment.setManager(choice.manager());
            assignment.setStatus(AssignmentStatus.SELF_ASSESSMENT_PENDING);
            replacements.add(assignment);
        }
        assignmentRepository.saveAll(replacements);
        assignmentRepository.flush();
        replacements.sort(Comparator.comparing(assignment -> assignment.getEmployee().getId()));

        return replacements.stream().map(this::toEmployeeResponse).toList();
    }

    // ----------------------------------------------------------------- publish

    /**
     * Publishes a DRAFT cycle in one transaction: validates the frozen
     * configuration, creates the KPI snapshot of every assignment and flips the
     * cycle to PUBLISHED. Any failure rolls the whole operation back.
     */
    @Transactional
    public CycleResponse publish(Long cycleId) {
        PerformanceCycle cycle = lockDraftCycle(cycleId, "Only a DRAFT cycle can be published.");

        List<CycleKpi> cycleKpis = cycleKpiRepository.findByCycle_IdOrderByIdAsc(cycleId);
        if (cycleKpis.isEmpty()) {
            throw invalid("At least one KPI must be configured before publishing.");
        }
        validateWeightage(cycleKpis.stream()
                .map(ck -> new SelectedKpi(ck.getKpi(), ck.getWeightage()))
                .toList());
        for (CycleKpi cycleKpi : cycleKpis) {
            if (!cycleKpi.getKpi().isActive()) {
                throw conflict("KPI '" + cycleKpi.getKpi().getName()
                        + "' is no longer active and cannot be published.");
            }
        }

        List<EmployeeAssignment> assignments =
                assignmentRepository.findByCycle_IdOrderByEmployee_IdAsc(cycleId);
        if (assignments.isEmpty()) {
            throw invalid("At least one employee must be assigned before publishing.");
        }

        // Lock every person referenced by the roster in ascending id order,
        // then re-validate the live role and state. This closes the window
        // between roster configuration and publication.
        Set<Long> staffIds = new TreeSet<>();
        for (EmployeeAssignment assignment : assignments) {
            staffIds.add(assignment.getEmployee().getId());
            staffIds.add(assignment.getManager().getId());
        }
        Map<Long, Employee> lockedStaff = new HashMap<>();
        for (Long staffId : staffIds) {
            lockedStaff.put(staffId, employeeRepository.findByIdForUpdate(staffId).orElse(null));
        }

        Set<Long> seenEmployees = new HashSet<>();
        for (EmployeeAssignment assignment : assignments) {
            Employee employee = lockedStaff.get(assignment.getEmployee().getId());
            Employee manager = lockedStaff.get(assignment.getManager().getId());

            if (employee == null || manager == null) {
                throw conflict("An assigned employee or manager no longer exists.");
            }
            if (!seenEmployees.add(employee.getId())) {
                throw conflict("An employee is assigned more than once in this cycle.");
            }
            if (Objects.equals(employee.getId(), manager.getId())) {
                throw conflict("An employee cannot be assigned to themselves.");
            }
            assertAppraisee(employee);
            assertReviewer(manager);
        }

        List<EmployeeKpi> snapshots = new ArrayList<>(assignments.size() * cycleKpis.size());
        for (EmployeeAssignment assignment : assignments) {
            assignment.setStatus(AssignmentStatus.SELF_ASSESSMENT_PENDING);
            for (CycleKpi cycleKpi : cycleKpis) {
                KpiMaster kpi = cycleKpi.getKpi();
                EmployeeKpi snapshot = new EmployeeKpi();
                snapshot.setAssignment(assignment);
                snapshot.setKpi(kpi);
                snapshot.setKpiName(kpi.getName());
                snapshot.setKpiDescription(kpi.getDescription());
                snapshot.setKpiCategory(kpi.getCategory());
                snapshot.setKpiType(kpi.getKpiType() == null ? KpiType.MANAGER : kpi.getKpiType());
                snapshot.setWeightage(cycleKpi.getWeightage());
                snapshots.add(snapshot);
            }
        }
        employeeKpiRepository.saveAll(snapshots);
        employeeKpiRepository.flush();

        cycle.setStatus(CycleStatus.PUBLISHED);
        cycle.setPublishedAt(now());
        cycle.setCompletedAt(null);
        return toResponse(saveCycle(cycle));
    }

    // ----------------------------------------------------------------- helpers

    private PerformanceCycle requireCycle(Long cycleId) {
        return cycleRepository.findById(cycleId)
                .orElseThrow(() -> notFound("Performance cycle not found."));
    }

    private PerformanceCycle lockDraftCycle(Long cycleId, String conflictMessage) {
        PerformanceCycle cycle = cycleRepository.findByIdForUpdate(cycleId)
                .orElseThrow(() -> notFound("Performance cycle not found."));
        if (cycle.getStatus() != CycleStatus.DRAFT) {
            throw conflict(conflictMessage);
        }
        return cycle;
    }

    /**
     * Current manager from the append-only reporting history, falling back to
     * the mirror column for data created before V2. The caller already holds
     * the employee row lock, so a concurrent reassignment cannot slip in.
     */
    private Long currentManagerId(Employee employee) {
        List<EmployeeManagerAssignment> open =
                managerAssignmentRepository.findByEmployee_IdAndEffectiveToIsNull(employee.getId());
        if (!open.isEmpty()) {
            return open.get(0).getManager().getId();
        }
        return employee.getManager() == null ? null : employee.getManager().getId();
    }

    private void assertAppraisee(Employee employee) {
        if (employee.getUser().getRole() != Role.EMPLOYEE) {
            throw invalid("The selected account is not an employee.");
        }
        if (employee.getStatus() != StaffStatus.ACTIVE || !employee.getUser().isEnabled()) {
            throw conflict("The selected employee is inactive.");
        }
    }

    private void assertReviewer(Employee manager) {
        if (manager.getUser().getRole() != Role.MANAGER) {
            throw invalid("The selected account is not a manager.");
        }
        if (manager.getStatus() != StaffStatus.ACTIVE || !manager.getUser().isEnabled()) {
            throw conflict("The selected manager is inactive.");
        }
    }

    private static void validateWeightage(List<SelectedKpi> selected) {
        if (selected.isEmpty()) {
            return;
        }
        long nulls = selected.stream().filter(choice -> choice.weightage() == null).count();
        if (nulls == selected.size()) {
            return;
        }
        if (nulls != 0) {
            throw invalid("KPI weightage must be set for every KPI or for none.");
        }

        BigDecimal total = BigDecimal.ZERO;
        for (SelectedKpi choice : selected) {
            BigDecimal weightage = choice.weightage();
            if (weightage.compareTo(BigDecimal.ZERO) <= 0
                    || weightage.compareTo(MAX_WEIGHTAGE) > 0) {
                throw invalid("KPI weightage must be greater than 0 and at most 100.");
            }
            if (weightage.stripTrailingZeros().scale() > 2) {
                throw invalid("KPI weightage must have at most 2 decimal places.");
            }
            total = total.add(weightage);
        }
        if (total.compareTo(REQUIRED_TOTAL) != 0) {
            throw invalid("KPI weightages must total exactly 100.00.");
        }
    }

    private static BigDecimal normalizeWeightage(BigDecimal weightage) {
        if (weightage == null) {
            return null;
        }
        if (weightage.compareTo(BigDecimal.ZERO) <= 0
                || weightage.compareTo(MAX_WEIGHTAGE) > 0) {
            throw invalid("KPI weightage must be greater than 0 and at most 100.");
        }
        if (weightage.stripTrailingZeros().scale() > 2) {
            throw invalid("KPI weightage must have at most 2 decimal places.");
        }
        return weightage.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static void validateDates(CycleRequest request) {
        LocalDate start = request.startDate();
        LocalDate end = request.endDate();
        LocalDate self = request.selfAssessmentDeadline();
        LocalDate manager = request.managerReviewDeadline();
        LocalDate hr = request.hrReviewDeadline();

        if (start == null || end == null || self == null || manager == null || hr == null) {
            throw invalid("All cycle dates are required.");
        }
        if (!start.isBefore(end)) {
            throw invalid("Start date must be before the end date.");
        }
        if (self.isBefore(start) || self.isAfter(end)) {
            throw invalid("Self-assessment deadline must be within the cycle dates.");
        }
        if (manager.isBefore(self)) {
            throw invalid("Manager review deadline must be on or after the self-assessment deadline.");
        }
        if (hr.isBefore(manager)) {
            throw invalid("HR review deadline must be on or after the manager review deadline.");
        }
    }

    private static String normalizeName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw invalid("Cycle name is required.");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw invalid("Cycle name must be at most " + MAX_NAME_LENGTH + " characters.");
        }
        return trimmed;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private PerformanceCycle saveCycle(PerformanceCycle cycle) {
        try {
            return cycleRepository.saveAndFlush(cycle);
        } catch (DataIntegrityViolationException ex) {
            throw conflict("A cycle with this name already exists.");
        }
    }

    private CycleResponse toResponse(PerformanceCycle cycle) {
        return new CycleResponse(
                cycle.getId(),
                cycle.getName(),
                cycle.getDescription(),
                cycle.getStartDate(),
                cycle.getEndDate(),
                cycle.getSelfAssessmentDeadline(),
                cycle.getManagerReviewDeadline(),
                cycle.getHrReviewDeadline(),
                cycle.getStatus().name(),
                cycle.getCreatedBy().getId(),
                cycle.getPublishedAt(),
                cycle.getCompletedAt(),
                cycle.getCreatedAt(),
                cycle.getUpdatedAt());
    }

    private CycleKpiResponse toKpiResponse(CycleKpi cycleKpi) {
        KpiMaster kpi = cycleKpi.getKpi();
        return new CycleKpiResponse(
                cycleKpi.getId(),
                kpi.getId(),
                kpi.getName(),
                kpi.getDescription(),
                kpi.getCategory(),
                kpi.getKpiType() == null ? KpiType.MANAGER.name() : kpi.getKpiType().name(),
                cycleKpi.getWeightage());
    }

    private CycleEmployeeResponse toEmployeeResponse(EmployeeAssignment assignment) {
        Employee employee = assignment.getEmployee();
        Employee manager = assignment.getManager();
        return new CycleEmployeeResponse(
                assignment.getId(),
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFullName(),
                manager.getId(),
                manager.getFullName(),
                assignment.getStatus().name());
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static HrApiException notFound(String message) {
        return new HrApiException(HrErrorType.NOT_FOUND, message);
    }

    private static HrApiException conflict(String message) {
        return new HrApiException(HrErrorType.CONFLICT, message);
    }

    private static HrApiException invalid(String message) {
        return new HrApiException(HrErrorType.INVALID_REQUEST, message);
    }

    private record SelectedKpi(KpiMaster kpi, BigDecimal weightage) {
    }

    private record SelectedEmployee(Employee employee, Employee manager) {
    }
}
