package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.InspectionTemplate;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InspectionTemplateRepository extends JpaRepository<InspectionTemplate, Long> {
    boolean existsByCode(String code);
    Optional<InspectionTemplate> findByCode(String code);
    List<InspectionTemplate> findAllByOrderByCodeAsc();
    List<InspectionTemplate> findByEquipmentTypeOrderByCodeAsc(String equipmentType);

    /** 发布流程串行化：对模板行加悲观写锁，保证并发发布只有一个递增版本成功。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from InspectionTemplate t where t.id = :id")
    Optional<InspectionTemplate> findForUpdate(@Param("id") Long id);
}
