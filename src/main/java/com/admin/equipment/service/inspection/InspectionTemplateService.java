package com.admin.equipment.service.inspection;

import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.model.inspection.InspectionTemplate;
import com.admin.equipment.model.inspection.InspectionTemplateItem;
import com.admin.equipment.model.inspection.InspectionTemplateVersion;
import com.admin.equipment.model.inspection.InspectionTemplateVersionItem;
import com.admin.equipment.repo.inspection.InspectionPlanRepository;
import com.admin.equipment.repo.inspection.InspectionRecordRepository;
import com.admin.equipment.repo.inspection.InspectionTaskRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateItemRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateVersionItemRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 巡检模板维护：草稿 -> 发布 -> 停用 的版本化流程。
 * 模板（code）保持业务身份；每次发布生成不可修改的版本及完整项目快照；
 * 计划引用当前发布版，任务生成时冻结实际版本。
 */
@Service
public class InspectionTemplateService {

    private final InspectionTemplateRepository templateRepo;
    private final InspectionTemplateItemRepository itemRepo;
    private final InspectionTemplateVersionRepository versionRepo;
    private final InspectionTemplateVersionItemRepository versionItemRepo;
    private final InspectionPlanRepository planRepo;
    private final InspectionTaskRepository taskRepo;
    private final InspectionRecordRepository recordRepo;

    public InspectionTemplateService(InspectionTemplateRepository templateRepo,
                                      InspectionTemplateItemRepository itemRepo,
                                      InspectionTemplateVersionRepository versionRepo,
                                      InspectionTemplateVersionItemRepository versionItemRepo,
                                      InspectionPlanRepository planRepo,
                                      InspectionTaskRepository taskRepo,
                                      InspectionRecordRepository recordRepo) {
        this.templateRepo = templateRepo;
        this.itemRepo = itemRepo;
        this.versionRepo = versionRepo;
        this.versionItemRepo = versionItemRepo;
        this.planRepo = planRepo;
        this.taskRepo = taskRepo;
        this.recordRepo = recordRepo;
    }

    public List<InspectionTemplate> listAll() {
        return templateRepo.findAllByOrderByCodeAsc();
    }

    public List<InspectionTemplate> listByEquipmentType(String type) {
        return templateRepo.findByEquipmentTypeOrderByCodeAsc(type);
    }

    public Optional<InspectionTemplate> getById(Long id) {
        return templateRepo.findById(id);
    }

    /** 旧版模板项（兼容未迁移数据）。 */
    public List<InspectionTemplateItem> getItems(Long templateId) {
        return itemRepo.findByTemplateIdOrderBySortOrderAsc(templateId);
    }

    // ---------- 版本查询 ----------

    public List<InspectionTemplateVersion> listVersions(Long templateId) {
        return versionRepo.findByTemplateIdOrderByVersionNoDesc(templateId);
    }

    public Optional<InspectionTemplateVersion> getCurrentPublished(Long templateId) {
        return versionRepo.findFirstByTemplateIdAndStatusOrderByVersionNoDesc(
                templateId, InspectionTemplateVersion.STATUS_PUBLISHED);
    }

    public Optional<InspectionTemplateVersion> getDraft(Long templateId) {
        return versionRepo.findFirstByTemplateIdAndStatusOrderByIdDesc(
                templateId, InspectionTemplateVersion.STATUS_DRAFT);
    }

    public List<InspectionTemplateVersionItem> getVersionItems(Long versionId) {
        return versionItemRepo.findByVersionIdOrderBySortOrderAsc(versionId);
    }

    /** 当前发布版项目快照；无发布版时返回空。 */
    public List<InspectionTemplateVersionItem> getCurrentPublishedItems(Long templateId) {
        return getCurrentPublished(templateId)
                .map(v -> versionItemRepo.findByVersionIdOrderBySortOrderAsc(v.getId()))
                .orElseGet(ArrayList::new);
    }

    public record VersionDetail(InspectionTemplateVersion version,
                                List<InspectionTemplateVersionItem> items) {}

