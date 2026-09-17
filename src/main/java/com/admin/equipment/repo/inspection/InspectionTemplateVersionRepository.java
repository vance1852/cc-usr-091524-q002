package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.InspectionTemplateVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InspectionTemplateVersionRepository extends JpaRepository<InspectionTemplateVersion, Long> {
    List<InspectionTemplateVersion> findByTemplateIdOrderByVersionNoDesc(Long templateId);
    List<InspectionTemplateVersion> findByTemplateId(Long templateId);
    boolean existsByTemplateId(Long templateId);
    Optional<InspectionTemplateVersion> findFirstByTemplateIdAndStatusOrderByIdDesc(Long templateId, String status);
    Optional<InspectionTemplateVersion> findFirstByTemplateIdAndStatusOrderByVersionNoDesc(Long templateId, String status);

    @Query("select coalesce(max(v.versionNo), 0) from InspectionTemplateVersion v where v.templateId = :templateId")
    int maxVersionNo(@Param("templateId") Long templateId);
}
