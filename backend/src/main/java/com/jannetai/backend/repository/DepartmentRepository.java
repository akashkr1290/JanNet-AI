package com.jannetai.backend.repository;

import com.jannetai.backend.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Phase 3 skeleton (plain CRUD access only), extended Phase 11.
 */
@Repository
public interface DepartmentRepository extends JpaRepository<Department, Long> {

    /**
     * Phase 11 (SRS 15.7 Validation Rules: "unmapped categories default
     * to a configurable 'General Civic Issues' department"). V15 seeded
     * this fallback department under the name "General Triage" (see that
     * migration's own header comment) - the app.department-assignment.
     * fallback-department-name property (default "General Triage") is
     * this lookup's key, kept name-based (not a hardcoded department_id)
     * so the mapping stays correct even if a real deployment's seed data
     * uses a different literal name.
     */
    // V34: names repeat per area ("Public Works" for Gaya and for Ghaziabad), so
    // this lookup (routing rules, fallback department) means the GLOBAL one.
    @org.springframework.data.jpa.repository.Query("SELECT d FROM Department d WHERE d.name = :name AND d.isActive = true "
            + "AND d.districtCode IS NULL AND d.localBodyCode IS NULL")
    Optional<Department> findByNameAndIsActiveTrue(@org.springframework.data.repository.query.Param("name") String name);

    /**
     * V34 / V37 area routing (DepartmentAssignmentService#forArea): the
     * same-named department of the complaint's local body, then of its block,
     * then of its whole district.
     */
    Optional<Department> findFirstByNameAndLocalBodyCodeAndIsActiveTrue(String name, Integer localBodyCode);

    Optional<Department> findFirstByNameAndBlockCodeAndLocalBodyCodeIsNullAndIsActiveTrue(String name, Integer blockCode);

    Optional<Department> findFirstByNameAndDistrictCodeAndBlockCodeIsNullAndLocalBodyCodeIsNullAndIsActiveTrue(
            String name, Integer districtCode);

    /** V34 / V37 Admin uniqueness: one name per area (uq_departments_name_scope). Null codes match IS NULL. */
    Optional<Department> findFirstByNameAndDistrictCodeAndBlockCodeAndLocalBodyCode(String name, Integer districtCode,
                                                                                   Integer blockCode, Integer localBodyCode);

    List<Department> findByIsActiveTrueOrderByNameAsc();

    /** Audit GAP-020: Admin department configuration (all rows, uniqueness on uq_departments_name). */
    List<Department> findAllByOrderByNameAsc();

    Optional<Department> findFirstByName(String name);
}
