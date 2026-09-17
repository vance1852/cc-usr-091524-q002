# 工业设备巡检与维保工单管理平台（纯后端）

工业设备台账、巡检与维保工单管理的纯后端 API 服务。

## 技术栈

- Java 17 + Spring Boot 3 + Spring Web
- Spring Data JPA + MySQL 8（字符集 utf8mb4）
- JWT 鉴权（jjwt，自定义过滤器）、PBKDF2 密码哈希（JDK 自带）

## 启动（Docker）

```bash
docker compose up --build
```

MySQL 就绪后，应用通过 JPA 自动建表（ddl-auto=update）并在启动时灌入种子数据，服务监听 `http://127.0.0.1:7654`。

## 内置账号

唯一管理员（本平台只有 admin 一个角色）：

- 用户名：`admin`
- 密码：`admin123`

## 已实现的基础功能

- 登录签发 JWT、获取当前用户（`/api/auth/login`、`/api/auth/me`）
- 设备台账增删改查（`/api/equipments`，编号唯一校验）
- 维保工单查询、创建、状态流转（`/api/work-orders`，完成时记录关闭时间）
- 巡检点、巡检模板与周期计划维护（`/api/inspection/points`、`/api/inspection/templates`、`/api/inspection/plans`）
- 巡检任务生成与执行、异常转工单、复检闭环和路线比较（`/api/inspection/tasks`）
- 巡检完成率、设备历史与执行轨迹查询（`/api/inspection/stats`）
- 仪表盘统计（`/api/dashboard/stats`）
- 健康检查（`/api/health`）

除 `login` 与 `health` 外，接口均需 `Authorization: Bearer <token>`。

## 巡检模板版本化（草稿 / 发布 / 停用）

模板（`code`）保持业务身份，维护流程改为版本化：

- `POST /api/inspection/templates` 创建模板并生成**草稿**版本；`PUT /api/inspection/templates/{id}` 修改头信息并维护草稿（无草稿时自动从当前发布版克隆），已发布版本快照不受影响。
- `POST /api/inspection/templates/{id}/publish` 发布草稿：校验项目顺序（正整数且不重复）、数值上下限（至少一个且下限≤上限）、合格选项（选项型必填）；生成递增版本号，原发布版自动停用，引用该模板的计划全部推进到新发布版。发布通过模板行悲观锁串行化，并发发布只有一个成功。
- `POST /api/inspection/templates/versions/{versionId}/deprecate` 停用发布版（仍有启用中的计划引用时拒绝）。
- `DELETE /api/inspection/templates/versions/{versionId}` 物理删除版本：当前发布版、被计划引用、已生成任务或已有巡检记录的版本一律拒绝。
- 查询：`GET /{id}/versions` 版本列表、`GET /{id}/draft` 当前草稿、`GET /versions/{versionId}` 版本详情（含项目快照）、`GET /versions/diff?from=&to=` 版本差异（新增/删除/逐项字段变化）、`GET /versions/{versionId}/references` 引用关系（计划、任务、记录数、可否删除）。
- 计划通过 `templateVersionId` 明确引用当前发布版；任务生成时把该版本冻结到 `templateVersionId`，执行与判定只读取冻结版本的项目快照，正在执行和已完成的任务不受后续发布影响。
- `GET /api/inspection/tasks/records/{recordId}/definition` 查看任一巡检记录的判定依据：项目定义、数值上下限、合格选项、判定标准，以及所属版本的发布人和发布时间。
- 历史数据由 `TemplateVersionMigration` 启动时自动迁移：旧模板项快照为 v1 发布版，并回填计划、任务与巡检记录的版本引用。

## 编码说明

数据库使用 utf8mb4，JDBC 连接显式指定 characterEncoding=utf8；Spring Boot 的 JSON 响应默认 UTF-8，中文不乱码。
