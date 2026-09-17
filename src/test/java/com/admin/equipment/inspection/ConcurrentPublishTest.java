package com.admin.equipment.inspection;

import com.admin.equipment.model.inspection.InspectionTemplate;
import com.admin.equipment.model.inspection.InspectionTemplateVersion;
import com.admin.equipment.service.inspection.InspectionTemplateService;
import com.admin.equipment.service.inspection.InspectionTemplateService.ItemSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发发布：同一模板的草稿被多个线程同时发布时，
 * 通过模板行悲观锁串行化，只有一个成功并拿到递增版本号。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentPublishTest {

    @Autowired
    private InspectionTemplateService templateService;

    private record RaceResult(int successCount, List<Throwable> errors) {}

    @Test
    void concurrentPublish_onlyOneSucceeds_withIncrementingVersionNo() throws Exception {
        InspectionTemplate t = templateService.create(
                "TPL-CONC-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                "并发发布测试模板", "pump", "",
                List.of(new ItemSpec("排气压力(MPa)", "numeric", 0.6, 0.8, "", "0.6~0.8 为正常")));

        // 第一轮：8 个线程同时发布同一草稿，只能成功一个，版本号为 1
        RaceResult r1 = race(t.getId(), 8);
        assertEquals(1, r1.successCount(), "并发发布只能成功一个，失败信息：" + r1.errors());
        assertEquals(7, r1.errors().size());
        List<InspectionTemplateVersion> versions = templateService.listVersions(t.getId());
        assertEquals(1, versions.size());
        assertEquals(Integer.valueOf(1), versions.get(0).getVersionNo());
        assertEquals(InspectionTemplateVersion.STATUS_PUBLISHED, versions.get(0).getStatus());

        // 第二轮：产生新草稿后再次并发发布，仍只有一个成功，版本号递增为 2
        templateService.update(t.getId(), null, null, null,
                List.of(new ItemSpec("排气压力(MPa)", "numeric", 0.7, 0.9, "", "0.7~0.9 为正常")));
        RaceResult r2 = race(t.getId(), 4);
        assertEquals(1, r2.successCount(), "第二轮并发发布也只能成功一个，失败信息：" + r2.errors());
        assertEquals(3, r2.errors().size());

        versions = templateService.listVersions(t.getId());
        assertEquals(2, versions.size());
        assertEquals(Integer.valueOf(2), versions.get(0).getVersionNo());
        assertEquals(InspectionTemplateVersion.STATUS_PUBLISHED, versions.get(0).getStatus());
        assertEquals(Integer.valueOf(1), versions.get(1).getVersionNo());
        assertEquals(InspectionTemplateVersion.STATUS_DEPRECATED, versions.get(1).getStatus());
    }

    private RaceResult race(Long templateId, int threads) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    templateService.publish(templateId, "tester");
                    success.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    errors.add(e);
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未就绪");
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发发布未在超时内结束");
        return new RaceResult(success.get(), errors);
    }
}