    public VersionDetail getVersionDetail(Long versionId) {
        InspectionTemplateVersion v = versionRepo.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("模板版本不存在"));
        return new VersionDetail(v, versionItemRepo.findByVersionIdOrderBySortOrderAsc(versionId));
    }

    // ---------- 模板与草稿维护 ----------

    public record ItemSpec(String name, String type, Integer sortOrder, Double normalMin, Double normalMax,
                           String qualifiedOptions, String judgeCriteria) {
        /** 兼容旧调用：不显式指定顺序时按传入顺序编号。 */
        public ItemSpec(String name, String type, Double normalMin, Double normalMax,
                        String qualifiedOptions, String judgeCriteria) {
            this(name, type, null, normalMin, normalMax, qualifiedOptions, judgeCriteria);
        }
    }

    @Transactional
    public InspectionTemplate create(String code, String name, String equipmentType, String description,
                                      List<ItemSpec> items) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("编号必填");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("名称必填");
        if (templateRepo.existsByCode(code)) throw new IllegalArgumentException("编号已存在");
        InspectionTemplate t = new InspectionTemplate();
        t.setCode(code);
        t.setName(name);
        t.setEquipmentType(equipmentType == null ? "" : equipmentType);
        t.setDescription(description == null ? "" : description);
        InspectionTemplate saved = templateRepo.save(t);

        InspectionTemplateVersion draft = newDraft(saved);
        versionRepo.save(draft);
        if (items != null) {
            replaceDraftItems(draft, items);
        }
        return saved;
    }

    /**
     * 更新模板头信息并维护草稿：没有草稿时从当前发布版克隆一份再应用修改，
     * 已发布版本的快照不受影响。
     */
    @Transactional
    public InspectionTemplate update(Long id, String name, String equipmentType, String description,
                                      List<ItemSpec> items) {
        InspectionTemplate t = templateRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在"));
        if (name != null && !name.isBlank()) t.setName(name);
        if (equipmentType != null) t.setEquipmentType(equipmentType);
        if (description != null) t.setDescription(description);
        InspectionTemplate saved = templateRepo.save(t);
        if (items != null) {
            InspectionTemplateVersion draft = getDraft(id).orElseGet(() -> newDraftFromPublished(saved));
            replaceDraftItems(draft, items);
        }
        return saved;
    }

    // ---------- 发布 / 停用 / 删除 ----------

    /**
     * 发布草稿：校验项目顺序、数值上下限、合格选项；生成递增版本号；
     * 原发布版转为停用；引用该模板的计划推进到新发布版。
     * 通过模板行悲观锁串行化，并发发布只有一个成功。
     */
    @Transactional
    public InspectionTemplateVersion publish(Long templateId, String operator) {
        InspectionTemplate t = templateRepo.findForUpdate(templateId)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在"));
        InspectionTemplateVersion draft = getDraft(templateId)
                .orElseThrow(() -> new IllegalArgumentException("没有待发布的草稿版本"));
        List<InspectionTemplateVersionItem> items = versionItemRepo.findByVersionIdOrderBySortOrderAsc(draft.getId());
        validateForPublish(items);

        int nextNo = versionRepo.maxVersionNo(templateId) + 1;
        getCurrentPublished(templateId).ifPresent(cur -> {
            cur.setStatus(InspectionTemplateVersion.STATUS_DEPRECATED);
            versionRepo.save(cur);
        });

        draft.setStatus(InspectionTemplateVersion.STATUS_PUBLISHED);
        draft.setVersionNo(nextNo);
        draft.setName(t.getName());
        draft.setEquipmentType(t.getEquipmentType());
        draft.setDescription(t.getDescription());
        draft.setPublishedBy(operator == null || operator.isBlank() ? "system" : operator.trim());
        draft.setPublishedAt(LocalDateTime.now());
        InspectionTemplateVersion published = versionRepo.save(draft);

        // 计划明确引用当前发布版：发布后全部推进到新版本
        for (InspectionPlan plan : planRepo.findByTemplateId(templateId)) {
            plan.setTemplateVersionId(published.getId());
            planRepo.save(plan);
        }
        return published;
    }

    /** 停用发布版：仍有启用中的计划引用时拒绝。 */
    @Transactional
    public InspectionTemplateVersion deprecateVersion(Long versionId) {
        InspectionTemplateVersion v = versionRepo.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("模板版本不存在"));
        if (!InspectionTemplateVersion.STATUS_PUBLISHED.equals(v.getStatus())) {
            throw new IllegalArgumentException("仅已发布版本可停用，当前状态：" + v.getStatus());
        }
        boolean referencedByEnabledPlan = planRepo.findByTemplateVersionId(versionId).stream()
                .anyMatch(p -> Boolean.TRUE.equals(p.getEnabled()));
        if (referencedByEnabledPlan) {
            throw new IllegalArgumentException("仍有启用中的计划引用该版本，无法停用");
        }
        v.setStatus(InspectionTemplateVersion.STATUS_DEPRECATED);
        return versionRepo.save(v);
    }

    /** 删除单个版本：被计划引用或已有任务/巡检记录的版本不得物理删除。 */
    @Transactional
    public void deleteVersion(Long versionId) {
        InspectionTemplateVersion v = versionRepo.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("模板版本不存在"));
        if (InspectionTemplateVersion.STATUS_PUBLISHED.equals(v.getStatus())) {
            Optional<InspectionTemplateVersion> cur = getCurrentPublished(v.getTemplateId());
            if (cur.isPresent() && cur.get().getId().equals(versionId)) {
                throw new IllegalArgumentException("当前发布版本不可删除，请先发布新版本或停用");
            }
        }
        if (!planRepo.findByTemplateVersionId(versionId).isEmpty()) {
            throw new IllegalArgumentException("版本被计划引用，无法删除");
        }
        if (taskRepo.existsByTemplateVersionId(versionId)) {
            throw new IllegalArgumentException("版本已生成任务，无法删除");
        }
        List<Long> itemIds = versionItemIds(versionId);
        if (!itemIds.isEmpty() && recordRepo.existsByTemplateVersionItemIdIn(itemIds)) {
            throw new IllegalArgumentException("版本已产生巡检记录，无法删除");
        }
        versionItemRepo.deleteByVersionId(versionId);
        versionRepo.deleteById(versionId);
    }

    /** 删除模板：任一版本被计划引用或已有任务记录时拒绝。 */
    @Transactional
    public void delete(Long id) {
        if (!templateRepo.existsById(id)) throw new IllegalArgumentException("模板不存在");
        if (!planRepo.findByTemplateId(id).isEmpty()) {
            throw new IllegalArgumentException("模板已被计划引用，无法删除");
        }
        for (InspectionTemplateVersion v : versionRepo.findByTemplateId(id)) {
            if (taskRepo.existsByTemplateVersionId(v.getId())) {
                throw new IllegalArgumentException("模板版本已生成任务，无法删除");
            }
            List<Long> itemIds = versionItemIds(v.getId());
            if (!itemIds.isEmpty() && recordRepo.existsByTemplateVersionItemIdIn(itemIds)) {
                throw new IllegalArgumentException("模板版本已产生巡检记录，无法删除");
            }
        }
        for (InspectionTemplateVersion v : versionRepo.findByTemplateId(id)) {
            versionItemRepo.deleteByVersionId(v.getId());
            versionRepo.deleteById(v.getId());
        }
        itemRepo.deleteByTemplateId(id);
        templateRepo.deleteById(id);
    }

    // ---------- 版本差异与引用关系 ----------

    public record VersionItemView(Long id, String name, String type, Integer sortOrder,
                                  Double normalMin, Double normalMax,
                                  String qualifiedOptions, String judgeCriteria) {}
    public record FieldChange(String field, Object oldValue, Object newValue) {}
    public record ItemChange(String name, List<FieldChange> changes) {}
    public record VersionRef(Long versionId, Integer versionNo, String status) {}
    public record VersionDiff(VersionRef from, VersionRef to,
                              List<VersionItemView> added, List<VersionItemView> removed,
                              List<ItemChange> changed, int unchangedCount) {}

    /** 以项目名为业务键比较两个版本的项目快照。 */
    public VersionDiff diffVersions(Long fromId, Long toId) {
        InspectionTemplateVersion from = versionRepo.findById(fromId)
                .orElseThrow(() -> new IllegalArgumentException("起始版本不存在: " + fromId));
        InspectionTemplateVersion to = versionRepo.findById(toId)
                .orElseThrow(() -> new IllegalArgumentException("目标版本不存在: " + toId));
        if (!from.getTemplateId().equals(to.getTemplateId())) {
            throw new IllegalArgumentException("只能比较同一模板的版本");
        }
        Map<String, InspectionTemplateVersionItem> fromItems = itemsByName(fromId);
        Map<String, InspectionTemplateVersionItem> toItems = itemsByName(toId);

        List<VersionItemView> added = new ArrayList<>();
        List<VersionItemView> removed = new ArrayList<>();
        List<ItemChange> changed = new ArrayList<>();
        int unchanged = 0;

        for (Map.Entry<String, InspectionTemplateVersionItem> e : toItems.entrySet()) {
            InspectionTemplateVersionItem old = fromItems.get(e.getKey());
            if (old == null) {
                added.add(toView(e.getValue()));
            } else {
                List<FieldChange> changes = fieldChanges(old, e.getValue());
                if (changes.isEmpty()) unchanged++;
                else changed.add(new ItemChange(e.getKey(), changes));
            }
        }
        for (Map.Entry<String, InspectionTemplateVersionItem> e : fromItems.entrySet()) {
            if (!toItems.containsKey(e.getKey())) removed.add(toView(e.getValue()));
        }
        return new VersionDiff(new VersionRef(from.getId(), from.getVersionNo(), from.getStatus()),
                new VersionRef(to.getId(), to.getVersionNo(), to.getStatus()),
                added, removed, changed, unchanged);
    }

    public record PlanRef(Long id, String code, String name, Boolean enabled) {}
    public record TaskRef(Long id, String code, String status) {}
    public record VersionReferences(Long versionId, Integer versionNo, String status,
                                    List<PlanRef> plans, List<TaskRef> tasks,
                                    long recordCount, boolean deletable) {}

    /** 版本的引用关系：哪些计划引用、哪些任务冻结、产生了多少巡检记录。 */
    public VersionReferences getVersionReferences(Long versionId) {
        InspectionTemplateVersion v = versionRepo.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("模板版本不存在"));
        List<PlanRef> plans = planRepo.findByTemplateVersionId(versionId).stream()
                .map(p -> new PlanRef(p.getId(), p.getCode(), p.getName(), p.getEnabled()))
                .toList();
        List<TaskRef> tasks = taskRepo.findByTemplateVersionId(versionId).stream()
                .map(t -> new TaskRef(t.getId(), t.getCode(), t.getStatus()))
                .toList();
        List<Long> itemIds = versionItemIds(versionId);
        long recordCount = itemIds.isEmpty() ? 0 : recordRepo.countByTemplateVersionItemIdIn(itemIds);
        boolean isCurrentPublished = InspectionTemplateVersion.STATUS_PUBLISHED.equals(v.getStatus())
                && getCurrentPublished(v.getTemplateId()).map(c -> c.getId().equals(versionId)).orElse(false);
        boolean deletable = !isCurrentPublished && plans.isEmpty() && tasks.isEmpty() && recordCount == 0;
        return new VersionReferences(v.getId(), v.getVersionNo(), v.getStatus(),
                plans, tasks, recordCount, deletable);
    }

    // ---------- 判定 ----------

    public JudgeResult judgeItem(InspectionTemplateItem item, String valueStr, Double numericValue) {
        return judge(item.getType(), item.getNormalMin(), item.getNormalMax(),
                item.getQualifiedOptions(), valueStr, numericValue);
    }

    public JudgeResult judgeVersionItem(InspectionTemplateVersionItem item, String valueStr, Double numericValue) {
        return judge(item.getType(), item.getNormalMin(), item.getNormalMax(),
                item.getQualifiedOptions(), valueStr, numericValue);
    }

    private JudgeResult judge(String type, Double normalMin, Double normalMax, String qualifiedOptions,
                              String valueStr, Double numericValue) {
        if ("numeric".equals(type)) {
            if (numericValue == null && valueStr != null) {
                try { numericValue = Double.parseDouble(valueStr.trim()); }
                catch (NumberFormatException e) { return new JudgeResult(false, null, "数值格式错误"); }
            }
            if (numericValue == null) return new JudgeResult(false, null, "缺失数值");
            boolean ok = true;
            String detail = "";
            if (normalMin != null && numericValue < normalMin) {
                ok = false;
                detail = "低于最小值 " + normalMin;
            }
            if (normalMax != null && numericValue > normalMax) {
                ok = false;
                detail = detail.isEmpty() ? ("高于最大值 " + normalMax) : (detail + " 且高于最大值 " + normalMax);
            }
            if (ok) detail = "数值在正常范围内";
            return new JudgeResult(ok, numericValue, detail);
        } else if ("option".equals(type)) {
            if (valueStr == null || valueStr.isBlank()) {
                return new JudgeResult(false, null, "缺失选项值");
            }
            boolean ok = qualifiedOptions == null || qualifiedOptions.isBlank() ||
                    Arrays.asList(qualifiedOptions.split(",")).contains(valueStr.trim());
            return new JudgeResult(ok, null, ok ? "选项合格" : ("选项 '" + valueStr + "' 不在合格范围 [" + qualifiedOptions + "]"));
        } else {
            if (valueStr == null || valueStr.isBlank()) {
                return new JudgeResult(false, null, "缺失检查值");
            }
            boolean ok = !"异常".equals(valueStr.trim()) && !"fail".equalsIgnoreCase(valueStr.trim());
            return new JudgeResult(ok, null, ok ? "正常" : "判定异常");
        }
    }

    public record JudgeResult(boolean qualified, Double numericValue, String detail) {}

    // ---------- 内部辅助 ----------

    private InspectionTemplateVersion newDraft(InspectionTemplate t) {
        InspectionTemplateVersion draft = new InspectionTemplateVersion();
        draft.setTemplateId(t.getId());
        draft.setStatus(InspectionTemplateVersion.STATUS_DRAFT);
        draft.setName(t.getName());
        draft.setEquipmentType(t.getEquipmentType());
        draft.setDescription(t.getDescription());
        return draft;
    }

    /** 从当前发布版克隆草稿（含项目快照），发布版本身保持不变。 */
    private InspectionTemplateVersion newDraftFromPublished(InspectionTemplate t) {
        InspectionTemplateVersion draft = versionRepo.save(newDraft(t));
        getCurrentPublished(t.getId()).ifPresent(pub -> {
            for (InspectionTemplateVersionItem src : versionItemRepo.findByVersionIdOrderBySortOrderAsc(pub.getId())) {
                InspectionTemplateVersionItem copy = copyItem(draft.getId(), src);
                versionItemRepo.save(copy);
            }
        });
        return draft;
    }

    private void replaceDraftItems(InspectionTemplateVersion draft, List<ItemSpec> items) {
        if (!InspectionTemplateVersion.STATUS_DRAFT.equals(draft.getStatus())) {
            throw new IllegalArgumentException("已发布或已停用的版本不可修改");
        }
        versionItemRepo.deleteByVersionId(draft.getId());
        int sort = 1;
        for (ItemSpec spec : items) {
            InspectionTemplateVersionItem item = new InspectionTemplateVersionItem();
            item.setVersionId(draft.getId());
            item.setName(spec.name());
            item.setType(validType(spec.type()));
            item.setSortOrder(spec.sortOrder() != null ? spec.sortOrder() : sort);
            item.setNormalMin(spec.normalMin());
            item.setNormalMax(spec.normalMax());
            item.setQualifiedOptions(spec.qualifiedOptions() == null ? "" : spec.qualifiedOptions());
            item.setJudgeCriteria(spec.judgeCriteria() == null ? "" : spec.judgeCriteria());
            versionItemRepo.save(item);
            sort++;
        }
    }

    private InspectionTemplateVersionItem copyItem(Long versionId, InspectionTemplateVersionItem src) {
        InspectionTemplateVersionItem copy = new InspectionTemplateVersionItem();
        copy.setVersionId(versionId);
        copy.setName(src.getName());
        copy.setType(src.getType());
        copy.setSortOrder(src.getSortOrder());
        copy.setNormalMin(src.getNormalMin());
        copy.setNormalMax(src.getNormalMax());
        copy.setQualifiedOptions(src.getQualifiedOptions());
        copy.setJudgeCriteria(src.getJudgeCriteria());
        return copy;
    }

    /** 发布校验：项目非空、顺序为正且不重复、数值上下限合法、选项型必须有合格选项。 */
    private void validateForPublish(List<InspectionTemplateVersionItem> items) {
        if (items.isEmpty()) throw new IllegalArgumentException("巡检项不能为空，无法发布");
        Set<Integer> orders = new HashSet<>();
        for (InspectionTemplateVersionItem item : items) {
            String label = item.getName() == null ? "" : item.getName();
            if (label.isBlank()) throw new IllegalArgumentException("巡检项名称不能为空");
            Integer so = item.getSortOrder();
            if (so == null || so < 1) {
                throw new IllegalArgumentException("项目顺序必须为正整数：" + label);
            }
            if (!orders.add(so)) {
                throw new IllegalArgumentException("项目顺序重复：" + so);
            }
            if ("numeric".equals(item.getType())) {
                if (item.getNormalMin() == null && item.getNormalMax() == null) {
                    throw new IllegalArgumentException("数值型项目需至少设置一个上下限：" + label);
                }
                if (item.getNormalMin() != null && item.getNormalMax() != null
                        && item.getNormalMin() > item.getNormalMax()) {
                    throw new IllegalArgumentException("数值下限不能大于上限：" + label);
                }
            } else if ("option".equals(item.getType())) {
                if (item.getQualifiedOptions() == null || item.getQualifiedOptions().isBlank()) {
                    throw new IllegalArgumentException("选项型项目需设置合格选项：" + label);
                }
            }
        }
    }

    private List<Long> versionItemIds(Long versionId) {
        return versionItemRepo.findByVersionIdOrderBySortOrderAsc(versionId).stream()
                .map(InspectionTemplateVersionItem::getId).toList();
    }

    private Map<String, InspectionTemplateVersionItem> itemsByName(Long versionId) {
        Map<String, InspectionTemplateVersionItem> map = new LinkedHashMap<>();
        for (InspectionTemplateVersionItem item : versionItemRepo.findByVersionIdOrderBySortOrderAsc(versionId)) {
            map.put(item.getName(), item);
        }
        return map;
    }

    private VersionItemView toView(InspectionTemplateVersionItem item) {
        return new VersionItemView(item.getId(), item.getName(), item.getType(), item.getSortOrder(),
                item.getNormalMin(), item.getNormalMax(), item.getQualifiedOptions(), item.getJudgeCriteria());
    }

    private List<FieldChange> fieldChanges(InspectionTemplateVersionItem oldItem, InspectionTemplateVersionItem newItem) {
        List<FieldChange> changes = new ArrayList<>();
        addChange(changes, "type", oldItem.getType(), newItem.getType());
        addChange(changes, "sortOrder", oldItem.getSortOrder(), newItem.getSortOrder());
        addChange(changes, "normalMin", oldItem.getNormalMin(), newItem.getNormalMin());
        addChange(changes, "normalMax", oldItem.getNormalMax(), newItem.getNormalMax());
        addChange(changes, "qualifiedOptions", oldItem.getQualifiedOptions(), newItem.getQualifiedOptions());
        addChange(changes, "judgeCriteria", oldItem.getJudgeCriteria(), newItem.getJudgeCriteria());
        return changes;
    }

    private void addChange(List<FieldChange> changes, String field, Object oldVal, Object newVal) {
        if (!Objects.equals(oldVal, newVal)) changes.add(new FieldChange(field, oldVal, newVal));
    }

    private String validType(String t) {
        if (t == null) return "option";
        return switch (t) {
            case "numeric", "option", "text" -> t;
            default -> "option";
        };
    }
}
