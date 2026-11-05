package com.evalora.backend.repository;

import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByUser_Id(Long userId);

    Optional<Employee> findByEmployeeCodeIgnoreCase(String employeeCode);

    boolean existsByEmployeeCodeIgnoreCase(String employeeCode);

    List<Employee> findByManager_Id(Long managerId);

    List<Employee> findByStatus(StaffStatus status);

    long countByStatus(StaffStatus status);

    /** Active employees of one role, for the HR listings. */
    @EntityGraph(attributePaths = { "user", "manager" })
    List<Employee> findByUser_RoleAndStatusOrderByFullNameAsc(Role role, StaffStatus status);

    /** Detail lookup that also proves the profile has the expected role. */
    @EntityGraph(attributePaths = { "user", "manager" })
    Optional<Employee> findByIdAndUser_Role(Long id, Role role);

    /** Active direct reports of a manager, used before deactivating the manager. */
    long countByManager_IdAndStatus(Long managerId, StaffStatus status);

    @Query("""
            select count(e.id) from Employee e
            where e.user.role = :role
              and e.status = :status
              and e.user.enabled = :enabled
            """)
    long countByRoleAndStatusAndUserEnabled(@Param("role") Role role,
                                            @Param("status") StaffStatus status,
                                            @Param("enabled") boolean enabled);

    @Query("""
            select count(e.id) from Employee e
            where e.manager.id = :managerId
              and e.user.role = :role
              and e.status = :status
              and e.user.enabled = :enabled
            """)
    long countDirectReportsByManagerAndRoleAndStatusAndUserEnabled(@Param("managerId") Long managerId,
                                                                   @Param("role") Role role,
                                                                   @Param("status") StaffStatus status,
                                                                   @Param("enabled") boolean enabled);

    /**
     * Locks the profile row for the rest of the transaction. Manager assignment
     * and account-state changes use this so two concurrent operations on the
     * same profile cannot interleave.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Employee e where e.id = :id")
    Optional<Employee> findByIdForUpdate(@Param("id") Long id);

    /**
     * Active direct reports of one manager, for the manager dashboard. Uses
     * exactly the same predicates as
     * {@link #countDirectReportsByManagerAndRoleAndStatusAndUserEnabled}, so
     * the widget count and its list agree.
     */
    @EntityGraph(attributePaths = { "user", "manager" })
    @Query("""
            select e from Employee e
            where e.manager.id = :managerId
              and e.user.role = :role
              and e.status = :status
              and e.user.enabled = :enabled
            order by e.fullName asc, e.id asc
            """)
    List<Employee> findDirectReportsByManagerAndRoleAndStatusAndUserEnabled(
            @Param("managerId") Long managerId,
            @Param("role") Role role,
            @Param("status") StaffStatus status,
            @Param("enabled") boolean enabled);
}
