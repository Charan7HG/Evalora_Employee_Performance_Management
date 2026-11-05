package com.evalora.backend.controller;

import com.evalora.backend.dto.CreateKpiRequest;
import com.evalora.backend.dto.KpiResponse;
import com.evalora.backend.dto.UpdateKpiRequest;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.security.EvaloraPrincipal;
import com.evalora.backend.security.SecurityErrorResponse;
import com.evalora.backend.service.KpiManagementService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * HR-only KPI catalogue management. The whole {@code /api/hr/**} path requires
 * {@code ROLE_HR} in {@code SecurityConfig}; no role, creator or active flag is
 * ever taken from the request body.
 *
 * <p>The exception handlers mirror {@link HrEmployeeController} so both HR
 * modules return the same JSON error shape without changing the existing
 * controller.</p>
 */
@RestController
@RequestMapping("/api/hr/kpis")
public class HrKpiController {

    private final KpiManagementService kpiManagementService;

    public HrKpiController(KpiManagementService kpiManagementService) {
        this.kpiManagementService = kpiManagementService;
    }

    @PostMapping
    public ResponseEntity<KpiResponse> create(
            @AuthenticationPrincipal EvaloraPrincipal principal,
            @Valid @RequestBody CreateKpiRequest request) {
        KpiResponse response = kpiManagementService.create(request, principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** No {@code active} parameter means every KPI, active and inactive. */
    @GetMapping
    public List<KpiResponse> list(@RequestParam(required = false) Boolean active) {
        return kpiManagementService.list(active);
    }

    @GetMapping("/{kpiId}")
    public KpiResponse get(@PathVariable Long kpiId) {
        return kpiManagementService.get(kpiId);
    }

    @PutMapping("/{kpiId}")
    public KpiResponse update(@PathVariable Long kpiId,
                              @Valid @RequestBody UpdateKpiRequest request) {
        return kpiManagementService.update(kpiId, request);
    }

    @PatchMapping("/{kpiId}/deactivate")
    public KpiResponse deactivate(@PathVariable Long kpiId) {
        return kpiManagementService.deactivate(kpiId);
    }

    @PatchMapping("/{kpiId}/reactivate")
    public KpiResponse reactivate(@PathVariable Long kpiId) {
        return kpiManagementService.reactivate(kpiId);
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
