package com.evalora.backend.controller;

import com.evalora.backend.dto.AssessmentStatusItemResponse;
import com.evalora.backend.dto.DirectReportResponse;
import com.evalora.backend.entity.enums.AssignmentStatus;
import com.evalora.backend.security.EvaloraPrincipal;
import com.evalora.backend.service.AssessmentStatusService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only drill-down lists for assessment dashboard counters. */
@RestController
@RequestMapping("/api")
public class AssessmentStatusController {

    private final AssessmentStatusService assessmentStatusService;

    public AssessmentStatusController(AssessmentStatusService assessmentStatusService) {
        this.assessmentStatusService = assessmentStatusService;
    }

    @GetMapping("/hr/assessment-status")
    public List<AssessmentStatusItemResponse> hrStatus(
            @AuthenticationPrincipal EvaloraPrincipal principal,
            @RequestParam AssignmentStatus status) {
        return assessmentStatusService.listForHr(principal.getUserId(), status);
    }

    @GetMapping("/manager/assessment-status")
    public List<AssessmentStatusItemResponse> managerStatus(
            @AuthenticationPrincipal EvaloraPrincipal principal,
            @RequestParam AssignmentStatus status) {
        return assessmentStatusService.listForManager(principal.getUserId(), status);
    }

    @GetMapping("/manager/direct-reports")
    public List<DirectReportResponse> directReports(@AuthenticationPrincipal EvaloraPrincipal principal) {
        return assessmentStatusService.listDirectReports(principal.getUserId());
    }
}
