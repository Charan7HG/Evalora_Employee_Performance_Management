package com.evalora.backend.repository;

import com.evalora.backend.entity.PerformanceResultItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PerformanceResultItemRepository extends JpaRepository<PerformanceResultItem, Long> {

    List<PerformanceResultItem> findByResult_Id(Long resultId);

    List<PerformanceResultItem> findByResult_IdOrderByEmployeeKpi_IdAsc(Long resultId);

    long countByResult_Id(Long resultId);
}
