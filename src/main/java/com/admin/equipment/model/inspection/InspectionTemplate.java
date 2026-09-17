package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 巡检模板的业务身份。编号(code)唯一且长期不变，具体的检查项目不直接挂在模板上，
 * 而是通过 {@link InspectionTemplateVersion} 以“发布版本 + 完整快照”的方式保存。
 *
 * 生命周期：draft（从未发布）→ active（至少发布过一个版本）→ disabled（停用）。
 * 草稿内容存于 {@link #draftJson}，发布后清空，任何历史版本都不再随草稿变化。
 */
@Entity
@Table(name = "inspection_templates")
public class InspectionTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "equipment_type", length = 32)
    private String equipmentType = "";

    @Column(length = 512)
    private String description = "";

    /** draft / active / disabled。列允许 NULL，仅为兼容 ddl-auto=update 给存量表加列；空值按 draft 处理。 */
    @Column(length = 16)
    private String status = "draft";

    /** 当前发布版本号；从未发布时为 null。计划默认引用它，发布成功后递增。 */
    @Column(name = "current_version")
    private Integer currentVersion;

    /** 草稿项目快照（JSON 文本），发布后置空。 */
    @Lob
    @Column(name = "draft_json")
    private String draftJson;

    @Column(name = "draft_updated_at")
    private LocalDateTime draftUpdatedAt;

    @Column(name = "draft_updated_by", length = 64)
    private String draftUpdatedBy = "";

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEquipmentType() { return equipmentType; }
    public void setEquipmentType(String equipmentType) { this.equipmentType = equipmentType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(Integer currentVersion) { this.currentVersion = currentVersion; }
    public String getDraftJson() { return draftJson; }
    public void setDraftJson(String draftJson) { this.draftJson = draftJson; }
    public LocalDateTime getDraftUpdatedAt() { return draftUpdatedAt; }
    public void setDraftUpdatedAt(LocalDateTime draftUpdatedAt) { this.draftUpdatedAt = draftUpdatedAt; }
    public String getDraftUpdatedBy() { return draftUpdatedBy; }
    public void setDraftUpdatedBy(String draftUpdatedBy) { this.draftUpdatedBy = draftUpdatedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
