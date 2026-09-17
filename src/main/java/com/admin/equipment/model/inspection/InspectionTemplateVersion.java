package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 巡检模板版本。模板（inspection_templates）承载业务身份，
 * 每次发布生成一个不可修改的版本，版本内包含完整项目快照。
 * 状态流转：draft（草稿） -> published（已发布） -> deprecated（已停用）。
 * 同一模板同一时刻最多一个 published 版本；草稿最多一个，versionNo 在发布时分配。
 */
@Entity
@Table(name = "inspection_template_versions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"template_id", "version_no"}))
public class InspectionTemplateVersion {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_DEPRECATED = "deprecated";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "version_no")
    private Integer versionNo;

    @Column(nullable = false, length = 16)
    private String status = STATUS_DRAFT;

    @Column(nullable = false, length = 128)
    private String name = "";

    @Column(name = "equipment_type", length = 32)
    private String equipmentType = "";

    @Column(length = 512)
    private String description = "";

    @Column(name = "published_by", length = 64)
    private String publishedBy = "";

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEquipmentType() { return equipmentType; }
    public void setEquipmentType(String equipmentType) { this.equipmentType = equipmentType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPublishedBy() { return publishedBy; }
    public void setPublishedBy(String publishedBy) { this.publishedBy = publishedBy; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
