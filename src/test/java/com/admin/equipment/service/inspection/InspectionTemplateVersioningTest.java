package com.admin.equipment.service.inspection;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.inspection.*;
import com.admin.equipment.repo.AppUserRepository;
import com.admin.equipment.repo.inspection.*;
import com.admin.equipment.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模板草稿/发布/停用版本流程的集成测试（H2 内存库）。
 * 覆盖：发布快照不可变、发布校验、版本差异、引用保护、任务生成时冻结版本、
 * 历史/在执行任务按旧版本判定、记录可追溯发布信息，以及并发发布只有一个递增版本成功。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InspectionTemplateVersioningTest {

    private static final String OPERATOR = "质量工程师-王工";

    @Autowired InspectionTemplateService templateService;
    @Autowired InspectionPlanService planService;
    @Autowired InspectionTaskService taskService;
    @Autowired InspectionTemplateRepository templateRepo;
    @Autowired InspectionTemplateVersionRepository versionRepo;
    @Autowired InspectionTemplateItemRepository itemRepo;
    @Autowired InspectionPointRepository pointRepo;
    @Autowired InspectionPlanRepository planRepo;
    @Autowired InspectionPlanPointRepository planPointRepo;
    @Autowired InspectionTaskRepository taskRepo;
    @Autowired InspectionTaskPointRepository taskPointRepo;
    @Autowired InspectionRecordRepository recordRepo;
    @Autowired AppUserRepository userRepo;
    @Autowired JwtUtil jwtUtil;
    @Autowired MockMvc mockMvc;

    @BeforeEach
    void clean() {
        recordRepo.deleteAll();
        taskPointRepo.deleteAll();
        taskRepo.deleteAll();
        planPointRepo.deleteAll();
        planRepo.deleteAll();
        pointRepo.deleteAll();
        itemRepo.deleteAll();
        versionRepo.deleteAll();
        templateRepo.deleteAll();
    }

    private List<InspectionTemplateService.ItemSpec> pumpItems(double min, double max) {
        return new ArrayList<>(List.of(
                new InspectionTemplateService.ItemSpec("排气压力(MPa)", "numeric", min, max,
                        "", "压力正常范围", 1),
                new InspectionTemplateService.ItemSpec("运行电流(A)", "numeric", 10.0, 45.0,
                        "", "额定电流范围内", 2),
                new InspectionTemplateService.ItemSpec("振动情况", "option", null, null,
                        "无,轻微,明显", "明显振动需检修", 3)
        ));
    }

    private Long createPublishedPumpTemplate(double min, double max) {
        Long id = templateService.create("TPL-PUMP-" + System.nanoTime(), "泵类巡检模板",
                "pump", "泵类设备巡检", pumpItems(min, max), OPERATOR).getId();
        templateService.publish(id, "初始发布", OPERATOR);
        return id;
    }

    private InspectionPoint createPoint() {
        InspectionPoint p = new InspectionPoint();
        p.setCode("IP-" + System.nanoTime());
        p.setName("动力站泵巡检点");
        p.setLocation("动力站");
        p.setCoordX(1.0);
        p.setCoordY(2.0);
        p.setEquipmentIds("");
        return pointRepo.save(p);
    }

    private InspectionPlan createPlanFollowingCurrent(Long templateId, Long pointId) {
        InspectionPlanService.PlanSpec spec = new InspectionPlanService.PlanSpec(
                "PLAN-" + System.nanoTime(), "泵巡检计划", templateId, null,
                "daily", 1, "day", "08:00", "10:00", 120,
                "甲班", "", "测试计划", List.of(pointId));
        return planService.create(spec);
    }

    /* ================= 草稿/发布/不可变 ================= */

    @Test
    void publish_createsImmutableSnapshot_andClearsDraft() {
        Long id = templateService.create("TPL-1", "模板", "pump", "d",
                pumpItems(0.6, 0.8), OPERATOR).getId();

        InspectionTemplate before = templateService.getById(id).orElseThrow();
        assertEquals("draft", before.getStatus());
        assertNull(before.getCurrentVersion());
        assertNotNull(before.getDraftJson());

        InspectionTemplateVersion v1 = templateService.publish(id, "首次发布", OPERATOR);
        assertEquals(1, v1.getVersionNo());
        assertEquals(3, v1.getItemCount());
        assertEquals(OPERATOR, v1.getPublishedBy());
        assertNotNull(v1.getPublishedAt());

        InspectionTemplate after = templateService.getById(id).orElseThrow();
        assertEquals("active", after.getStatus());
        assertEquals(1, after.getCurrentVersion());
        assertNull(after.getDraftJson(), "发布后草稿必须清空");

        // 再发布必须失败：没有新草稿
        assertThrows(IllegalStateException.class,
                () -> templateService.publish(id, "重复发布", OPERATOR));

        // 修改草稿并发布 v2，v1 快照保持不变（压力上限仍为 0.8）
        templateService.saveDraft(id, "泵类巡检模板", "pump", "d",
                pumpItems(0.6, 0.9), OPERATOR);
        InspectionTemplateVersion v2 = templateService.publish(id, "放宽压力上限到0.9", OPERATOR);
        assertEquals(2, v2.getVersionNo());

        List<InspectionTemplateItem> v1Items = itemRepo.findByVersionIdOrderBySortOrderAsc(v1.getId());
        assertEquals(0.8, v1Items.get(0).getNormalMax());
        List<InspectionTemplateItem> v2Items = itemRepo.findByVersionIdOrderBySortOrderAsc(v2.getId());
        assertEquals(0.9, v2Items.get(0).getNormalMax());
        // v1 项目行与 v2 完全独立
        assertNotEquals(v1Items.get(0).getId(), v2Items.get(0).getId());

        // 版本倒序排列
        List<InspectionTemplateVersion> versions = templateService.listVersions(id);
        assertEquals(List.of(2, 1), versions.stream().map(InspectionTemplateVersion::getVersionNo).toList());
    }

    @Test
    void disable_retireCurrentVersion_andEnableRestores() {
        Long id = createPublishedPumpTemplate(0.6, 0.8);
        templateService.disable(id);
        assertEquals("disabled", templateService.getById(id).orElseThrow().getStatus());
        InspectionTemplateVersion current = templateService.listVersions(id).get(0);
        assertEquals("retired", current.getStatus());
        assertTrue(templateService.getCurrentVersion(id).isEmpty(), "停用后不存在当前发布版");

        // 停用模板不能被计划引用
        InspectionPoint p = createPoint();
        assertThrows(IllegalArgumentException.class,
                () -> createPlanFollowingCurrent(id, p.getId()));

        templateService.enable(id);
        assertEquals("active", templateService.getById(id).orElseThrow().getStatus());
        assertEquals("published", templateService.getCurrentVersion(id).orElseThrow().getStatus());
    }

    /* ================= 发布校验 ================= */

    @Test
    void publish_rejectsInvalidNumericBounds() {
        Long id = templateService.create("TPL-BAD-1", "坏模板", "", "",
                pumpItems(0.9, 0.8), OPERATOR).getId();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> templateService.publish(id, "bad", OPERATOR));
        assertTrue(ex.getMessage().contains("下限"), ex.getMessage());
    }

    @Test
    void publish_rejectsDuplicatedAndGappedSortOrder() {
        Long id1 = templateService.create("TPL-BAD-2", "顺序重复", "", "", List.of(
                new InspectionTemplateService.ItemSpec("A", "option", null, null, "是,否", "", 1),
                new InspectionTemplateService.ItemSpec("B", "option", null, null, "是,否", "", 1)
        ), OPERATOR).getId();
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> templateService.publish(id1, "bad", OPERATOR)).getMessage().contains("不重复"));

        Long id2 = templateService.create("TPL-BAD-3", "顺序不连续", "", "", List.of(
                new InspectionTemplateService.ItemSpec("A", "option", null, null, "是,否", "", 1),
                new InspectionTemplateService.ItemSpec("B", "option", null, null, "是,否", "", 3)
        ), OPERATOR).getId();
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> templateService.publish(id2, "bad", OPERATOR)).getMessage().contains("连续"));
    }

    @Test
    void publish_rejectsBlankQualifiedOptionsAndDuplicateNames() {
        Long id1 = templateService.create("TPL-BAD-4", "选项缺失", "", "", List.of(
                new InspectionTemplateService.ItemSpec("A", "option", null, null, "", "无合格选项", 1)
        ), OPERATOR).getId();
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> templateService.publish(id1, "bad", OPERATOR)).getMessage().contains("合格选项"));

        Long id2 = templateService.create("TPL-BAD-5", "重名项目", "", "", List.of(
                new InspectionTemplateService.ItemSpec("A", "text", null, null, "", "", 1),
                new InspectionTemplateService.ItemSpec("A", "text", null, null, "", "", 2)
        ), OPERATOR).getId();
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> templateService.publish(id2, "bad", OPERATOR)).getMessage().contains("重复"));
    }

    /* ================= 版本差异 ================= */

    @Test
    void diff_reportsThresholdChange() {
        Long id = createPublishedPumpTemplate(0.6, 0.8);
        templateService.saveDraft(id, "泵类巡检模板", "pump", "d",
                pumpItems(0.6, 0.9), OPERATOR);
        templateService.publish(id, "压力上限调整", OPERATOR);

        InspectionTemplateService.VersionDiff diff = templateService.diff(id, 1, 2);
        List<InspectionTemplateService.ItemChange> pressure = diff.itemChanges().stream()
                .filter(c -> c.itemName().contains("排气压力")).toList();
        assertEquals(1, pressure.size());
        assertEquals("modified", pressure.get(0).changeType());
        assertTrue(pressure.get(0).changes().stream()
                .anyMatch(fc -> fc.field().equals("normalMax")
                        && Double.valueOf(0.8).equals(fc.from())
                        && Double.valueOf(0.9).equals(fc.to())));
    }

    /* ================= 引用保护 ================= */

    @Test
    void referencedVersion_cannotBeDeleted_andReferencesCounted() {
        Long tplId = createPublishedPumpTemplate(0.6, 0.8);
        InspectionTemplateVersion v1 = templateService.getCurrentVersion(tplId).orElseThrow();

        // 未引用时：模板本身已发布，物理删除整体被拒；引用计数为 0、deletable 标记仅作展示
        InspectionTemplateService.VersionReferences refs0 = templateService.references(v1.getId());
        assertEquals(0, refs0.planReferences());
        assertEquals(0, refs0.taskCount());
        assertEquals(0, refs0.recordCount());

        InspectionPoint point = createPoint();
        createPlanFollowingCurrent(tplId, point.getId());

        InspectionTemplateService.VersionReferences refs = templateService.references(v1.getId());
        assertTrue(refs.current());
        // 跟随当前发布版的计划没有显式版本ID，但在当前版本上计入有效引用
        assertEquals(0, refs.planReferences());
        assertEquals(1, refs.effectivePlanReferences());
        assertFalse(refs.deletable());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> templateService.delete(tplId));
        assertTrue(ex.getMessage().contains("发布版本") || ex.getMessage().contains("停用"));
    }

    /* ================= 任务冻结版本 + 历史判定不变 ================= */

    @Test
    void taskFreezesVersion_inProgressTaskKeepsOldCriteria_andNewTasksUseNewVersion() {
        Long tplId = createPublishedPumpTemplate(0.6, 0.8);
        InspectionTemplateVersion v1 = templateService.getCurrentVersion(tplId).orElseThrow();
        InspectionPoint point = createPoint();
        InspectionPlan plan = createPlanFollowingCurrent(tplId, point.getId());
        assertNull(plan.getTemplateVersionId(), "未显式指定版本时应跟随当前发布版");

        // 生成任务 A（v1 当前），并开始执行，但暂不录入结果
        InspectionTask taskA = taskService.generateTask(plan.getId(), null, "巡检员甲", false, null);
        assertEquals(v1.getId(), taskA.getTemplateVersionId());
        assertEquals(1, taskA.getTemplateVersionNo());
        taskService.startTask(taskA.getId(), "巡检员甲");

        // 质量工程师当天发布 v2：压力上限 0.8 -> 0.9
        templateService.saveDraft(tplId, "泵类巡检模板", "pump", "d",
                pumpItems(0.6, 0.9), OPERATOR);
        InspectionTemplateVersion v2 = templateService.publish(tplId, "调整泵类压力范围", OPERATOR);

        // 正在执行的任务 A 仍按 v1 判定：0.85 高于旧上限 0.8，应不合格
        Long v1PressureItemId = itemRepo.findByVersionIdOrderBySortOrderAsc(v1.getId()).get(0).getId();
        InspectionTaskService.PointExecuteResult resultA = taskService.executePoint(
                taskA.getId(),
                taskService.getTaskPoints(taskA.getId()).get(0).getId(),
                "巡检员甲",
                List.of(new InspectionTaskService.PointItemSpec(v1PressureItemId, "0.85", 0.85, "")),
                "");
        InspectionRecord recA = resultA.records().get(0);
        assertFalse(recA.getIsQualified(), "在执行任务必须按旧版本v1判定，0.85应不合格");
        assertEquals(v1.getId(), recA.getTemplateVersionId());
        assertEquals(0.8, recA.getNormalMax(), "记录上冻结的是旧阈值");

        // 新生成的任务 B 冻结 v2：同样 0.85 在新范围内，应合格
        InspectionTask taskB = taskService.generateTask(plan.getId(), null, "巡检员乙", false, null);
        assertEquals(v2.getId(), taskB.getTemplateVersionId());
        assertEquals(2, taskB.getTemplateVersionNo());
        taskService.startTask(taskB.getId(), "巡检员乙");
        Long v2PressureItemId = itemRepo.findByVersionIdOrderBySortOrderAsc(v2.getId()).get(0).getId();
        InspectionTaskService.PointExecuteResult resultB = taskService.executePoint(
                taskB.getId(),
                taskService.getTaskPoints(taskB.getId()).get(0).getId(),
                "巡检员乙",
                List.of(new InspectionTaskService.PointItemSpec(v2PressureItemId, "0.85", 0.85, "")),
                "");
        assertTrue(resultB.records().get(0).getIsQualified(), "新版本生效后生成的任务应按v2判定，0.85合格");
        assertEquals(0.9, resultB.records().get(0).getNormalMax());
    }

    /* ================= 记录追溯 ================= */

    @Test
    void recordDefinition_exposesThresholdsPublisherAndPublishTime_viaHttp() throws Exception {
        Long tplId = createPublishedPumpTemplate(0.6, 0.8);
        InspectionPoint point = createPoint();
        InspectionPlan plan = createPlanFollowingCurrent(tplId, point.getId());
        InspectionTask task = taskService.generateTask(plan.getId(), null, "巡检员", false, null);
        taskService.startTask(task.getId(), "巡检员");
        InspectionTemplateVersion v1 = templateService.getCurrentVersion(tplId).orElseThrow();
        Long itemId = itemRepo.findByVersionIdOrderBySortOrderAsc(v1.getId()).get(0).getId();
        Long recordId = taskService.executePoint(
                task.getId(), taskService.getTaskPoints(task.getId()).get(0).getId(),
                "巡检员",
                List.of(new InspectionTaskService.PointItemSpec(itemId, "0.7", 0.7, "")),
                "").records().get(0).getId();

        InspectionTemplateService.RecordDefinitionView view = templateService.getRecordDefinition(recordId);
        assertEquals("排气压力(MPa)", view.item().name());
        assertEquals(0.6, view.item().normalMin());
        assertEquals(0.8, view.item().normalMax());
        assertEquals(OPERATOR, view.publishedBy());
        assertNotNull(view.publishedAt());
        assertEquals(1, view.versionNo());

        AppUser user = new AppUser();
        user.setUsername("tester");
        user.setPasswordHash("x");
        user.setDisplayName("测试员");
        user = userRepo.save(user);
        String token = jwtUtil.createToken(user.getId(), user.getUsername());

        mockMvc.perform(get("/api/inspection/tasks/records/" + recordId + "/definition")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.publishedBy").value(OPERATOR))
                .andExpect(jsonPath("$.item.normalMax").value(0.8))
                .andExpect(jsonPath("$.publishedAt").exists());
    }

    /* ================= 并发发布 ================= */

    /**
     * 同一模板的同一份草稿被两个线程同时发布：
     * 悲观行锁串行化“取号-插入版本”，唯一约束兜底，
     * 结果必须恰好一个成功（版本号2），另一个失败，版本号不重复不跳跃。
     */
    @Test
    void concurrentPublish_onlyOneSucceedsWithIncrementingVersion() throws Exception {
        Long tplId = createPublishedPumpTemplate(0.6, 0.8);
        // 准备 v2 草稿（此时 currentVersion=1）
        templateService.saveDraft(tplId, "泵类巡检模板", "pump", "d",
                pumpItems(0.6, 0.9), OPERATOR);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        AtomicReference<InspectionTemplateVersion> winner = new AtomicReference<>();
        List<Throwable> errors = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    InspectionTemplateVersion v = templateService.publish(tplId, "并发发布", OPERATOR);
                    success.incrementAndGet();
                    winner.set(v);
                } catch (Throwable e) {
                    failure.incrementAndGet();
                    synchronized (errors) { errors.add(e); }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发发布应在超时前结束");

        assertEquals(1, success.get(), "并发发布只允许一个成功");
        assertEquals(1, failure.get(), "另一个必须失败（草稿已被发布）");
        assertEquals(2, winner.get().getVersionNo());
        assertEquals(2, templateService.getById(tplId).orElseThrow().getCurrentVersion());

        List<Integer> versionNos = versionRepo.findByTemplateIdOrderByVersionNoDesc(tplId).stream()
                .map(InspectionTemplateVersion::getVersionNo).toList();
        assertEquals(List.of(2, 1), versionNos, "版本号严格递增且无重复");
    }
}
