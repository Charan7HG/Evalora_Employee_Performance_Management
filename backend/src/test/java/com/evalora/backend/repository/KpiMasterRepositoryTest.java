package com.evalora.backend.repository;

import com.evalora.backend.entity.KpiMaster;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mapping and constraint tests for the kpi_master table.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class KpiMasterRepositoryTest {

    @Autowired
    private KpiMasterRepository kpiMasterRepository;

    private KpiMaster newKpi(String name, String weight) {
        KpiMaster kpi = new KpiMaster();
        kpi.setName(name);
        kpi.setDescription("Measured over the review period.");
        kpi.setCategory("Delivery");
        kpi.setDefaultWeightage(weight == null ? null : new BigDecimal(weight));
        return kpi;
    }

    @Test
    void savesKpiAndAppliesDefaultActiveFlag() {
        KpiMaster saved = kpiMasterRepository.saveAndFlush(newKpi("Delivery Quality", "25.00"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.isActive()).isTrue();
        assertThat(kpiMasterRepository.findByActiveTrue())
                .extracting(KpiMaster::getName)
                .containsExactly("Delivery Quality");
    }

    @Test
    void weightageKeepsTwoDecimalPrecision() {
        kpiMasterRepository.saveAndFlush(newKpi("Timeliness", "12.50"));

        BigDecimal weightage = kpiMasterRepository.findByNameIgnoreCase("timeliness")
                .orElseThrow()
                .getDefaultWeightage();

        assertThat(weightage).isEqualByComparingTo("12.50");
        assertThat(weightage.scale()).isEqualTo(2);
    }

    @Test
    void nameMustBeUnique() {
        kpiMasterRepository.saveAndFlush(newKpi("Ownership", "20.00"));

        assertThatThrownBy(() -> kpiMasterRepository.saveAndFlush(newKpi("Ownership", "30.00")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findsKpiByNameIgnoringCase() {
        kpiMasterRepository.saveAndFlush(newKpi("Customer Focus", null));

        assertThat(kpiMasterRepository.existsByNameIgnoreCase("CUSTOMER FOCUS")).isTrue();
    }

    @Test
    void kpiCanBeSoftDeletedByDeactivatingIt() {
        KpiMaster kpi = kpiMasterRepository.saveAndFlush(newKpi("Collaboration", "15.00"));
        kpi.setActive(false);
        kpiMasterRepository.saveAndFlush(kpi);

        assertThat(kpiMasterRepository.findById(kpi.getId())).isPresent();
        assertThat(kpiMasterRepository.findByActiveTrue()).isEmpty();
    }
}
