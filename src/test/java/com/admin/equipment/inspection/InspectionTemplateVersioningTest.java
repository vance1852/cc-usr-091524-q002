package com.admin.equipment.inspection;

import com.admin.equipment.model.inspection.InspectionPlan;
import com.admin.equipment.model.inspection.InspectionPoint;
import com.admin.equipment.model.inspection.InspectionTemplate;
import com.admin.equipment.model.inspection.InspectionTemplateVersion;
import com.admin.equipment.model.inspection.InspectionTemplateVersionItem;
import com.admin.equipment.repo.inspection.InspectionPointRepository;
import com.admin.equipment.service.inspection.InspectionPlanService;
import com.admin.equipment.service.inspection.InspectionPlanService.PlanSpec;
import com.admin.equipment.service.inspection.InspectionTemplateService;
import com.admin.equipment.service.inspection.InspectionTemplateService.ItemSpec;
import com.admin.equipment.service.inspection.InspectionTemplateService.VersionDiff;
import com.admin.equipment.service.inspection.InspectionTemplateService.VersionReferences;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模板版本流程：草稿 -> 发布 -> 停用，发布校验、快照不可变、
 * 计划引用推进、版本差异与删除保护。
 */
@SpringBootTest
@ActiveProfiles("test")
class InspectionTemplateVersioningTest {

    @Autowired
    private InspectionTemplateService templateService;
    @Autowired
    private InspectionPlanService planService;
    @Autowired
    private InspectionPointRepository pointRepo;

    private String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private InspectionPoint newPoint() {
        InspectionPoint p = new InspectionPoint();
        p.setCode(uniq("IP"));
        p.setName("测试巡检点");
        p.setLocation("测试车间");
        p.setCoordX(1.0);
        p.setCoordY(2.0);
        p.setEquipmentIds("");
        p.setEquipmentType("pump");
        return pointRepo.save(p);
    }

    private PlanSpec planSpec(String code, Long templateId, Long pointId) {
        return new PlanSpec(code, "测试计划", templateId, "daily", 1, "day",
                "08:00", "10:00", 120, "测试组", "", "", List.of(pointId), null);
    }

