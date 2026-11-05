package com.evalora.backend.entity.enums;

import lombok.Getter;

/**
 * One single grade scale for the whole application.
 * The stored value is the constant name; displayName is for the UI.
 */
@Getter
public enum PerformanceGrade {

    OUTSTANDING("Outstanding"),
    EXCELLENT("Excellent"),
    VERY_GOOD("Very Good"),
    GOOD("Good"),
    NEEDS_IMPROVEMENT("Needs Improvement"),
    UNSATISFACTORY("Unsatisfactory");

    private final String displayName;

    PerformanceGrade(String displayName) {
        this.displayName = displayName;
    }
}
