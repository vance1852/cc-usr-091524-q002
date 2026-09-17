-- 版本化改造：巡检记录改由 template_version_item_id 追溯版本项目快照，
-- 旧列 template_item_id 仅保留历史数据，放宽 NOT NULL 约束。
-- 首次启动表尚不存在时该语句会失败，由 spring.sql.init.continue-on-error 忽略，
-- Hibernate 会按实体定义（可空）创建新列。
ALTER TABLE inspection_records MODIFY COLUMN template_item_id BIGINT NULL;
