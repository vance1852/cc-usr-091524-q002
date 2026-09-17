package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.InspectionTemplateVersionItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InspectionTemplateVersionItemRepository extends JpaRepository<InspectionTemplateVersionItem, Long> {
    List<InspectionTemplateVersionItem> findByVersionIdOrderBySortOrderAsc(Long versionId);
    void deleteByVersionId(Long versionId);
    long countByVersionId(Long versionId);
}
