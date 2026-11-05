package com.evalora.backend.service;

import com.evalora.backend.entity.EmployeeKpi;
import com.evalora.backend.entity.enums.CalculationMode;
import com.evalora.backend.entity.enums.KpiType;
import com.evalora.backend.entity.enums.PerformanceGrade;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.exception.HrErrorType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure arithmetic tests for the Phase 5D category model. No Spring and no
 * database: the same inputs the HR submission passes to the calculator produce
 * the documented scores, modes and grades.
 */
class PerformanceScoreCalculatorTest {

    // ------------------------------------------------------------ KPI score

    @Test
    void managerKpiScoresFromTheManagerRatingAlone() {
        assertThat(PerformanceScoreCalculator.kpiScore(KpiType.MANAGER, (short) 4, (short) 5))
                .isEqualByComparingTo("4.00");
        assertThat(PerformanceScoreCalculator.kpiScore(KpiType.MANAGER, (short) 5, null))
                .isEqualByComparingTo("5.00");
    }

    @Test
    void hrKpiScoresFromTheAverageOfManagerAndHrRatings() {
        assertThat(PerformanceScoreCalculator.kpiScore(KpiType.HR, (short) 4, (short) 2))
                .isEqualByComparingTo("3.00");
        assertThat(PerformanceScoreCalculator.kpiScore(KpiType.HR, (short) 4, (short) 5))
                .isEqualByComparingTo("4.50");
    }

    @Test
    void kpiScoreKeepsExactlyTwoDecimals() {
        assertThat(PerformanceScoreCalculator.kpiScore(KpiType.MANAGER, (short) 4, null).scale())
                .isEqualTo(2);
    }

    @Test
    void hrKpiWithoutAnHrRatingIsRejectedInsteadOfScoredAsZero() {
        assertThatThrownBy(() -> PerformanceScoreCalculator.kpiScore(KpiType.HR, (short) 4, null))
                .isInstanceOf(HrApiException.class)
                .satisfies(failure -> assertThat(((HrApiException) failure).getType())
                        .isEqualTo(HrErrorType.CONFLICT))
                .hasMessage("Every HR-assessed KPI must have an HR rating before scoring.");
    }

    @Test
    void hrKpiWithoutAManagerRatingIsRejectedInsteadOfScoredAsZero() {
        assertThatThrownBy(() -> PerformanceScoreCalculator.kpiScore(KpiType.HR, null, (short) 5))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Every HR-assessed KPI must have a manager rating before scoring.");
    }

    @Test
    void managerKpiWithoutAManagerRatingIsRejectedInsteadOfScoredAsZero() {
        assertThatThrownBy(() -> PerformanceScoreCalculator.kpiScore(
                KpiType.MANAGER, null, (short) 4))
                .isInstanceOf(HrApiException.class)
                .satisfies(failure -> assertThat(((HrApiException) failure).getType())
                        .isEqualTo(HrErrorType.CONFLICT))
                .hasMessage("Every manager-assessed KPI must have a manager rating before scoring.");
    }

    // -------------------------------------------------------- category score

    @Test
    void weightedCategoryNormalisesItsOwnWeights() {
        // (4 * 60 + 5 * 40) / 100 = 4.40
        assertThat(PerformanceScoreCalculator.categoryScore(
                List.of(score("4.00"), score("5.00")),
                List.of(weight("60.00"), weight("40.00"))))
                .isEqualByComparingTo("4.40");
    }

    @Test
    void weightedCategoryIgnoresTheAbsoluteWeightTotal() {
        // (4 * 30 + 5 * 20) / 50 = 4.40
        assertThat(PerformanceScoreCalculator.categoryScore(
                List.of(score("4.00"), score("5.00")),
                List.of(weight("30.00"), weight("20.00"))))
                .isEqualByComparingTo("4.40");
    }

    @Test
    void unweightedCategoryUsesTheEqualShareAverage() {
        // (4 + 5) / 2 = 4.50
        assertThat(PerformanceScoreCalculator.categoryScore(
                List.of(score("4.00"), score("5.00")),
                Arrays.asList(null, null)))
                .isEqualByComparingTo("4.50");
    }

    @Test
    void mixedCategoryWeightsAreRejected() {
        assertThatThrownBy(() -> PerformanceScoreCalculator.categoryScore(
                List.of(score("4.00"), score("5.00")),
                Arrays.asList(weight("60.00"), null)))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Every KPI must have a weightage, or none of them.");
    }

