package com.admin.equipment.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 历史数据迁移（只执行一次、幂等）：
 * 把版本化改造前“模板直接挂项目”的存量数据，升级为“模板 + 不可变 v1 版本 + 项目快照”。
 *
 * - 为每个存量模板生成 v1 发布版本，原项目行改挂到该版本（template_id 列保留但不再使用）；
 * - 计划补模板编号（仍跟随当前发布版）；
 * - 已生成的任务冻结到 v1；
 * - 历史记录补版本引用与阈值/合格选项快照，保证老任务继续按 v1 判定、记录可追溯；
 * - 模板状态置为 active。
 *
 * 全新数据库的 inspection_template_items 没有 template_id 列，探测 SQL 会抛异常，
 * 此时直接跳过（DataSeeder 会走新的 草稿→发布 流程）。
 */
@Component
@Profile("!test")
@Order(0)
public class TemplateVersionMigration implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TemplateVersionMigration.class);
    private static final String PUBLISHER = "系统迁移(历史模板)";

    private final JdbcTemplate jdbc;

    public TemplateVersionMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        if (!legacySchemaPresent()) {
            return;
        }
        List<Long> templateIds = jdbc.queryForList(
                "SELECT t.id FROM inspection_templates t "
                        + "WHERE t.draft_json IS NULL AND (t.current_version IS NULL OR t.status IS NULL) "
                        + "AND EXISTS (SELECT 1 FROM inspection_template_items i WHERE i.template_id = t.id)",
                Long.class);
        if (templateIds.isEmpty()) {
            log.info("模板版本化：无待迁移的历史模板");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int migrated = 0;
        for (Long tplId : templateIds) {
            migrateOne(tplId, now);
            migrated++;
        }
        log.info("模板版本化迁移完成：{} 个历史模板已生成不可变 v1 版本", migrated);
    }

    private void migrateOne(Long tplId, LocalDateTime now) {
        Map<String, Object> tpl = jdbc.queryForMap(
                "SELECT code, name, equipment_type, description, created_at FROM inspection_templates WHERE id = ?",
                tplId);
        Integer itemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inspection_template_items WHERE template_id = ? AND version_id IS NULL",
                Integer.class, tplId);
        if (itemCount == null || itemCount == 0) return;

        Integer maxNo = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM inspection_template_versions WHERE template_id = ?",
                Integer.class, tplId);
        int versionNo = (maxNo == null ? 0 : maxNo) + 1;

        jdbc.update("INSERT INTO inspection_template_versions "
                        + "(template_id, version_no, name, equipment_type, description, status, item_count, "
                        + "published_at, published_by, change_summary) VALUES (?,?,?,?,?,'published',?,?,?,?)",
                tplId, versionNo, tpl.get("name"), nz(tpl.get("equipment_type")), nz(tpl.get("description")),
                itemCount, tpl.getOrDefault("created_at", now), PUBLISHER, "由改造前的历史模板快照迁移");

        Long versionId = jdbc.queryForObject(
                "SELECT id FROM inspection_template_versions WHERE template_id = ? AND version_no = ?",
                Long.class, tplId, versionNo);

        // 历史项目行改挂 v1
        jdbc.update("UPDATE inspection_template_items SET version_id = ? "
                + "WHERE template_id = ? AND version_id IS NULL", versionId, tplId);

        // 计划：跟随当前发布版（显式版本列保持 NULL），补编号与展示版本号
        jdbc.update("UPDATE inspection_plans SET template_code = ?, template_version_no = ? "
                + "WHERE template_id = ? AND template_version_id IS NULL", tpl.get("code"), versionNo, tplId);

        // 已生成的任务冻结到 v1
        jdbc.update("UPDATE inspection_tasks SET template_version_id = ?, template_version_no = ? "
                + "WHERE template_id = ? AND template_version_id IS NULL", versionId, versionNo, tplId);

        // 历史记录：从（已成为 v1 快照的）项目行回填阈值与合格选项
        jdbc.update("UPDATE inspection_records r JOIN inspection_template_items i ON r.template_item_id = i.id "
                + "SET r.normal_min = i.normal_min, r.normal_max = i.normal_max, "
                + "r.qualified_options = i.qualified_options, r.judge_criteria = i.judge_criteria "
                + "WHERE r.template_version_id IS NULL");

        // 历史记录：经任务关联冻结版本
        jdbc.update("UPDATE inspection_records r JOIN inspection_tasks t ON r.task_id = t.id "
                + "SET r.template_version_id = t.template_version_id, r.template_version_no = t.template_version_no "
                + "WHERE r.template_version_id IS NULL AND t.template_version_id IS NOT NULL");

        jdbc.update("UPDATE inspection_templates SET status = 'active', current_version = ? WHERE id = ?",
                versionNo, tplId);
    }

    /** 探测是否为改造前的老结构（items 表仍有 template_id 列）。 */
    private boolean legacySchemaPresent() {
        try {
            jdbc.queryForObject(
                    "SELECT COUNT(*) FROM inspection_template_items WHERE template_id IS NOT NULL",
                    Integer.class);
            return true;
        } catch (BadSqlGrammarException e) {
            return false;
        }
    }

    private String nz(Object v) {
        return v == null ? "" : v.toString();
    }
}