    @Test
    void publishCreatesImmutableSnapshot_andPlanFollowsCurrentPublished() {
        InspectionPoint point = newPoint();
        InspectionTemplate t = templateService.create(uniq("TPL"), "泵类巡检模板", "pump", "泵类通用",
                List.of(new ItemSpec("排气压力(MPa)", "numeric", 0.6, 0.8, "", "0.6~0.8 为正常"),
                        new ItemSpec("振动情况", "option", null, null, "无,轻微", "明显振动需检修")));

        // 新建模板只有草稿，不能直接被计划引用
        assertTrue(templateService.getDraft(t.getId()).isPresent());
        assertTrue(templateService.getCurrentPublished(t.getId()).isEmpty());
        IllegalArgumentException noPublished = assertThrows(IllegalArgumentException.class,
                () -> planService.create(planSpec(uniq("PLAN"), t.getId(), point.getId())));
        assertTrue(noPublished.getMessage().contains("已发布"));

        // 第一次发布：v1
        InspectionTemplateVersion v1 = templateService.publish(t.getId(), "王工");
        assertEquals(1, v1.getVersionNo());
        assertEquals(InspectionTemplateVersion.STATUS_PUBLISHED, v1.getStatus());
        assertEquals("王工", v1.getPublishedBy());
        assertNotNull(v1.getPublishedAt());
        assertTrue(templateService.getDraft(t.getId()).isEmpty(), "发布后草稿被消耗");

        // 计划明确引用当前发布版
        InspectionPlan plan = planService.create(planSpec(uniq("PLAN"), t.getId(), point.getId()));
        assertEquals(v1.getId(), plan.getTemplateVersionId());

        // 修改模板 -> 自动从当前发布版克隆草稿；v1 快照保持不变
        templateService.update(t.getId(), null, null, null,
                List.of(new ItemSpec("排气压力(MPa)", "numeric", 0.7, 0.9, "", "0.7~0.9 为正常"),
                        new ItemSpec("振动情况", "option", null, null, "无,轻微", "明显振动需检修"),
                        new ItemSpec("运行电流(A)", "numeric", 10.0, 45.0, "", "额定范围内")));
        List<InspectionTemplateVersionItem> v1Items = templateService.getVersionItems(v1.getId());
        assertEquals(2, v1Items.size(), "已发布版本项目数不变");
        assertEquals(0.6, v1Items.get(0).getNormalMin(), "已发布版本阈值不被草稿修改影响");
        assertEquals(0.8, v1Items.get(0).getNormalMax());

        // 第二次发布：v2 递增，v1 停用，计划推进到 v2
        InspectionTemplateVersion v2 = templateService.publish(t.getId(), "王工");
        assertEquals(2, v2.getVersionNo());
        assertEquals(InspectionTemplateVersion.STATUS_DEPRECATED,
                templateService.listVersions(t.getId()).stream()
                        .filter(v -> v.getId().equals(v1.getId())).findFirst().orElseThrow().getStatus());
        assertEquals(v2.getId(), planService.getById(plan.getId()).orElseThrow().getTemplateVersionId());

        // 版本差异：阈值变化 + 新增项
        VersionDiff diff = templateService.diffVersions(v1.getId(), v2.getId());
        assertEquals(1, diff.added().size());
        assertEquals("运行电流(A)", diff.added().get(0).name());
        assertEquals(0, diff.removed().size());
        assertEquals(1, diff.changed().size());
        assertEquals("排气压力(MPa)", diff.changed().get(0).name());
        assertTrue(diff.changed().get(0).changes().stream()
                .anyMatch(c -> c.field().equals("normalMin") && c.oldValue().equals(0.6) && c.newValue().equals(0.7)));
        assertTrue(diff.changed().get(0).changes().stream()
                .anyMatch(c -> c.field().equals("normalMax") && c.oldValue().equals(0.8) && c.newValue().equals(0.9)));
        assertEquals(1, diff.unchangedCount());
    }

    @Test
    void publishValidatesItemOrderNumericRangeAndQualifiedOptions() {
        // 数值下限大于上限
        InspectionTemplate t1 = templateService.create(uniq("TPL"), "校验1", "pump", "",
                List.of(new ItemSpec("压力", "numeric", 0.9, 0.6, "", "")));
        assertPublishFails(t1.getId(), "下限不能大于上限");

        // 数值型缺少上下限
        InspectionTemplate t2 = templateService.create(uniq("TPL"), "校验2", "pump", "",
                List.of(new ItemSpec("压力", "numeric", null, null, "", "")));
        assertPublishFails(t2.getId(), "至少设置一个上下限");

        // 选项型缺少合格选项
        InspectionTemplate t3 = templateService.create(uniq("TPL"), "校验3", "pump", "",
                List.of(new ItemSpec("振动", "option", null, null, "", "")));
        assertPublishFails(t3.getId(), "合格选项");

        // 项目顺序重复
        InspectionTemplate t4 = templateService.create(uniq("TPL"), "校验4", "pump", "",
                List.of(new ItemSpec("项目A", "text", 1, null, null, "", ""),
                        new ItemSpec("项目B", "text", 1, null, null, "", "")));
        assertPublishFails(t4.getId(), "顺序重复");

        // 空项目
        InspectionTemplate t5 = templateService.create(uniq("TPL"), "校验5", "pump", "", List.of());
        assertPublishFails(t5.getId(), "不能为空");

        // 校验失败的草稿仍可修正后发布
        templateService.update(t1.getId(), null, null, null,
                List.of(new ItemSpec("压力", "numeric", 0.6, 0.9, "", "")));
        InspectionTemplateVersion v = templateService.publish(t1.getId(), "王工");
        assertEquals(1, v.getVersionNo());
    }

