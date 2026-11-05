package com.evalora.backend.controller;

import com.evalora.backend.dto.AssignManagerRequest;
import com.evalora.backend.dto.CreateStaffRequest;
import com.evalora.backend.dto.StaffResponse;
import com.evalora.backend.dto.UpdateStaffRequest;
import com.evalora.backend.entity.enums.StaffStatus;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.security.EvaloraPrincipal;
import com.evalora.backend.security.SecurityErrorResponse;
import com.evalora.backend.service.EmployeeManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * HR-only staff management. The whole path is restricted to {@code ROLE_HR} in
 * {@code SecurityConfig}; no role is ever read from the request.
 *
 * <p>Errors are classified by the service ({@code HrApiException}) and mapped
 * here to the same JSON error body the security layer uses. No stack trace or
 * database message ever reaches the client.</p>
 */
@RestController
@RequestMapping("/api/hr")
public class HrEmployeeController {

    private final EmployeeManagementService employeeManagementService;

    public HrEmployeeController(EmployeeManagementService employeeManagementService) {
        this.employeeManagementService = employeeManagementService;
    }

    // ------------------------------------------------------------------ create

    @PostMapping("/employees")
    public ResponseEntity<StaffResponse> createEmployee(
            @AuthenticationPrincipal EvaloraPrincipal principal,
            @Valid @RequestBody CreateStaffRequest request) {
        StaffResponse response = employeeManagementService.createEmployee(
                request, principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/managers")
    public ResponseEntity<StaffResponse> createManager(
            @AuthenticationPrincipal EvaloraPrincipal principal,
            @Valid @RequestBody CreateStaffRequest request) {
        StaffResponse response = employeeManagementService.createManager(
                request, principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // -------------------------------------------------------------------- read

    @GetMapping("/employees")
    public List<StaffResponse> listEmployees(
            @RequestParam(defaultValue = "ACTIVE") StaffStatus status) {
        return employeeManagementService.listEmployees(status);
    }

    @GetMapping("/employees/{employeeId}")
    public StaffResponse getEmployee(@PathVariable Long employeeId) {
        return employeeManagementService.getEmployee(employeeId);
    }

    @GetMapping("/managers")
    public List<StaffResponse> listManagers(
            @RequestParam(defaultValue = "ACTIVE") StaffStatus status) {
        return employeeManagementService.listManagers(status);
    }

    @GetMapping("/managers/{managerId}")
    public StaffResponse getManager(@PathVariable Long managerId) {
        return employeeManagementService.getManager(managerId);
    }

    // ------------------------------------------------------------------ update

    @PutMapping("/employees/{employeeId}")
    public StaffResponse updateEmployee(@PathVariable Long employeeId,
                                        @Valid @RequestBody UpdateStaffRequest request) {
        return employeeManagementService.updateEmployee(employeeId, request);
    }

    @PutMapping("/managers/{managerId}")
    public StaffResponse updateManager(@PathVariable Long managerId,
                                       @Valid @RequestBody UpdateStaffRequest request) {
        return employeeManagementService.updateManager(managerId, request);
    }

    // -------------------------------------------------------------- assignment

    @PutMapping("/employees/{employeeId}/manager")
    public StaffResponse assignManager(@PathVariable Long employeeId,
                                       @Valid @RequestBody AssignManagerRequest request,
                                       @AuthenticationPrincipal EvaloraPrincipal principal) {
        return employeeManagementService.assignManager(
                employeeId, request.managerId(), principal.getUserId());
    }

    // ------------------------------------------------------- activate/deactivate

    @PatchMapping("/employees/{employeeId}/deactivate")
    public StaffResponse deactivateEmployee(@PathVariable Long employeeId) {
        return employeeManagementService.deactivateEmployee(employeeId);
    }

    @PatchMapping("/employees/{employeeId}/reactivate")
    public StaffResponse reactivateEmployee(@PathVariable Long employeeId) {
        return employeeManagementService.reactivateEmployee(employeeId);
    }

    @PatchMapping("/managers/{managerId}/deactivate")
    public StaffResponse deactivateManager(@PathVariable Long managerId) {
        return employeeManagementService.deactivateManager(managerId);
    }

    @PatchMapping("/managers/{managerId}/reactivate")
    public StaffResponse reactivateManager(@PathVariable Long managerId) {
        return employeeManagementService.reactivateManager(managerId);
    }

    // ----------------------------------------------------------- error mapping

    @ExceptionHandler(HrApiException.class)
    public ResponseEntity<SecurityErrorResponse> handleHrFailure(HrApiException ex,
                                                                 HttpServletRequest request) {
        HttpStatus status = switch (ex.getType()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        return error(status, status.name(), ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<SecurityErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                                  HttpServletRequest request) {
        FieldError fieldError = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String message = fieldError == null ? "Invalid request." : fieldError.getDefaultMessage();
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message, request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<SecurityErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                                    HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "Invalid value for parameter '" + ex.getName() + "'.", request.getRequestURI());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<SecurityErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                                      HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "Malformed request body.", request.getRequestURI());
    }

    /**
     * Last-resort mapping for a uniqueness race that slipped past the service
     * pre-checks. The database detail stays in the log, not in the response.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<SecurityErrorResponse> handleConflict(DataIntegrityViolationException ex,
                                                                HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "CONFLICT",
                "The operation conflicts with existing data.", request.getRequestURI());
    }

    private static ResponseEntity<SecurityErrorResponse> error(HttpStatus status,
                                                               String error,
                                                               String message,
                                                               String path) {
        SecurityErrorResponse body = new SecurityErrorResponse(
                OffsetDateTime.now(ZoneOffset.UTC).toString(),
                status.value(), error, message, path);
        return ResponseEntity.status(status).body(body);
    }
}
