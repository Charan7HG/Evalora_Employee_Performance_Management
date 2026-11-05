package com.evalora.backend.controller;

import com.evalora.backend.dto.PerformanceResultResponse;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.security.EvaloraPrincipal;
import com.evalora.backend.security.SecurityErrorResponse;
import com.evalora.backend.service.PerformanceResultService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@RestController
@RequestMapping("/api/manager/results")
public class ManagerResultController {

    private final PerformanceResultService resultService;

    public ManagerResultController(PerformanceResultService resultService) {
        this.resultService = resultService;
    }

    @GetMapping
    public List<PerformanceResultResponse> list(@AuthenticationPrincipal EvaloraPrincipal principal) {
        return resultService.listForManager(principal.getUserId());
    }

    @GetMapping("/{resultId}")
    public PerformanceResultResponse get(@AuthenticationPrincipal EvaloraPrincipal principal,
                                         @PathVariable Long resultId) {
        return resultService.getForManager(principal.getUserId(), resultId);
    }

    @ExceptionHandler(HrApiException.class)
    public ResponseEntity<SecurityErrorResponse> handleFailure(HrApiException ex,
                                                               HttpServletRequest request) {
        HttpStatus status = switch (ex.getType()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        SecurityErrorResponse body = new SecurityErrorResponse(
                OffsetDateTime.now(ZoneOffset.UTC).toString(),
                status.value(), status.name(), ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