    @Test
    void categoryZeroWeightIsRejected() {
        assertThatThrownBy(() -> PerformanceScoreCalculator.categoryScore(
                List.of(score("4.00"), score("5.00")),
                List.of(weight("0.00"), weight("100.00"))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Each KPI weightage must be greater than 0 and at most 100.");
    }

    @Test
    void categoryWeightAbove100IsRejected() {
        assertThatThrownBy(() -> PerformanceScoreCalculator.categoryScore(
                List.of(score("4.00")), List.of(weight("100.01"))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Each KPI weightage must be greater than 0 and at most 100.");
    }

    @Test
    void emptyCategoryIsRejected() {
        assertThatThrownBy(() -> PerformanceScoreCalculator.categoryScore(
                List.of(), List.of()))
                .isInstanceOf(HrApiException.class)
                .hasMessage("A scoring category cannot be empty.");
    }

    // ----------------------------------------------------------- overall score

    @Test
    void overallScoreIsSeventyFivePercentManagerAndTwentyFivePercentHr() {
        // 4 * 0.75 + 5 * 0.25 = 4.25
        assertThat(PerformanceScoreCalculator.overallScore(score("4.00"), score("5.00")))
                .isEqualByComparingTo("4.25");
    }

    @Test
    void managerOnlyCycleUsesTheManagerCategoryWithoutZeroingOrRedistribution() {
        assertThat(PerformanceScoreCalculator.overallScore(score("4.00"), null))
                .isEqualByComparingTo("4.00");
    }

    @Test
    void hrOnlyCycleUsesTheHrCategoryWithoutZeroingOrRedistribution() {
        assertThat(PerformanceScoreCalculator.overallScore(null, score("5.00")))
                .isEqualByComparingTo("5.00");
    }

    @Test
    void overallScoreRoundsHalfUpOnceAtTheEnd() {
        // 4.00 * 0.75 + 4.50 * 0.25 = 4.125 -> 4.13
        assertThat(PerformanceScoreCalculator.overallScore(score("4.00"), score("4.50")))
                .isEqualByComparingTo("4.13");
    }

    @Test
    void firstBusinessExampleScoresThreePointSeventyFive() {
        assertThat(PerformanceScoreCalculator.kpiScore(KpiType.HR, (short) 4, (short) 2))
                .isEqualByComparingTo("3.00");
        assertThat(PerformanceScoreCalculator.overallScore(score("4.00"), score("3.00")))
                .isEqualByComparingTo("3.75");
    }

    @Test
    void secondBusinessExampleRoundsFourPointOneTwoFiveToFourPointOneThree() {
        assertThat(PerformanceScoreCalculator.kpiScore(KpiType.HR, (short) 4, (short) 5))
                .isEqualByComparingTo("4.50");
        assertThat(PerformanceScoreCalculator.overallScore(score("4.00"), score("4.50")))
                .isEqualByComparingTo("4.13");
    }

    @Test
    void overallScoreAlwaysHasExactlyTwoDecimals() {
        assertThat(PerformanceScoreCalculator.overallScore(score("4.00"), score("4.00")).scale())
                .isEqualTo(2);
        assertThat(PerformanceScoreCalculator.overallScore(score("4.00"), null).scale())
                .isEqualTo(2);
    }

    @Test
    void noCategoryAtAllIsRejected() {
        assertThatThrownBy(() -> PerformanceScoreCalculator.overallScore(null, null))
                .isInstanceOf(HrApiException.class)
                .hasMessage("No assessed KPI is available for scoring.");
    }

    // ---------------------------------------------------------- scoring mode

    @Test
    void everyWeightSummingTo100UsesWeightedScoring() {
        assertThat(PerformanceScoreCalculator.resolveMode(List.of(kpi("60.00"), kpi("40.00"))))
                .isEqualTo(CalculationMode.WEIGHTED);
    }

    @Test
    void everyWeightNullUsesAverageScoring() {
        assertThat(PerformanceScoreCalculator.resolveMode(List.of(kpi(null), kpi(null))))
                .isEqualTo(CalculationMode.AVERAGE);
    }

    @Test
    void mixedWeightsAreRejectedInsteadOfFallingBack() {
        assertThatThrownBy(() -> PerformanceScoreCalculator.resolveMode(
                List.of(kpi("60.00"), kpi(null))))
                .isInstanceOf(HrApiException.class)
                .satisfies(failure -> assertThat(((HrApiException) failure).getType())
                        .isEqualTo(HrErrorType.CONFLICT))
                .hasMessage("Every KPI must have a weightage, or none of them.");
    }

    @Test
    void weightsThatDoNotAddUpTo100AreRejected() {
        assertThatThrownBy(() -> PerformanceScoreCalculator.resolveMode(
                List.of(kpi("60.00"), kpi("30.00"))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("KPI weightages must add up to exactly 100.00.");
    }

    @Test
    void zeroWeightIsRejected() {
        assertThatThrownBy(() -> PerformanceScoreCalculator.resolveMode(
                List.of(kpi("0.00"), kpi("100.00"))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Each KPI weightage must be greater than 0 and at most 100.");
    }

    @Test
    void negativeWeightIsRejected() {
        assertThatThrownBy(() -> PerformanceScoreCalculator.resolveMode(
                List.of(kpi("-1.00"), kpi("101.00"))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Each KPI weightage must be greater than 0 and at most 100.");
    }

    @Test
    void weightGreaterThan100IsRejected() {
        assertThatThrownBy(() -> PerformanceScoreCalculator.resolveMode(
                List.of(kpi("101.00"), kpi("100.00"))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Each KPI weightage must be greater than 0 and at most 100.");
    }

    @Test
    void emptyKpiCollectionIsRejected() {
        assertThatThrownBy(() -> PerformanceScoreCalculator.resolveMode(List.of()))
                .isInstanceOf(HrApiException.class)
                .hasMessage("No KPIs are assigned to this assessment.");
    }

    // ---------------------------------------------------------------- average

    @Test
    void averageUsesFourDecimalsForAnUnweightedCategory() {
        // 11.75 / 3 = 3.91666... -> 3.9167
        assertThat(PerformanceScoreCalculator.average(
                List.of(score("4.25"), score("3.50"), score("4.00"))))
                .isEqualByComparingTo("3.9167");
    }

    // ---------------------------------------------------------------- grade

    @Test
    void gradeScaleUsesInclusiveFloors() {
        assertThat(PerformanceScoreCalculator.gradeFor(score("5.00")))
                .isEqualTo(PerformanceGrade.OUTSTANDING);
        assertThat(PerformanceScoreCalculator.gradeFor(score("4.50")))
                .isEqualTo(PerformanceGrade.OUTSTANDING);
        assertThat(PerformanceScoreCalculator.gradeFor(score("4.49")))
                .isEqualTo(PerformanceGrade.EXCELLENT);
        assertThat(PerformanceScoreCalculator.gradeFor(score("4.00")))
                .isEqualTo(PerformanceGrade.EXCELLENT);
        assertThat(PerformanceScoreCalculator.gradeFor(score("3.99")))
                .isEqualTo(PerformanceGrade.VERY_GOOD);
        assertThat(PerformanceScoreCalculator.gradeFor(score("3.50")))
                .isEqualTo(PerformanceGrade.VERY_GOOD);
        assertThat(PerformanceScoreCalculator.gradeFor(score("3.49")))
                .isEqualTo(PerformanceGrade.GOOD);
        assertThat(PerformanceScoreCalculator.gradeFor(score("3.00")))
                .isEqualTo(PerformanceGrade.GOOD);
        assertThat(PerformanceScoreCalculator.gradeFor(score("2.99")))
                .isEqualTo(PerformanceGrade.NEEDS_IMPROVEMENT);
        assertThat(PerformanceScoreCalculator.gradeFor(score("2.00")))
                .isEqualTo(PerformanceGrade.NEEDS_IMPROVEMENT);
        assertThat(PerformanceScoreCalculator.gradeFor(score("1.99")))
                .isEqualTo(PerformanceGrade.UNSATISFACTORY);
        assertThat(PerformanceScoreCalculator.gradeFor(score("1.00")))
                .isEqualTo(PerformanceGrade.UNSATISFACTORY);
    }

    private static EmployeeKpi kpi(String weightage) {
        EmployeeKpi kpi = new EmployeeKpi();
        kpi.setWeightage(weightage == null ? null : new BigDecimal(weightage));
        return kpi;
    }

    private static BigDecimal score(String value) {
        return new BigDecimal(value);
    }

    private static BigDecimal weight(String value) {
        return new BigDecimal(value);
    }
}
