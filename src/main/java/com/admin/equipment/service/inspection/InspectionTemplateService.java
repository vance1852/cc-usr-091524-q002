package com.admin.equipment.service.inspection;

import com.admin.equipment.model.inspection.InspectionRecord;
import com.admin.equipment.model.inspection.InspectionTemplate;
import com.admin.equipment.model.inspection.InspectionTemplateItem;
import com.admin.equipment.model.inspection.InspectionTemplateVersion;
import com.admin.equipment.repo.inspection.InspectionRecordRepository;
import com.admin.equipment.repo.inspection.InspectionPlanRepository;
import com.admin.equipment.repo.inspection.InspectionTaskRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateItemRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateVersionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class InspectionTemplateService {

    private final InspectionTemplateRepository templateRepo;
    private final InspectionTemplateVersionRepository versionRepo;
    private final InspectionTemplateItemRepository itemRepo;
    private final InspectionPlanRepository planRepo;
    private final InspectionTaskRepository taskRepo;
    private final InspectionRecordRepository recordRepo;
    private final ObjectMapper objectMapper;

    public InspectionTemplateService(InspectionTemplateRepository templateRepo,
                                      InspectionTemplateVersionRepository versionRepo,
                                      InspectionTemplateItemRepository itemRepo,
                                      InspectionPlanRepository planRepo,
                                      InspectionTaskRepository taskRepo,
                                      InspectionRecordRepository recordRepo,
                                      ObjectMapper objectMapper) {
        this.templateRepo = templateRepo;
        this.versionRepo = versionRepo;
        this.itemRepo = itemRepo;
        this.planRepo = planRepo;
        this.taskRepo = taskRepo;
        this.recordRepo = recordRepo;
        this.objectMapper = objectMapper;
    }

    /* ============================ 查询 ============================ */

    public List<InspectionTemplate> listAll() {
        return templateRepo.findAllByOrderByCodeAsc();
    }

    public List<InspectionTemplate> listByEquipmentType(String type) {
        return templateRepo.findByEquipmentTypeOrderByCodeAsc(type);
    }

    public Optional<InspectionTemplate> getById(Long id) {
        return templateRepo.findById(id);
    }

    /** 模板的全部发布版本，版本号倒序。 */
    public List<InspectionTemplateVersion> listVersions(Long templateId) {
        return versionRepo.findByTemplateIdOrderByVersionNoDesc(templateId);
    }

    public Optional<InspectionTemplateVersion> getVersion(Long versionId) {
        return versionRepo.findById(versionId);
    }

    public Optional<InspectionTemplateVersion> getVersionByNo(Long templateId, Integer versionNo) {
        return versionRepo.findByTemplateIdAndVersionNo(templateId, versionNo);
    }

    public Optional<InspectionTemplateVersion> getCurrentVersion(Long templateId) {
        return versionRepo.findCurrent(templateId);
    }

    /** 不可变版本下的完整项目快照，按顺序返回。 */
    public List<InspectionTemplateItem> getVersionItems(Long versionId) {
        return itemRepo.findByVersionIdOrderBySortOrderAsc(versionId);
    }

    /**
     * 兼容旧调用方：按模板取“当前发布版”的项目。任务执行等场景应改用任务冻结的 versionId。
     */
    public List<InspectionTemplateItem> getItems(Long templateId) {
        return getCurrentVersion(templateId)
                .map(v -> itemRepo.findByVersionIdOrderBySortOrderAsc(v.getId()))
                .orElseGet(ArrayList::new);
    }

    public DraftContent getDraft(Long templateId) {
        InspectionTemplate t = mustGetTemplate(templateId);
        if (t.getDraftJson() == null || t.getDraftJson().isBlank()) {
            return new DraftContent(t.getName(), t.getEquipmentType(), t.getDescription(),
                    Collections.emptyList(), t.getDraftUpdatedAt(), t.getDraftUpdatedBy());
        }
        return readDraft(t);
    }

    /* ============================ 草稿维护 ============================ */

    public record ItemSpec(String name, String type, Double normalMin, Double normalMax,
                           String qualifiedOptions, String judgeCriteria, Integer sortOrder) {}

    /** 草稿内容（不落业务表，序列化为 JSON 存模板行）。 */
    public record DraftContent(String name, String equipmentType, String description,
                                List<ItemSpec> items, LocalDateTime updatedAt, String updatedBy) {}

    @Transactional
    public InspectionTemplate create(String code, String name, String equipmentType, String description,
                                      List<ItemSpec> items, String operator) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("编号必填");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("名称必填");
        if (templateRepo.existsByCode(code)) throw new IllegalArgumentException("编号已存在");

        InspectionTemplate t = new InspectionTemplate();
        t.setCode(code);
        t.setName(name);
        t.setEquipmentType(equipmentType == null ? "" : equipmentType);
        t.setDescription(description == null ? "" : description);
        t.setStatus("draft");
        InspectionTemplate saved = templateRepo.save(t);
        writeDraft(saved, name, t.getEquipmentType(), t.getDescription(),
                items == null ? Collections.emptyList() : items, operator);
        return templateRepo.save(saved);
    }

    /**
     * 保存草稿：只改草稿区，不产生版本，不影响任何计划与历史任务。
     * 草稿允许暂存未完成内容；顺序、上下限、合格选项等完整性在发布时统一校验。
     */
    @Transactional
    public InspectionTemplate saveDraft(Long id, String name, String equipmentType, String description,
                                         List<ItemSpec> items, String operator) {
        InspectionTemplate t = mustGetTemplate(id);
        if ("disabled".equals(t.getStatus())) {
            throw new IllegalArgumentException("模板已停用，请先启用后再编辑");
        }
        if (name == null || name.isBlank()) throw new IllegalArgumentException("名称必填");
        if (items == null) throw new IllegalArgumentException("草稿项目不能为空");
        writeDraft(t, name, equipmentType == null ? "" : equipmentType,
                description == null ? "" : description, items, operator);
        return templateRepo.save(t);
    }

    /** 把某个已发布版本的完整快照复制为草稿，供“基于旧版本调整后再发布”。 */
    @Transactional
    public InspectionTemplate draftFromVersion(Long templateId, Integer versionNo, String operator) {
        InspectionTemplate t = mustGetTemplate(templateId);
        if ("disabled".equals(t.getStatus())) {
            throw new IllegalArgumentException("模板已停用，请先启用后再编辑");
        }
        InspectionTemplateVersion base = (versionNo == null
                ? getCurrentVersion(templateId)
                : getVersionByNo(templateId, versionNo))
                .orElseThrow(() -> new IllegalArgumentException("基准版本不存在"));
        List<ItemSpec> items = new ArrayList<>();
        for (InspectionTemplateItem it : getVersionItems(base.getId())) {
            items.add(new ItemSpec(it.getName(), it.getType(), it.getNormalMin(), it.getNormalMax(),
                    it.getQualifiedOptions(), it.getJudgeCriteria(), it.getSortOrder()));
        }
        writeDraft(t, base.getName(), base.getEquipmentType(), base.getDescription(), items, operator);
        return templateRepo.save(t);
    }

    private void writeDraft(InspectionTemplate t, String name, String equipmentType, String description,
                             List<ItemSpec> items, String operator) {
        // 草稿允许保存未完成的内容；完整性（顺序/上下限/合格选项）统一在发布时校验
        List<ItemSpec> draftItems = items == null ? Collections.emptyList() : items;
        t.setName(name);
        t.setEquipmentType(equipmentType);
        t.setDescription(description);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", name);
            payload.put("equipmentType", equipmentType);
            payload.put("description", description);
            payload.put("items", draftItems);
            t.setDraftJson(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalStateException("草稿序列化失败: " + e.getMessage(), e);
        }
        t.setDraftUpdatedAt(LocalDateTime.now());
        t.setDraftUpdatedBy(operator == null ? "" : operator);
    }

    private DraftContent readDraft(InspectionTemplate t) {
        try {
            Map<String, Object> payload = objectMapper.readValue(t.getDraftJson(),
                    new TypeReference<Map<String, Object>>() {});
            List<ItemSpec> items = objectMapper.convertValue(
                    payload.getOrDefault("items", Collections.emptyList()),
                    new TypeReference<List<ItemSpec>>() {});
            return new DraftContent(
                    (String) payload.getOrDefault("name", t.getName()),
                    (String) payload.getOrDefault("equipmentType", t.getEquipmentType()),
                    (String) payload.getOrDefault("description", t.getDescription()),
                    items, t.getDraftUpdatedAt(), t.getDraftUpdatedBy());
        } catch (Exception e) {
            throw new IllegalStateException("草稿内容无法解析: " + e.getMessage(), e);
        }
    }

    /* ============================ 发布 / 停用 ============================ */

    /**
     * 发布草稿，生成不可变版本。
     *
     * 并发控制：先对模板行加悲观写锁（SELECT ... FOR UPDATE），把“读当前版本号—插入新版本—
     * 清空草稿”串行化。同一草稿的两个并发发布，只有先拿到锁的提交成功；另一个等待后会看到
     * 草稿已清空而失败，因此一次草稿变更只会产生一个递增版本，版本号也绝不会重复。
     * (template_id, version_no) 上还有唯一约束兜底。
     */
    @Transactional
    public InspectionTemplateVersion publish(Long templateId, String changeSummary, String operator) {
        InspectionTemplate t = versionRepo.lockById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在"));
        if ("disabled".equals(t.getStatus())) {
            throw new IllegalArgumentException("模板已停用，请先启用后再发布");
        }
        if (t.getDraftJson() == null || t.getDraftJson().isBlank()) {
            throw new IllegalStateException("草稿为空或已发布，请先修改草稿后再发布");
        }
        DraftContent draft = readDraft(t);
        List<ItemSpec> items = validateAndNormalize(draft.items());

        int nextNo = (t.getCurrentVersion() == null ? 0 : t.getCurrentVersion()) + 1;
        LocalDateTime now = LocalDateTime.now();

        InspectionTemplateVersion v = new InspectionTemplateVersion();
        v.setTemplateId(templateId);
        v.setVersionNo(nextNo);
        v.setName(draft.name());
        v.setEquipmentType(draft.equipmentType() == null ? "" : draft.equipmentType());
        v.setDescription(draft.description() == null ? "" : draft.description());
        v.setStatus("published");
        v.setItemCount(items.size());
        v.setPublishedAt(now);
        v.setPublishedBy(operator == null ? "" : operator);
        v.setChangeSummary(changeSummary == null ? "" : changeSummary);
        InspectionTemplateVersion savedVersion = versionRepo.save(v);

        for (ItemSpec spec : items) {
            InspectionTemplateItem item = new InspectionTemplateItem();
            item.setVersionId(savedVersion.getId());
            item.setName(spec.name());
            item.setType(spec.type());
            item.setSortOrder(spec.sortOrder());
            item.setNormalMin(spec.normalMin());
            item.setNormalMax(spec.normalMax());
            item.setQualifiedOptions(spec.qualifiedOptions() == null ? "" : spec.qualifiedOptions());
            item.setJudgeCriteria(spec.judgeCriteria() == null ? "" : spec.judgeCriteria());
            itemRepo.save(item);
        }

        t.setCurrentVersion(nextNo);
        t.setName(draft.name());
        t.setEquipmentType(v.getEquipmentType());
        t.setDescription(v.getDescription());
        t.setStatus("active");
        t.setDraftJson(null);
        t.setDraftUpdatedAt(null);
        t.setDraftUpdatedBy("");
        templateRepo.save(t);
        return savedVersion;
    }

    /** 停用模板：当前发布版标记 retired，计划不能再引用，历史任务/记录全部保留。 */
    @Transactional
    public InspectionTemplate disable(Long templateId) {
        InspectionTemplate t = mustGetTemplate(templateId);
        if ("disabled".equals(t.getStatus())) {
            throw new IllegalArgumentException("模板已是停用状态");
        }
        if (t.getCurrentVersion() == null) {
            throw new IllegalArgumentException("尚未发布过的模板不能停用，可直接删除");
        }
        // 停用后不应再存在“当前发布版”：所有版本一并 retired（数据保留，状态可恢复）
        List<InspectionTemplateVersion> versions = versionRepo.findByTemplateIdOrderByVersionNoDesc(templateId);
        for (InspectionTemplateVersion v : versions) {
            if ("published".equals(v.getStatus())) {
                v.setStatus("retired");
                versionRepo.save(v);
            }
        }
        t.setStatus("disabled");
        return templateRepo.save(t);
    }

    /** 重新启用：仅恢复最新版本为当前发布版，其余保持 retired（只代表历史）。 */
    @Transactional
    public InspectionTemplate enable(Long templateId) {
        InspectionTemplate t = mustGetTemplate(templateId);
        if (!"disabled".equals(t.getStatus())) {
            throw new IllegalArgumentException("模板不是停用状态");
        }
        List<InspectionTemplateVersion> versions = versionRepo.findByTemplateIdOrderByVersionNoDesc(templateId);
        if (versions.isEmpty()) throw new IllegalStateException("模板没有任何发布版本");
        InspectionTemplateVersion latest = versions.get(0);
        latest.setStatus("published");
        versionRepo.save(latest);
        t.setStatus("active");
        return templateRepo.save(t);
    }

    /**
     * 物理删除只允许“从未发布、也未被计划引用”的模板。
     * 已发布版本被计划引用或已有任务/记录时一律拒绝——版本数据只追加、不删除。
     */
    @Transactional
    public void delete(Long id) {
        InspectionTemplate t = templateRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在"));
        List<InspectionTemplateVersion> versions = versionRepo.findByTemplateIdOrderByVersionNoDesc(id);
        if (!versions.isEmpty()) {
            throw new IllegalArgumentException("模板存在发布版本，不可物理删除；如不再使用请停用");
        }
        if (!planRepo.findByTemplateId(id).isEmpty()) {
            throw new IllegalArgumentException("模板已被巡检计划引用，不可删除");
        }
        templateRepo.deleteById(id);
    }

    /* ============================ 版本差异与引用关系 ============================ */

    public record FieldChange(String field, Object from, Object to) {}
    public record ItemChange(String changeType, String itemName, List<FieldChange> changes) {}
    public record VersionDiff(Long templateId, String templateCode,
                               Integer fromVersionNo, Integer toVersionNo,
                               List<FieldChange> headerChanges, List<ItemChange> itemChanges) {}

    /** 比较两个已发布版本：头信息变化 + 项目的新增/删除/修改（以版本内唯一的项目名称匹配）。 */
    public VersionDiff diff(Long templateId, Integer fromVersionNo, Integer toVersionNo) {
        InspectionTemplateVersion from = getVersionByNo(templateId, fromVersionNo)
                .orElseThrow(() -> new IllegalArgumentException("起始版本不存在: " + fromVersionNo));
        InspectionTemplateVersion to = getVersionByNo(templateId, toVersionNo)
                .orElseThrow(() -> new IllegalArgumentException("目标版本不存在: " + toVersionNo));

        List<FieldChange> header = new ArrayList<>();
        addChange(header, "name", from.getName(), to.getName());
        addChange(header, "equipmentType", from.getEquipmentType(), to.getEquipmentType());
        addChange(header, "description", from.getDescription(), to.getDescription());
        addChange(header, "changeSummary", from.getChangeSummary(), to.getChangeSummary());

        Map<String, InspectionTemplateItem> fromMap = mapByName(getVersionItems(from.getId()));
        Map<String, InspectionTemplateItem> toMap = mapByName(getVersionItems(to.getId()));
        List<ItemChange> itemChanges = new ArrayList<>();

        // 按目标版本顺序输出新增与修改
        for (Map.Entry<String, InspectionTemplateItem> e : toMap.entrySet()) {
            InspectionTemplateItem old = fromMap.get(e.getKey());
            if (old == null) {
                itemChanges.add(new ItemChange("added", e.getKey(), Collections.emptyList()));
            } else {
                List<FieldChange> ch = new ArrayList<>();
                addChange(ch, "type", old.getType(), e.getValue().getType());
                addChange(ch, "sortOrder", old.getSortOrder(), e.getValue().getSortOrder());
                addChange(ch, "normalMin", old.getNormalMin(), e.getValue().getNormalMin());
                addChange(ch, "normalMax", old.getNormalMax(), e.getValue().getNormalMax());
                addChange(ch, "qualifiedOptions", old.getQualifiedOptions(), e.getValue().getQualifiedOptions());
                addChange(ch, "judgeCriteria", old.getJudgeCriteria(), e.getValue().getJudgeCriteria());
                if (!ch.isEmpty()) itemChanges.add(new ItemChange("modified", e.getKey(), ch));
            }
        }
        for (String name : fromMap.keySet()) {
            if (!toMap.containsKey(name)) {
                itemChanges.add(new ItemChange("removed", name, Collections.emptyList()));
            }
        }

        return new VersionDiff(templateId, mustGetTemplate(templateId).getCode(),
                fromVersionNo, toVersionNo, header, itemChanges);
    }

    public record VersionReferences(Long versionId, Long templateId, Integer versionNo,
                                     boolean current, long planReferences, long effectivePlanReferences,
                                     long taskCount, long recordCount, boolean deletable) {}

    /** 查看一个版本被哪些业务对象引用：计划（显式/跟随当前）、任务、记录。 */
    @Transactional(readOnly = true)
    public VersionReferences references(Long versionId) {
        InspectionTemplateVersion v = versionRepo.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("版本不存在"));
        boolean isCurrent = getCurrentVersion(v.getTemplateId())
                .map(cur -> cur.getId().equals(versionId)).orElse(false);

        long directPlans = planRepo.countByTemplateVersionId(versionId);
        // 跟随当前发布版的计划在“当前版本”上同样视为引用
        long effectivePlans = directPlans
                + (isCurrent ? planRepo.countByTemplateIdAndTemplateVersionIdIsNull(v.getTemplateId()) : 0);
        long tasks = taskRepo.countByTemplateVersionId(versionId);
        long records = recordRepo.countByTemplateVersionId(versionId);
        // 被计划引用或已有任务/记录的版本不得物理删除；当前实现中版本一律不物理删除
        boolean referenced = effectivePlans > 0 || tasks > 0 || records > 0;
        return new VersionReferences(versionId, v.getTemplateId(), v.getVersionNo(), isCurrent,
                directPlans, effectivePlans, tasks, records, !referenced);
    }

    /* ============================ 记录追溯 ============================ */

    public record ItemDefinition(Long itemId, String name, String type, Integer sortOrder,
                                  Double normalMin, Double normalMax,
                                  String qualifiedOptions, String judgeCriteria) {}

    public record RecordDefinitionView(Long recordId, Long taskId, Long templateId, String templateCode,
                                        Long versionId, Integer versionNo, String versionName,
                                        String publishedBy, LocalDateTime publishedAt, String changeSummary,
                                        String checkValue, Double checkNumeric, Boolean qualified,
                                        String judgeDetail, LocalDateTime recordedAt, String recordedBy,
                                        ItemDefinition item) {}

    /**
     * 查看任一巡检记录当时采用的完整判定标准：项目定义与阈值优先取记录上的冻结快照，
     * 发布人/发布时间来自任务冻结的不可变版本。
     */
    @Transactional(readOnly = true)
    public RecordDefinitionView getRecordDefinition(Long recordId) {
        InspectionRecord rec = recordRepo.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("巡检记录不存在"));
        InspectionTemplateVersion v = rec.getTemplateVersionId() == null ? null
                : versionRepo.findById(rec.getTemplateVersionId()).orElse(null);
        InspectionTemplate t = v == null ? null : templateRepo.findById(v.getTemplateId()).orElse(null);

        // 阈值等优先用记录行内的冻结值（旧数据可能为空），缺失时回退到版本项目表
        InspectionTemplateItem versionItem = v == null ? null
                : itemRepo.findById(rec.getTemplateItemId())
                        .filter(it -> it.getVersionId().equals(v.getId())).orElse(null);
        ItemDefinition item = new ItemDefinition(
                rec.getTemplateItemId(), rec.getItemName(), rec.getItemType(), null,
                rec.getNormalMin() != null ? rec.getNormalMin() : (versionItem == null ? null : versionItem.getNormalMin()),
                rec.getNormalMax() != null ? rec.getNormalMax() : (versionItem == null ? null : versionItem.getNormalMax()),
                rec.getQualifiedOptions() != null && !rec.getQualifiedOptions().isBlank()
                        ? rec.getQualifiedOptions()
                        : (versionItem == null ? "" : versionItem.getQualifiedOptions()),
                rec.getJudgeCriteria() != null && !rec.getJudgeCriteria().isBlank()
                        ? rec.getJudgeCriteria()
                        : (versionItem == null ? "" : versionItem.getJudgeCriteria()));

        return new RecordDefinitionView(
                rec.getId(), rec.getTaskId(),
                v != null ? v.getTemplateId() : null,
                t != null ? t.getCode() : "",
                rec.getTemplateVersionId(), rec.getTemplateVersionNo(),
                v != null ? v.getName() : "",
                v != null ? v.getPublishedBy() : "",
                v != null ? v.getPublishedAt() : null,
                v != null ? v.getChangeSummary() : "",
                rec.getCheckValue(), rec.getCheckNumeric(), rec.getIsQualified(),
                rec.getJudgeDetail(), rec.getRecordedAt(), rec.getRecordedBy(),
                item);
    }

    /* ============================ 判定 ============================ */

    public JudgeResult judgeItem(InspectionTemplateItem item, String valueStr, Double numericValue) {
        String type = item.getType();
        if ("numeric".equals(type)) {
            if (numericValue == null && valueStr != null) {
                try { numericValue = Double.parseDouble(valueStr.trim()); }
                catch (NumberFormatException e) { return new JudgeResult(false, null, "数值格式错误"); }
            }
            if (numericValue == null) return new JudgeResult(false, null, "缺失数值");
            boolean ok = true;
            String detail = "";
            if (item.getNormalMin() != null && numericValue < item.getNormalMin()) {
                ok = false;
                detail = "低于最小值 " + item.getNormalMin();
            }
            if (item.getNormalMax() != null && numericValue > item.getNormalMax()) {
                ok = false;
                detail = detail.isEmpty() ? ("高于最大值 " + item.getNormalMax()) : (detail + " 且高于最大值 " + item.getNormalMax());
            }
            if (ok) detail = "数值在正常范围内";
            return new JudgeResult(ok, numericValue, detail);
        } else if ("option".equals(type)) {
            if (valueStr == null || valueStr.isBlank()) {
                return new JudgeResult(false, null, "缺失选项值");
            }
            String options = item.getQualifiedOptions();
            boolean ok = options == null || options.isBlank() ||
                    Arrays.asList(options.split(",")).contains(valueStr.trim());
            return new JudgeResult(ok, null, ok ? "选项合格" : ("选项 '" + valueStr + "' 不在合格范围 [" + options + "]"));
        } else {
            if (valueStr == null || valueStr.isBlank()) {
                return new JudgeResult(false, null, "缺失检查值");
            }
            boolean ok = !"异常".equals(valueStr.trim()) && !"fail".equalsIgnoreCase(valueStr.trim());
            return new JudgeResult(ok, null, ok ? "正常" : "判定异常");
        }
    }

    public record JudgeResult(boolean qualified, Double numericValue, String detail) {}

    /* ============================ 校验 ============================ */

    /**
     * 发布（及草稿保存）校验：
     * 1) 项目非空、名称必填且版本内唯一；
     * 2) 项目顺序：显式给出的 sortOrder 必须恰好构成 1..n 的不重复连续序列，未给出则按提交顺序编号；
     * 3) 数值项：类型合法，上下限不得为 NaN，且 min <= max；
     * 4) 选项项：合格选项必填、不得含空白项、不得重复。
     */
    public List<ItemSpec> validateAndNormalize(List<ItemSpec> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("至少保留一个巡检项目");
        }
        List<ItemSpec> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        boolean hasExplicitOrder = items.stream().anyMatch(i -> i != null && i.sortOrder() != null);
        Set<Integer> orders = new HashSet<>();

        for (int idx = 0; idx < items.size(); idx++) {
            ItemSpec spec = items.get(idx);
            if (spec == null) throw new IllegalArgumentException("第" + (idx + 1) + "个项目为空");
            if (spec.name() == null || spec.name().isBlank()) {
                throw new IllegalArgumentException("第" + (idx + 1) + "个项目名称必填");
            }
            String name = spec.name().trim();
            if (!names.add(name)) {
                throw new IllegalArgumentException("项目名称重复: " + name);
            }
            String type = validType(spec.type());

            int order;
            if (hasExplicitOrder) {
                if (spec.sortOrder() == null) {
                    throw new IllegalArgumentException("项目[" + name + "]缺少顺序号：顺序需全部填写或全部不填");
                }
                order = spec.sortOrder();
                if (order < 1 || !orders.add(order)) {
                    throw new IllegalArgumentException("项目顺序必须从1开始且不重复，异常顺序号: " + order);
                }
            } else {
                order = idx + 1;
            }

            Double min = spec.normalMin();
            Double max = spec.normalMax();
            if ("numeric".equals(type)) {
                if (min != null && (Double.isNaN(min) || Double.isInfinite(min))) {
                    throw new IllegalArgumentException("项目[" + name + "]下限不是有效数值");
                }
                if (max != null && (Double.isNaN(max) || Double.isInfinite(max))) {
                    throw new IllegalArgumentException("项目[" + name + "]上限不是有效数值");
                }
                if (min != null && max != null && min > max) {
                    throw new IllegalArgumentException("项目[" + name + "]数值下限(" + min + ")不能大于上限(" + max + ")");
                }
            }

            String options = spec.qualifiedOptions() == null ? "" : spec.qualifiedOptions().trim();
            if ("option".equals(type)) {
                if (options.isEmpty()) {
                    throw new IllegalArgumentException("选项类项目[" + name + "]必须配置合格选项");
                }
                String[] parts = options.split(",");
                Set<String> optionSet = new HashSet<>();
                for (String p : parts) {
                    String op = p.trim();
                    if (op.isEmpty()) {
                        throw new IllegalArgumentException("项目[" + name + "]的合格选项包含空项");
                    }
                    if (!optionSet.add(op)) {
                        throw new IllegalArgumentException("项目[" + name + "]的合格选项重复: " + op);
                    }
                }
            }

            result.add(new ItemSpec(name, type, min, max, options,
                    spec.judgeCriteria() == null ? "" : spec.judgeCriteria(), order));
        }

        if (hasExplicitOrder) {
            for (int i = 1; i <= items.size(); i++) {
                if (!orders.contains(i)) {
                    throw new IllegalArgumentException("项目顺序不连续，缺少顺序号: " + i);
                }
            }
        }
        result.sort(Comparator.comparingInt(ItemSpec::sortOrder));
        return result;
    }

    /* ============================ 内部工具 ============================ */

    private InspectionTemplate mustGetTemplate(Long id) {
        return templateRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在"));
    }

    private Map<String, InspectionTemplateItem> mapByName(List<InspectionTemplateItem> items) {
        Map<String, InspectionTemplateItem> map = new LinkedHashMap<>();
        for (InspectionTemplateItem it : items) map.put(it.getName(), it);
        return map;
    }

    private void addChange(List<FieldChange> changes, String field, Object from, Object to) {
        if (!Objects.equals(from == null ? "" : from, to == null ? "" : to)) {
            changes.add(new FieldChange(field, from, to));
        }
    }

    private String validType(String t) {
        if (t == null) return "option";
        return switch (t) {
            case "numeric", "option", "text" -> t;
            default -> "option";
        };
    }
}
