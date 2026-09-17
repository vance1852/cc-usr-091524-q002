package com.admin.equipment.seed;

import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionRecord;
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
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 历史数据迁移：为没有版本的模板把旧模板项快照为 v1 发布版，
 * 并回填计划、任务的版本引用与巡检记录的版本项目引用。幂等，可重复执行。
 * 在 DataSeeder 之后运行，保证种子模板也被迁移。
 */
@Component
@Order(2)
public class TemplateVersionMigration implements ApplicationRunner {

    private final InspectionTemplateRepository templateRepo;
    private final InspectionTemplateItemRepository itemRepo;
    private final InspectionTemplateVersionRepository versionRepo;
    private final InspectionTemplateVersionItemRepository versionItemRepo;
    private final InspectionPlanRepository planRepo;
    private final InspectionTaskRepository taskRepo;
    private final InspectionRecordRepository recordRepo;

    public TemplateVersionMigration(InspectionTemplateRepository templateRepo,
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

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (InspectionTemplate t : templateRepo.findAll()) {
            if (versionRepo.existsByTemplateId(t.getId())) continue;
            List<InspectionTemplateItem> legacyItems = itemRepo.findByTemplateIdOrderBySortOrderAsc(t.getId());
            if (legacyItems.isEmpty()) continue;
            Map<Long, Long> itemIdMap = migrateTemplate(t, legacyItems);
            backfillRecords(itemIdMap);
            System.out.println("模板版本迁移：模板[" + t.getCode() + "] 已生成 v1 发布版 (" + legacyItems.size() + "项)");
        }
        backfillPlans();
        backfillTasks();
    }

    private Map<Long, Long> migrateTemplate(InspectionTemplate t, List<InspectionTemplateItem> legacyItems) {
        InspectionTemplateVersion v1 = new InspectionTemplateVersion();
        v1.setTemplateId(t.getId());
        v1.setVersionNo(1);
        v1.setStatus(InspectionTemplateVersion.STATUS_PUBLISHED);
        v1.setName(t.getName());
        v1.setEquipmentType(t.getEquipmentType());
        v1.setDescription(t.getDescription());
        v1.setPublishedBy("system-migration");
        v1.setPublishedAt(LocalDateTime.now());
        InspectionTemplateVersion saved = versionRepo.save(v1);

        Map<Long, Long> itemIdMap = new HashMap<>();
        for (InspectionTemplateItem src : legacyItems) {
            InspectionTemplateVersionItem copy = new InspectionTemplateVersionItem();
            copy.setVersionId(saved.getId());
            copy.setName(src.getName());
            copy.setType(src.getType());
            copy.setSortOrder(src.getSortOrder());
            copy.setNormalMin(src.getNormalMin());
            copy.setNormalMax(src.getNormalMax());
            copy.setQualifiedOptions(src.getQualifiedOptions());
            copy.setJudgeCriteria(src.getJudgeCriteria());
            InspectionTemplateVersionItem savedItem = versionItemRepo.save(copy);
            itemIdMap.put(src.getId(), savedItem.getId());
        }
        return itemIdMap;
    }

    private void backfillRecords(Map<Long, Long> itemIdMap) {
        for (Map.Entry<Long, Long> e : itemIdMap.entrySet()) {
            List<InspectionRecord> records = recordRepo.findByTemplateItemIdOrderByRecordedAtDesc(e.getKey());
            for (InspectionRecord rec : records) {
                if (rec.getTemplateVersionItemId() == null) {
                    rec.setTemplateVersionItemId(e.getValue());
                    recordRepo.save(rec);
                }
            }
        }
    }

    private void backfillPlans() {
        for (InspectionPlan plan : planRepo.findAll()) {
            if (plan.getTemplateVersionId() != null) continue;
            Optional<InspectionTemplateVersion> published = currentPublished(plan.getTemplateId());
            if (published.isPresent()) {
                plan.setTemplateVersionId(published.get().getId());
                planRepo.save(plan);
            }
        }
    }

    private void backfillTasks() {
        for (InspectionTask task : taskRepo.findAll()) {
            if (task.getTemplateVersionId() != null) continue;
            Optional<InspectionTemplateVersion> published = currentPublished(task.getTemplateId());
            if (published.isPresent()) {
                task.setTemplateVersionId(published.get().getId());
                taskRepo.save(task);
            }
        }
    }

    private Optional<InspectionTemplateVersion> currentPublished(Long templateId) {
        return versionRepo.findFirstByTemplateIdAndStatusOrderByVersionNoDesc(
                templateId, InspectionTemplateVersion.STATUS_PUBLISHED);
    }
}
