package com.evalora.backend.controller;

import com.evalora.backend.dto.CycleEmployeeResponse;
import com.evalora.backend.dto.CycleKpiResponse;
import com.evalora.backend.dto.CycleRequest;
import com.evalora.backend.dto.CycleResponse;
import com.evalora.backend.dto.ConfigureCycleEmployeesRequest;
import com.evalora.backend.dto.ConfigureCycleKpisRequest;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.security.EvaloraPrincipal;
import com.evalora.backend.security.SecurityErrorResponse;
import com.evalora.backend.service.PerformanceCycleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * HR-only performance-cycle management. The whole {@code /api/hr/**} path
 * requires {@code ROLE_HR} in {@code SecurityConfig}; creator, status,
 * publication timestamps and audit fields are never read from the request.
 *
 * <p>Error mapping mirrors {@link HrEmployeeController} and
 * {@link HrKpiController} so every HR module returns the same JSON error
 * shape.</p>
 */
@RestController
@RequestMapping("/api/hr/cycles")
public class HrCycleController {

    private final PerformanceCycleService performanceCycleService;

    public HrCycleController(PerformanceCycleService performanceCycleService) {
        this.performanceCycleService = performanceCycleService;
    }

    // ------------------------------------------------------------------ create

    @PostMapping
    public ResponseEntity<CycleResponse> create(
            @AuthenticationPrincipal EvaloraPrincipal principal,
            @Valid @RequestBody CycleRequest request) {
        CycleResponse response = performanceCycleService.create(request, principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // -------------------------------------------------------------------- read

    @GetMapping
    public List<CycleResponse> list() {
        return performanceCycleService.list();
    }

    @GetMapping("/{cycleId}")
    public CycleResponse get(@PathVariable Long cycleId) {
        return performanceCycleService.get(cycleId);
    }

    @GetMapping("/{cycleId}/kpis")
    public List<CycleKpiResponse> getKpis(@PathVariable Long cycleId) {
        return performanceCycleService.getKpis(cycleId);
    }

    @GetMapping("/{cycleId}/employees")
    public List<CycleEmployeeResponse> getEmployees(@PathVariable Long cycleId) {
        return performanceCycleService.getEmployees(cycleId);
    }

    // ------------------------------------------------------------------ update

    @PutMapping("/{cycleId}")
    public CycleResponse update(@PathVariable Long cycleId,
                                @Valid @RequestBody CycleRequest request) {
        return performanceCycleService.update(cycleId, request);
    }

    // -------------------------------------------------------------- KPI roster

    @PostMapping("/{cycleId}/kpis")
    public List<CycleKpiResponse> configureKpis(
            @PathVariable Long cycleId,
            @Valid @RequestBody ConfigureCycleKpisRequest request) {
        return performanceCycleService.configureKpis(cycleId, request);
    }

    // --------------------------------------------------------- employee roster

    @PutMapping("/{cycleId}/employees")
    public List<CycleEmployeeResponse> configureEmployees(
            @PathVariable Long cycleId,
            @Valid @RequestBody ConfigureCycleEmployeesRequest request) {
        return performanceCycleService.configureEmployees(cycleId, request);
    }

    // ----------------------------------------------------------------- publish

    @PostMapping("/{cycleId}/publish")
    public CycleResponse publish(@PathVariable Long cycleId) {
        return performanceCycleService.publish(cycleId);
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
