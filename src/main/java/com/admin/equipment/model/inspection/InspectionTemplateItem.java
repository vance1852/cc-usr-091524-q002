package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 模板版本中的单个检查项目，归属于某个不可变的 {@link InspectionTemplateVersion}。
 * 行本身也不再被修改：需要调整项目时，应修改草稿后发布一个新版本，
 * 新版本复制全部项目并赋予新的 versionId，旧项目原样保留以供历史记录追溯。
 */
@Entity
@Table(name = "inspection_template_items")
public class InspectionTemplateItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 允许 NULL 仅为兼容 ddl-auto=update 给存量 items 表加列；迁移后及新数据必有值。 */
    @Column(name = "version_id")
    private Long versionId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 16)
    private String type = "option";

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "normal_min")
    private Double normalMin;

    @Column(name = "normal_max")
    private Double normalMax;

    @Column(name = "qualified_options", length = 512)
    private String qualifiedOptions = "";

    @Column(name = "judge_criteria", length = 512)
    private String judgeCriteria = "";

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Double getNormalMin() { return normalMin; }
    public void setNormalMin(Double normalMin) { this.normalMin = normalMin; }
    public Double getNormalMax() { return normalMax; }
    public void setNormalMax(Double normalMax) { this.normalMax = normalMax; }
    public String getQualifiedOptions() { return qualifiedOptions; }
    public void setQualifiedOptions(String qualifiedOptions) { this.qualifiedOptions = qualifiedOptions; }
    public String getJudgeCriteria() { return judgeCriteria; }
    public void setJudgeCriteria(String judgeCriteria) { this.judgeCriteria = judgeCriteria; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
