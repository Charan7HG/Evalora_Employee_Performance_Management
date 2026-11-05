package com.evalora.backend.controller;

import com.evalora.backend.dto.BulkResultPublicationResponse;
import com.evalora.backend.dto.PerformanceResultResponse;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.security.EvaloraPrincipal;
import com.evalora.backend.security.SecurityErrorResponse;
import com.evalora.backend.service.PerformanceResultService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@RestController
@RequestMapping("/api/hr/results")
public class HrResultController {

    private final PerformanceResultService resultService;

    public HrResultController(PerformanceResultService resultService) {
        this.resultService = resultService;
    }

    @GetMapping
    public List<PerformanceResultResponse> listAwaitingPublication(
            @AuthenticationPrincipal EvaloraPrincipal principal) {
        return resultService.listAwaitingPublication(principal.getUserId());
    }

    /**
     * Every result of one cycle, including results that are not published yet.
     * HR-only by the path rule in SecurityConfig. The optional department filter
     * matches the employee's stored department exactly (case-insensitive).
     */
    @GetMapping("/cycles/{cycleId}")
    public List<PerformanceResultResponse> listForCycle(
            @AuthenticationPrincipal EvaloraPrincipal principal,
            @PathVariable Long cycleId,
            @RequestParam(required = false) String department) {
        return resultService.listForCycleForHr(principal.getUserId(), cycleId, department);
    }

    @GetMapping("/{resultId}")
    public PerformanceResultResponse get(@AuthenticationPrincipal EvaloraPrincipal principal,
                                         @PathVariable Long resultId) {
        return resultService.getForHr(principal.getUserId(), resultId);
    }

    @PostMapping("/{resultId}/publish")
    public PerformanceResultResponse publish(@AuthenticationPrincipal EvaloraPrincipal principal,
                                             @PathVariable Long resultId) {
        return resultService.publish(principal.getUserId(), resultId);
    }

    @PostMapping("/cycles/{cycleId}/publish")
    public BulkResultPublicationResponse publishCycle(@AuthenticationPrincipal EvaloraPrincipal principal,
                                                      @PathVariable Long cycleId) {
        return resultService.publishCycle(principal.getUserId(), cycleId);
    }

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
