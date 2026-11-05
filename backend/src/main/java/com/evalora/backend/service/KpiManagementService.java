package com.evalora.backend.service;

import com.evalora.backend.dto.CreateKpiRequest;
import com.evalora.backend.dto.KpiResponse;
import com.evalora.backend.dto.UpdateKpiRequest;
import com.evalora.backend.entity.KpiMaster;
import com.evalora.backend.entity.enums.KpiType;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.exception.HrErrorType;
import com.evalora.backend.repository.KpiMasterRepository;
import com.evalora.backend.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * HR-side management of the reusable KPI catalogue.
 *
 * <p>KPIs are never hard deleted: deactivation only flips {@code active}.
 * Historical copies in {@code cycle_kpis} and {@code employee_kpis} are
 * snapshots, so editing or deactivating a master KPI never rewrites them.</p>
 *
 * <p>Names are unique case-insensitively. The service pre-checks, and the
 * database functional index on {@code LOWER(name)} is the final protection for
 * concurrent requests; a uniqueness violation is translated to a conflict.</p>
 */
@Service
public class KpiManagementService {

    private static final int MAX_NAME_LENGTH = 150;
    private static final int MAX_CATEGORY_LENGTH = 80;
    private static final BigDecimal MAX_WEIGHTAGE = new BigDecimal("100");

    private final KpiMasterRepository kpiRepository;
    private final UserRepository userRepository;

    public KpiManagementService(KpiMasterRepository kpiRepository, UserRepository userRepository) {
        this.kpiRepository = kpiRepository;
        this.userRepository = userRepository;
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public KpiResponse create(CreateKpiRequest request, Long hrUserId) {
        KpiMaster kpi = new KpiMaster();
        kpi.setName(normalizeName(request.name()));
        kpi.setDescription(normalizeOptional(request.description()));
        kpi.setCategory(normalizeCategory(request.category()));
        kpi.setKpiType(request.kpiType() == null ? KpiType.MANAGER : request.kpiType());
        kpi.setDefaultWeightage(normalizeWeightage(request.defaultWeightage()));
        kpi.setActive(true);
        kpi.setCreatedBy(userRepository.getReferenceById(hrUserId));

        if (kpiRepository.existsByNameIgnoreCase(kpi.getName())) {
            throw conflict("A KPI with this name already exists.");
        }
        return toResponse(save(kpi));
    }

    // -------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public List<KpiResponse> list(Boolean active) {
        List<KpiMaster> kpis = active == null
                ? kpiRepository.findAllByOrderByIdDesc()
                : kpiRepository.findByActiveOrderByIdDesc(active);
        return kpis.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public KpiResponse get(Long id) {
        return toResponse(require(id));
    }

    // ------------------------------------------------------------------ update

    @Transactional
    public KpiResponse update(Long id, UpdateKpiRequest request) {
        KpiMaster kpi = require(id);

        String name = normalizeName(request.name());
        if (kpiRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw conflict("A KPI with this name already exists.");
        }

        kpi.setName(name);
        kpi.setDescription(normalizeOptional(request.description()));
        kpi.setCategory(normalizeCategory(request.category()));
        // A request without a type keeps the stored classification.
        kpi.setKpiType(request.kpiType() == null ? kpi.getKpiType() : request.kpiType());
        kpi.setDefaultWeightage(normalizeWeightage(request.defaultWeightage()));
        return toResponse(save(kpi));
    }

    // ------------------------------------------------------ activate/deactivate

    @Transactional
    public KpiResponse deactivate(Long id) {
        return changeActive(id, false);
    }

    @Transactional
    public KpiResponse reactivate(Long id) {
        return changeActive(id, true);
    }

    private KpiResponse changeActive(Long id, boolean active) {
        KpiMaster kpi = require(id);
        if (kpi.isActive() == active) {
            throw conflict(active ? "KPI is already active." : "KPI is already inactive.");
        }
        kpi.setActive(active);
        return toResponse(save(kpi));
    }

    // ----------------------------------------------------------------- helpers

    private KpiMaster require(Long id) {
        return kpiRepository.findById(id)
                .orElseThrow(() -> new HrApiException(HrErrorType.NOT_FOUND, "KPI not found."));
    }

    /**
     * Flushes immediately so a concurrent duplicate is reported as a conflict
     * rather than surfacing on transaction commit.
     */
    private KpiMaster save(KpiMaster kpi) {
        try {
            return kpiRepository.saveAndFlush(kpi);
        } catch (DataIntegrityViolationException ex) {
            throw conflict("A KPI with this name already exists.");
        }
    }

    private KpiResponse toResponse(KpiMaster kpi) {
        return new KpiResponse(
                kpi.getId(),
                kpi.getName(),
                kpi.getDescription(),
                kpi.getCategory(),
                kpi.getKpiType() == null ? KpiType.MANAGER.name() : kpi.getKpiType().name(),
                kpi.getDefaultWeightage(),
                kpi.isActive(),
                kpi.getCreatedBy() == null ? null : kpi.getCreatedBy().getId(),
                kpi.getCreatedAt(),
                kpi.getUpdatedAt());
    }

    private static String normalizeName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw invalid("KPI name is required.");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw invalid("KPI name must be at most " + MAX_NAME_LENGTH + " characters.");
        }
        return trimmed;
    }

    private static String normalizeCategory(String category) {
        String trimmed = normalizeOptional(category);
        if (trimmed != null && trimmed.length() > MAX_CATEGORY_LENGTH) {
            throw invalid("Category must be at most " + MAX_CATEGORY_LENGTH + " characters.");
        }
        return trimmed;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BigDecimal normalizeWeightage(BigDecimal weightage) {
        if (weightage == null) {
            return null;
        }
        if (weightage.compareTo(BigDecimal.ZERO) <= 0 || weightage.compareTo(MAX_WEIGHTAGE) > 0) {
            throw invalid("Default weightage must be greater than 0 and at most 100.");
        }
        if (weightage.stripTrailingZeros().scale() > 2) {
            throw invalid("Default weightage must have at most 2 decimal places.");
        }
        return weightage.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static HrApiException conflict(String message) {
        return new HrApiException(HrErrorType.CONFLICT, message);
    }

    private static HrApiException invalid(String message) {
        return new HrApiException(HrErrorType.INVALID_REQUEST, message);
    }
}
