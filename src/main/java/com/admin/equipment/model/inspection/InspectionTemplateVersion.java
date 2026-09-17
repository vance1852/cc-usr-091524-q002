package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 模板的一次不可变发布版本。版本一旦创建即冻结：
 * 名称/设备类型等头信息与全部检查项目（见 {@link InspectionTemplateItem}）构成完整快照，
 * 之后再编辑草稿、发布新版本都不会改变历史版本，保证历史任务可追溯当时的判定标准。
 *
 * status：published（当前或历史发布版）→ retired（随模板停用）。停用不删除任何数据。
 */
@Entity
@Table(
    name = "inspection_template_versions",
    uniqueConstraints = @UniqueConstraint(name = "uk_tpl_version_no",
        columnNames = {"template_id", "version_no"})
)
public class InspectionTemplateVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "equipment_type", length = 32)
    private String equipmentType = "";

    @Column(length = 512)
    private String description = "";

    /** published / retired */
    @Column(length = 16, nullable = false)
    private String status = "published";

    @Column(name = "item_count", nullable = false)
    private Integer itemCount = 0;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;

    @Column(name = "published_by", length = 64)
    private String publishedBy = "";

    /** 发布时填写的变更说明 */
    @Column(name = "change_summary", length = 512)
    private String changeSummary = "";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEquipmentType() { return equipmentType; }
    public void setEquipmentType(String equipmentType) { this.equipmentType = equipmentType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public String getPublishedBy() { return publishedBy; }
    public void setPublishedBy(String publishedBy) { this.publishedBy = publishedBy; }
    public String getChangeSummary() { return changeSummary; }
    public void setChangeSummary(String changeSummary) { this.changeSummary = changeSummary; }
}
