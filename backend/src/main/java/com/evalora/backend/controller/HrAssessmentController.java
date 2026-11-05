package com.evalora.backend.controller;

import com.evalora.backend.dto.HrAssessmentResponse;
import com.evalora.backend.dto.HrAssessmentStatusResponse;
import com.evalora.backend.dto.HrQueueItemResponse;
import com.evalora.backend.dto.SaveHrAssessmentDraftRequest;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.security.EvaloraPrincipal;
import com.evalora.backend.security.SecurityErrorResponse;
import com.evalora.backend.service.HrAssessmentService;
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
 * HR-only assessment review. The whole {@code /api/hr/**} path requires
 * {@code ROLE_HR} in {@code SecurityConfig}; this controller adds no new
 * security rule and never reads an HR identity from the request.
 *
 * <p>HR reviews every eligible employee, so there is no ownership predicate.
 * An assignment whose cycle is not exactly {@code PUBLISHED}, whose employee or
 * manager has not submitted, or which is unknown returns the same 404.</p>
 *
 * <p>Error mapping mirrors the other HR controllers so every module returns the
 * same JSON error shape.</p>
 */
@RestController
@RequestMapping("/api/hr/assessments")
public class HrAssessmentController {

    private final HrAssessmentService hrAssessmentService;

    public HrAssessmentController(HrAssessmentService hrAssessmentService) {
        this.hrAssessmentService = hrAssessmentService;
    }

    // ----------------------------------------------------------------- queue

    @GetMapping
    public List<HrQueueItemResponse> listPending(@AuthenticationPrincipal EvaloraPrincipal principal) {
        return hrAssessmentService.listPending(principal.getUserId());
    }

    // ---------------------------------------------------------------- detail

    @GetMapping("/{assignmentId}")
    public HrAssessmentResponse get(@AuthenticationPrincipal EvaloraPrincipal principal,
                                    @PathVariable Long assignmentId) {
        return hrAssessmentService.get(principal.getUserId(), assignmentId);
    }

    @GetMapping("/{assignmentId}/status")
    public HrAssessmentStatusResponse getStatus(@AuthenticationPrincipal EvaloraPrincipal principal,
                                                @PathVariable Long assignmentId) {
        return hrAssessmentService.getStatus(principal.getUserId(), assignmentId);
    }

    // ----------------------------------------------------------------- draft

    @PutMapping("/{assignmentId}/draft")
    public HrAssessmentResponse saveDraft(@AuthenticationPrincipal EvaloraPrincipal principal,
                                          @PathVariable Long assignmentId,
                                          @Valid @RequestBody SaveHrAssessmentDraftRequest request) {
        return hrAssessmentService.saveDraft(principal.getUserId(), assignmentId, request);
    }

    // ---------------------------------------------------------------- submit

    @PostMapping("/{assignmentId}/submit")
    public HrAssessmentStatusResponse submit(@AuthenticationPrincipal EvaloraPrincipal principal,
                                             @PathVariable Long assignmentId) {
        return hrAssessmentService.submit(principal.getUserId(), assignmentId);
    }

    // ----------------------------------------------------------- error mapping

    @ExceptionHandler(HrApiException.class)
    public ResponseEntity<SecurityErrorResponse> handleFailure(HrApiException ex,
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
