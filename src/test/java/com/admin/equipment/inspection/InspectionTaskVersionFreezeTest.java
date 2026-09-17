package com.admin.equipment.inspection;

import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionPoint;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.model.inspection.InspectionTaskPoint;
import com.admin.equipment.model.inspection.InspectionTemplate;
import com.admin.equipment.model.inspection.InspectionTemplateVersion;
import com.admin.equipment.repo.inspection.InspectionPointRepository;
import com.admin.equipment.service.inspection.InspectionPlanService;
import com.admin.equipment.service.inspection.InspectionPlanService.PlanSpec;
import com.admin.equipment.service.inspection.InspectionTaskService;
import com.admin.equipment.service.inspection.InspectionTaskService.PointItemSpec;
import com.admin.equipment.service.inspection.InspectionTaskService.RecordVersionDetail;
import com.admin.equipment.service.inspection.InspectionTemplateService;
import com.admin.equipment.service.inspection.InspectionTemplateService.ItemSpec;
import com.admin.equipment.service.inspection.InspectionTemplateService.VersionReferences;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务版本冻结：任务生成时冻结计划引用的发布版；
 * 正在执行/已完成的任务继续按原版本判定，新版本只影响之后生成的任务；
 * 任一巡检记录可追溯到项目定义、阈值、发布人和发布时间。
 */
@SpringBootTest
@ActiveProfiles("test")
class InspectionTaskVersionFreezeTest {

    @Autowired
    private InspectionTemplateService templateService;
    @Autowired
    private InspectionPlanService planService;
    @Autowired
    private InspectionTaskService taskService;
    @Autowired
    private InspectionPointRepository pointRepo;

    private String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    @Test
    void executingTaskKeepsFrozenVersion_newTaskUsesNewVersion_recordTraceable() {
        // 巡检点
        InspectionPoint point = new InspectionPoint();
        point.setCode(uniq("IP"));
        point.setName("泵房1号点");
        point.setLocation("动力站");
        point.setCoordX(10.0);
        point.setCoordY(20.0);
        point.setEquipmentIds("");
        point.setEquipmentType("pump");
        point = pointRepo.save(point);

        // v1：排气压力 0.6~0.8 MPa
        InspectionTemplate template = templateService.create(uniq("TPL"), "泵类设备巡检模板", "pump", "",
                List.of(new ItemSpec("排气压力(MPa)", "numeric", 0.6, 0.8, "", "0.6~0.8 MPa 为正常")));
        InspectionTemplateVersion v1 = templateService.publish(template.getId(), "王工");

        // 计划引用 v1，生成任务 T1 并开始执行（模拟"当天已有任务正在执行"）
        InspectionPlan plan = planService.create(new PlanSpec(uniq("PLAN"), "动力站巡检计划",
                template.getId(), "daily", 1, "day", "08:00", "10:00", 120,
                "甲班", "", "", List.of(point.getId()), null));
        assertEquals(v1.getId(), plan.getTemplateVersionId());
        InspectionTask t1 = taskService.generateTask(plan.getId(), 2L, "张三", false, null);
        assertEquals(v1.getId(), t1.getTemplateVersionId(), "任务生成时冻结当前发布版");
        taskService.startTask(t1.getId(), "张三");

        // 质量工程师调整压力范围为 0.7~0.9 并发布 v2
        templateService.update(template.getId(), null, null, null,
                List.of(new ItemSpec("排气压力(MPa)", "numeric", 0.7, 0.9, "", "0.7~0.9 MPa 为正常")));
        InspectionTemplateVersion v2 = templateService.publish(template.getId(), "王工");
        assertEquals(2, v2.getVersionNo());
        assertEquals(v2.getId(), planService.getById(plan.getId()).orElseThrow().getTemplateVersionId(),
                "发布后计划推进到当前发布版");

        // 正在执行的 T1 仍按 v1 判定：0.65 合格
        Long v1ItemId = templateService.getVersionItems(v1.getId()).get(0).getId();
        InspectionTaskPoint tp1 = taskService.getTaskPoints(t1.getId()).get(0);
        var r1 = taskService.executePoint(t1.getId(), tp1.getId(), "张三",
                List.of(new PointItemSpec(v1ItemId, "0.65", null, null)), null);
        assertEquals(1, r1.records().size());
        assertTrue(r1.records().get(0).getIsQualified(), "执行中的任务按冻结的 v1 判定，0.65 应在 0.6~0.8 内合格");
        assertEquals(v1ItemId, r1.records().get(0).getTemplateVersionItemId());

        // 之后生成的 T2 按 v2 判定：0.65 不合格
        InspectionTask t2 = taskService.generateTask(plan.getId(), 3L, "李四", false, null);
        assertEquals(v2.getId(), t2.getTemplateVersionId(), "新版本只影响之后生成的任务");
        taskService.startTask(t2.getId(), "李四");
        Long v2ItemId = templateService.getVersionItems(v2.getId()).get(0).getId();
        InspectionTaskPoint tp2 = taskService.getTaskPoints(t2.getId()).get(0);
        var r2 = taskService.executePoint(t2.getId(), tp2.getId(), "李四",
                List.of(new PointItemSpec(v2ItemId, "0.65", null, null)), null);
        assertFalse(r2.records().get(0).getIsQualified(), "新任务按 v2 判定，0.65 低于 0.7 不合格");

        // 查看 T1 的巡检记录：获得项目定义、阈值、发布人和发布时间
        RecordVersionDetail detail = taskService.getRecordVersionDetail(r1.records().get(0).getId());
        assertEquals("排气压力(MPa)", detail.versionItem().getName());
        assertEquals(0.6, detail.versionItem().getNormalMin());
        assertEquals(0.8, detail.versionItem().getNormalMax());
        assertEquals("0.6~0.8 MPa 为正常", detail.versionItem().getJudgeCriteria());
        assertEquals(1, detail.version().getVersionNo());
        assertEquals("王工", detail.version().getPublishedBy());
        assertNotNull(detail.version().getPublishedAt());
        assertEquals(template.getCode(), detail.template().getCode());

        // 查看 T2 的巡检记录：阈值为 v2 的 0.7~0.9
        RecordVersionDetail detail2 = taskService.getRecordVersionDetail(r2.records().get(0).getId());
        assertEquals(2, detail2.version().getVersionNo());
        assertEquals(0.7, detail2.versionItem().getNormalMin());
        assertEquals(0.9, detail2.versionItem().getNormalMax());

        // 引用关系：v1 已被任务冻结并产生记录，不得物理删除
        VersionReferences refs = templateService.getVersionReferences(v1.getId());
        assertEquals(1, refs.tasks().size());
        assertEquals(t1.getCode(), refs.tasks().get(0).code());
        assertEquals(1, refs.recordCount());
        assertFalse(refs.deletable());
        assertThrows(IllegalArgumentException.class, () -> templateService.deleteVersion(v1.getId()));
    }
}
