package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.InspectionTemplateVersion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InspectionTemplateVersionRepository extends JpaRepository<InspectionTemplateVersion, Long> {

    List<InspectionTemplateVersion> findByTemplateIdOrderByVersionNoDesc(Long templateId);

    Optional<InspectionTemplateVersion> findByTemplateIdAndVersionNo(Long templateId, Integer versionNo);

    /** 取当前发布版：同一模板下版本号最大的 published 版本。 */
    @Query("select v from InspectionTemplateVersion v where v.templateId = :templateId and v.status = 'published' "
            + "and v.versionNo = (select max(v2.versionNo) from InspectionTemplateVersion v2 "
            + "where v2.templateId = :templateId and v2.status = 'published')")
    Optional<InspectionTemplateVersion> findCurrent(@Param("templateId") Long templateId);

    /**
     * 并发发布用：对模板行加行级悲观写锁，保证同一模板同时只有一个发布事务能推进版本号。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from InspectionTemplate t where t.id = :id")
    Optional<com.admin.equipment.model.inspection.InspectionTemplate> lockById(@Param("id") Long id);
}