    private void assertPublishFails(Long templateId, String messageFragment) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> templateService.publish(templateId, "tester"));
        assertTrue(ex.getMessage().contains(messageFragment),
                "期望错误信息包含[" + messageFragment + "]，实际：" + ex.getMessage());
    }

    @Test
    void deprecateAndDeleteGuards_protectReferencedVersions() {
        InspectionPoint point = newPoint();
        InspectionTemplate t = templateService.create(uniq("TPL"), "保护测试", "pump", "",
                List.of(new ItemSpec("压力", "numeric", 0.6, 0.8, "", "")));
        InspectionTemplateVersion v1 = templateService.publish(t.getId(), "王工");
        InspectionPlan plan = planService.create(planSpec(uniq("PLAN"), t.getId(), point.getId()));

        // 启用中的计划引用 -> 不可停用
        IllegalArgumentException depFail = assertThrows(IllegalArgumentException.class,
                () -> templateService.deprecateVersion(v1.getId()));
        assertTrue(depFail.getMessage().contains("启用中的计划"));

        // 当前发布版 -> 不可删除
        IllegalArgumentException delCurrent = assertThrows(IllegalArgumentException.class,
                () -> templateService.deleteVersion(v1.getId()));
        assertTrue(delCurrent.getMessage().contains("当前发布版本"));

        // 停用计划后可停用版本；被（停用的）计划引用仍不可物理删除
        planService.setEnabled(plan.getId(), false);
        templateService.deprecateVersion(v1.getId());
        VersionReferences refs = templateService.getVersionReferences(v1.getId());
        assertEquals(1, refs.plans().size());
        assertFalse(refs.deletable());
        IllegalArgumentException delRef = assertThrows(IllegalArgumentException.class,
                () -> templateService.deleteVersion(v1.getId()));
        assertTrue(delRef.getMessage().contains("被计划引用"));

        // 解除引用后可删除
        planService.delete(plan.getId());
        templateService.deleteVersion(v1.getId());
        assertTrue(templateService.listVersions(t.getId()).isEmpty());

        // 模板本身被计划引用时不可删除
        InspectionTemplate t2 = templateService.create(uniq("TPL"), "保护测试2", "pump", "",
                List.of(new ItemSpec("压力", "numeric", 0.6, 0.8, "", "")));
        templateService.publish(t2.getId(), "王工");
        planService.create(planSpec(uniq("PLAN"), t2.getId(), point.getId()));
        IllegalArgumentException delTpl = assertThrows(IllegalArgumentException.class,
                () -> templateService.delete(t2.getId()));
        assertTrue(delTpl.getMessage().contains("被计划引用"));
    }

    @Test
    void draftIsEditableAndDeletable_publishedVersionIsNot() {
        InspectionTemplate t = templateService.create(uniq("TPL"), "草稿测试", "pump", "",
                List.of(new ItemSpec("压力", "numeric", 0.6, 0.8, "", "")));
        InspectionTemplateVersion draft = templateService.getDraft(t.getId()).orElseThrow();

        // 草稿可反复修改
        templateService.update(t.getId(), null, null, null,
                List.of(new ItemSpec("压力", "numeric", 0.5, 0.9, "", "")));
        List<InspectionTemplateVersionItem> draftItems = templateService.getVersionItems(draft.getId());
        assertEquals(0.5, draftItems.get(0).getNormalMin());

        // 草稿可直接删除
        templateService.deleteVersion(draft.getId());
        assertTrue(templateService.getDraft(t.getId()).isEmpty());

        // 发布后版本不可再当草稿删除/修改
        templateService.update(t.getId(), null, null, null,
                List.of(new ItemSpec("压力", "numeric", 0.6, 0.8, "", "")));
        InspectionTemplateVersion v1 = templateService.publish(t.getId(), "王工");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> templateService.deleteVersion(v1.getId()));
        assertTrue(ex.getMessage().contains("当前发布版本"));
    }
}
