package com.evalora.backend.service;

import com.evalora.backend.entity.EmployeeKpi;
import com.evalora.backend.entity.enums.CalculationMode;
import com.evalora.backend.entity.enums.KpiType;
import com.evalora.backend.entity.enums.PerformanceGrade;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.exception.HrErrorType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The arithmetic of one final result. Kept separate from the workflow so the
 * rules are easy to read and can be tested without a database.
 *
 * <p>Only the manager and HR ratings are scored. The employee self-rating is
 * never passed to this class: it is reference information in the report, not
 * a scoring input.</p>
 *
 * <p><strong>Category model.</strong> A cycle can contain two independent sets
 * of KPIs:</p>
 * <ul>
 *   <li>{@link KpiType#MANAGER} KPIs are rated by the reviewing manager. Their
 *       category score is the weighted (or equal) mean of those ratings and
 *       contributes 75 % of the overall score.</li>
 *   <li>{@link KpiType#HR} KPIs are rated by both the manager and HR. Each HR
 *       KPI's combined rating is {@code (managerRating + hrRating) / 2}; their
 *       category score is the weighted (or equal) mean of those combined
 *       ratings and contributes 25 %.</li>
 * </ul>
 *
 * <p>The overall score is therefore {@code managerCategory * 0.75 +
 * hrCategory * 0.25}. A cycle that contains only one category is scored from
 * that category alone: the missing category is never invented as a zero and its
 * percentage is never redistributed across the other ratings. That rule is
 * deliberate, documented and reported to HR when the cycle is configured.</p>
 *
 * <p>Every calculation uses {@link BigDecimal}. Category scores keep four
 * decimals internally; the overall score is rounded once, at the end, with
 * {@code HALF_UP} to the two decimals the database column stores.</p>
 */
final class PerformanceScoreCalculator {

    /** Share of the overall score produced by the manager-assessed KPIs. */
    static final BigDecimal MANAGER_CATEGORY_WEIGHT = new BigDecimal("0.75");

    /** Share of the overall score produced by the HR-assessed KPIs. */
    static final BigDecimal HR_CATEGORY_WEIGHT = new BigDecimal("0.25");

    private static final BigDecimal FULL_WEIGHT = new BigDecimal("100.00");

    private static final BigDecimal OUTSTANDING_FLOOR = new BigDecimal("4.50");
    private static final BigDecimal EXCELLENT_FLOOR = new BigDecimal("4.00");
    private static final BigDecimal VERY_GOOD_FLOOR = new BigDecimal("3.50");
    private static final BigDecimal GOOD_FLOOR = new BigDecimal("3.00");
    private static final BigDecimal NEEDS_IMPROVEMENT_FLOOR = new BigDecimal("2.00");

    private PerformanceScoreCalculator() {
    }

    // ------------------------------------------------------------- one KPI

    /**
     * The score of a single KPI.
     *
     * <p>A {@link KpiType#MANAGER} KPI is scored from the manager rating alone.
     * An {@link KpiType#HR} KPI is scored from the average of the manager and
     * HR ratings, {@code (managerRating + hrRating) / 2}, because both
     * assessors rate every HR KPI. A missing required rating is rejected
     * instead of being treated as zero.</p>
     */
    static BigDecimal kpiScore(KpiType kpiType, Short managerRating, Short hrRating) {
        if (kpiType == KpiType.HR) {
            if (managerRating == null) {
                throw conflict("Every HR-assessed KPI must have a manager rating before scoring.");
            }
            if (hrRating == null) {
                throw conflict("Every HR-assessed KPI must have an HR rating before scoring.");
            }
            BigDecimal combined = BigDecimal.valueOf(managerRating)
                    .add(BigDecimal.valueOf(hrRating));
            return combined.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        }
        if (managerRating == null) {
            throw conflict("Every manager-assessed KPI must have a manager rating before scoring.");
        }
        return BigDecimal.valueOf(managerRating).setScale(2, RoundingMode.UNNECESSARY);
    }

    // -------------------------------------------------------- category score

    /**
     * One category's score: the weighted mean of its ratings, or the plain mean
     * when the cycle is unweighted.
     *
     * <p>Weights are normalised inside the category, so the weights HR saved
     * for the whole cycle (which add up to 100 across both categories) keep
     * their relative influence without letting one category's share leak into
     * the other. Unweighted cycles are the documented equal-share average.</p>
     *
     * @param ratings    ratings of every KPI in the category, in any order
     * @param weightages the same KPIs' weightages; all {@code null} means
     *                   unweighted
     */
    static BigDecimal categoryScore(List<BigDecimal> ratings, List<BigDecimal> weightages) {
        if (ratings == null || ratings.isEmpty()) {
            throw conflict("A scoring category cannot be empty.");
        }
        if (weightages == null || weightages.size() != ratings.size()) {
            throw conflict("Every KPI must have a weightage, or none of them.");
        }

        BigDecimal totalWeight = BigDecimal.ZERO;
        for (BigDecimal weight : weightages) {
            if (weight == null) {
                continue;
            }
            if (weight.signum() <= 0 || weight.compareTo(FULL_WEIGHT) > 0) {
                throw conflict("Each KPI weightage must be greater than 0 and at most 100.");
            }
            totalWeight = totalWeight.add(weight);
        }

        long missing = weightages.stream().filter(weight -> weight == null).count();
        if (missing == weightages.size()) {
            return average(ratings);
        }
        if (missing > 0) {
            throw conflict("Every KPI must have a weightage, or none of them.");
        }

        BigDecimal weightedTotal = BigDecimal.ZERO;
        for (int index = 0; index < ratings.size(); index++) {
            weightedTotal = weightedTotal.add(ratings.get(index).multiply(weightages.get(index)));
        }
        // totalWeight is > 0 because every weight passed the check above.
        return weightedTotal.divide(totalWeight, 4, RoundingMode.HALF_UP);
    }

    // ----------------------------------------------------------- overall score

    /**
     * {@code managerCategory * 0.75 + hrCategory * 0.25}, rounded once.
     *
     * <p>When a cycle has only one category, that category's score is the
     * overall score and its weight is not silently redistributed or zeroed.</p>
     */
    static BigDecimal overallScore(BigDecimal managerCategory, BigDecimal hrCategory) {
        if (managerCategory == null && hrCategory == null) {
            throw conflict("No assessed KPI is available for scoring.");
        }
        if (managerCategory == null) {
            return hrCategory.setScale(2, RoundingMode.HALF_UP);
        }
        if (hrCategory == null) {
            return managerCategory.setScale(2, RoundingMode.HALF_UP);
        }
        return MANAGER_CATEGORY_WEIGHT.multiply(managerCategory)
                .add(HR_CATEGORY_WEIGHT.multiply(hrCategory))
                .setScale(2, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------- legacy

    /**
     * Weighted when every KPI has a weight that adds up to exactly 100.00;
     * average when no KPI has a weight. Every other combination is rejected -
     * there is deliberately no fallback.
     */
    static CalculationMode resolveMode(List<EmployeeKpi> kpis) {
        if (kpis.isEmpty()) {
            throw conflict("No KPIs are assigned to this assessment.");
        }

        boolean anyWeight = false;
        boolean anyMissing = false;
        for (EmployeeKpi kpi : kpis) {
            if (kpi.getWeightage() == null) {
                anyMissing = true;
            } else {
                anyWeight = true;
            }
        }
        if (anyWeight && anyMissing) {
            throw conflict("Every KPI must have a weightage, or none of them.");
        }
        if (anyMissing) {
            return CalculationMode.AVERAGE;
        }

        BigDecimal total = BigDecimal.ZERO;
        for (EmployeeKpi kpi : kpis) {
            BigDecimal weight = kpi.getWeightage();
            if (weight.signum() <= 0 || weight.compareTo(FULL_WEIGHT) > 0) {
                throw conflict("Each KPI weightage must be greater than 0 and at most 100.");
            }
            total = total.add(weight);
        }
        if (total.compareTo(FULL_WEIGHT) != 0) {
            throw conflict("KPI weightages must add up to exactly 100.00.");
        }
        return CalculationMode.WEIGHTED;
    }

    /**
     * {@code sum(ratings) / count}, rounded to two decimals with
     * {@code HALF_UP}. Used directly by the unweighted explanation in the UI
     * and by {@link #categoryScore} for unweighted cycles.
     */
    static BigDecimal average(List<BigDecimal> ratings) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal rating : ratings) {
            total = total.add(rating);
        }
        return total.divide(BigDecimal.valueOf(ratings.size()), 4, RoundingMode.HALF_UP);
    }

    /**
     * The single application-wide grade scale. Thresholds are inclusive
     * floors, and the overall score has already been rounded to two decimals.
     */
    static PerformanceGrade gradeFor(BigDecimal overallScore) {
        if (overallScore.compareTo(OUTSTANDING_FLOOR) >= 0) {
            return PerformanceGrade.OUTSTANDING;
        }
        if (overallScore.compareTo(EXCELLENT_FLOOR) >= 0) {
            return PerformanceGrade.EXCELLENT;
        }
        if (overallScore.compareTo(VERY_GOOD_FLOOR) >= 0) {
            return PerformanceGrade.VERY_GOOD;
        }
        if (overallScore.compareTo(GOOD_FLOOR) >= 0) {
            return PerformanceGrade.GOOD;
        }
        if (overallScore.compareTo(NEEDS_IMPROVEMENT_FLOOR) >= 0) {
            return PerformanceGrade.NEEDS_IMPROVEMENT;
        }
        return PerformanceGrade.UNSATISFACTORY;
    }

    private static HrApiException conflict(String message) {
        return new HrApiException(HrErrorType.CONFLICT, message);
    }
}
