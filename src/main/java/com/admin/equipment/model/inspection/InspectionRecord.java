package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "inspection_records")
public class InspectionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "task_point_id", nullable = false)
    private Long taskPointId;

    @Column(name = "point_id", nullable = false)
    private Long pointId;

    @Column(name = "template_item_id", nullable = false)
    private Long templateItemId;

    /** 生成记录时项目所属的冻结版本，与任务上的版本一致，便于单条记录独立追溯。 */
    @Column(name = "template_version_id")
    private Long templateVersionId;

    @Column(name = "template_version_no")
    private Integer templateVersionNo;

    @Column(name = "item_name", length = 128)
    private String itemName = "";

    @Column(name = "item_type", length = 16)
    private String itemType = "option";

    /* ---- 判定时刻的项目定义/阈值快照：版本不可变，这里再冗余一份以支持直接展示 ---- */

    @Column(name = "normal_min")
    private Double normalMin;

    @Column(name = "normal_max")
    private Double normalMax;

    @Column(name = "qualified_options", length = 512)
    private String qualifiedOptions = "";

    @Column(name = "judge_criteria", length = 512)
    private String judgeCriteria = "";

    @Column(name = "check_value", length = 256)
    private String checkValue = "";

    @Column(name = "check_numeric")
    private Double checkNumeric;

    @Column(name = "is_qualified")
    private Boolean isQualified = true;

    @Column(name = "is_abnormal")
    private Boolean isAbnormal = false;

    @Column(name = "judge_detail", length = 512)
    private String judgeDetail = "";

    @Column(name = "recorded_at")
    private LocalDateTime recordedAt;

    @Column(name = "recorded_by", length = 64)
    private String recordedBy = "";

    @Column(length = 512)
    private String remark = "";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getTaskPointId() { return taskPointId; }
    public void setTaskPointId(Long taskPointId) { this.taskPointId = taskPointId; }
    public Long getPointId() { return pointId; }
    public void setPointId(Long pointId) { this.pointId = pointId; }
    public Long getTemplateItemId() { return templateItemId; }
    public void setTemplateItemId(Long templateItemId) { this.templateItemId = templateItemId; }
    public Long getTemplateVersionId() { return templateVersionId; }
    public void setTemplateVersionId(Long templateVersionId) { this.templateVersionId = templateVersionId; }
    public Integer getTemplateVersionNo() { return templateVersionNo; }
    public void setTemplateVersionNo(Integer templateVersionNo) { this.templateVersionNo = templateVersionNo; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public String getItemType() { return itemType; }
    public void setItemType(String itemType) { this.itemType = itemType; }
    public Double getNormalMin() { return normalMin; }
    public void setNormalMin(Double normalMin) { this.normalMin = normalMin; }
    public Double getNormalMax() { return normalMax; }
    public void setNormalMax(Double normalMax) { this.normalMax = normalMax; }
    public String getQualifiedOptions() { return qualifiedOptions; }
    public void setQualifiedOptions(String qualifiedOptions) { this.qualifiedOptions = qualifiedOptions; }
    public String getJudgeCriteria() { return judgeCriteria; }
    public void setJudgeCriteria(String judgeCriteria) { this.judgeCriteria = judgeCriteria; }
    public String getCheckValue() { return checkValue; }
    public void setCheckValue(String checkValue) { this.checkValue = checkValue; }
    public Double getCheckNumeric() { return checkNumeric; }
    public void setCheckNumeric(Double checkNumeric) { this.checkNumeric = checkNumeric; }
    public Boolean getIsQualified() { return isQualified; }
    public void setIsQualified(Boolean isQualified) { this.isQualified = isQualified; }
    public Boolean getIsAbnormal() { return isAbnormal; }
    public void setIsAbnormal(Boolean isAbnormal) { this.isAbnormal = isAbnormal; }
    public String getJudgeDetail() { return judgeDetail; }
    public void setJudgeDetail(String judgeDetail) { this.judgeDetail = judgeDetail; }
    public LocalDateTime getRecordedAt() { return recordedAt; }
    public void setRecordedAt(LocalDateTime recordedAt) { this.recordedAt = recordedAt; }
    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String recordedBy) { this.recordedBy = recordedBy; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
