package com.evalora.backend.entity.enums;

/**
 * Workflow position of one employee inside one cycle.
 * The order is fixed and one-way in v1.
 */
public enum AssignmentStatus {
    SELF_ASSESSMENT_PENDING,
    MANAGER_REVIEW_PENDING,
    HR_REVIEW_PENDING,
    COMPLETED,
    RESULTS_PUBLISHED
}
