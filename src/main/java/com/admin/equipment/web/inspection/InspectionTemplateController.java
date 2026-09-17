package com.admin.equipment.web.inspection;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.inspection.InspectionTemplate;
import com.admin.equipment.model.inspection.InspectionTemplateItem;
import com.admin.equipment.model.inspection.InspectionTemplateVersion;
import com.admin.equipment.service.inspection.InspectionTemplateService;
import com.admin.equipment.service.inspection.InspectionTemplateService.DraftContent;
import com.admin.equipment.service.inspection.InspectionTemplateService.ItemSpec;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inspection/templates")
public class InspectionTemplateController {

    private final InspectionTemplateService service;

    public InspectionTemplateController(InspectionTemplateService service) {
        this.service = service;
    }

    public record TemplateRequest(String code, String name, String equipmentType,
                                   String description, List<ItemSpec> items, String operator) {}

    public record DraftRequest(String name, String equipmentType, String description,
                                List<ItemSpec> items, String operator) {}

    public record PublishRequest(String changeSummary, String operator) {}

    private String operator(HttpServletRequest request, String bodyOperator) {
        if (bodyOperator != null && !bodyOperator.isBlank()) return bodyOperator;
        Object u = request.getAttribute("currentUser");
        if (u instanceof AppUser user) {
            return user.getDisplayName() != null && !user.getDisplayName().isBlank()
                    ? user.getDisplayName() : user.getUsername();
        }
        return "";
    }

    @GetMapping
    public List<InspectionTemplate> list(@RequestParam(required = false) String equipmentType) {
        if (equipmentType != null && !equipmentType.isBlank()) {
            return service.listByEquipmentType(equipmentType);
        }
        return service.listAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return service.getById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "模板不存在")));
    }

    /* ---------------- 草稿 ---------------- */

    @GetMapping("/{id}/draft")
    public ResponseEntity<?> getDraft(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.getDraft(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
        }
    }

    /** 兼容旧接口：PUT 整体提交现在只保存草稿，不再直接覆盖已发布内容。 */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody DraftRequest req,
                                     HttpServletRequest http) {
        try {
            InspectionTemplate t = service.saveDraft(id, req.name(), req.equipmentType(),
                    req.description(), req.items(), operator(http, req.operator()));
            return ResponseEntity.ok(t);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PutMapping("/{id}/draft")
    public ResponseEntity<?> saveDraft(@PathVariable Long id, @RequestBody DraftRequest req,
                                        HttpServletRequest http) {
        try {
            InspectionTemplate t = service.saveDraft(id, req.name(), req.equipmentType(),
                    req.description(), req.items(), operator(http, req.operator()));
            return ResponseEntity.ok(t);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/{id}/draft/from-version")
    public ResponseEntity<?> draftFromVersion(@PathVariable Long id,
                                               @RequestParam(required = false) Integer versionNo,
                                               HttpServletRequest http) {
        try {
            return ResponseEntity.ok(service.draftFromVersion(id, versionNo, operator(http, null)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    /* ---------------- 发布 / 停用 / 启用 ---------------- */

    @PostMapping("/{id}/publish")
    public ResponseEntity<?> publish(@PathVariable Long id, @RequestBody(required = false) PublishRequest req,
                                      HttpServletRequest http) {
        try {
            String summary = req == null ? "" : req.changeSummary();
            String op = operator(http, req == null ? null : req.operator());
            InspectionTemplateVersion v = service.publish(id, summary, op);
            return ResponseEntity.status(HttpStatus.CREATED).body(v);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<?> disable(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.disable(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<?> enable(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.enable(id));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    /* ---------------- 版本、快照、差异、引用 ---------------- */

    @GetMapping("/{id}/versions")
    public ResponseEntity<?> listVersions(@PathVariable Long id) {
        if (service.getById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "模板不存在"));
        }
        return ResponseEntity.ok(service.listVersions(id));
    }

    @GetMapping("/{id}/versions/{versionNo}")
    public ResponseEntity<?> getVersion(@PathVariable Long id, @PathVariable Integer versionNo) {
        return service.getVersionByNo(id, versionNo)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "版本不存在")));
    }

    /** 当前发布版的完整项目快照（也兼容旧的 /items 调用）。 */
    @GetMapping("/{id}/items")
    public ResponseEntity<?> listItems(@PathVariable Long id) {
        if (service.getById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "模板不存在"));
        }
        return ResponseEntity.ok(service.getItems(id));
    }

    @GetMapping("/versions/{versionId}/items")
    public ResponseEntity<?> listVersionItems(@PathVariable Long versionId) {
        if (service.getVersion(versionId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "版本不存在"));
        }
        List<InspectionTemplateItem> items = service.getVersionItems(versionId);
        return ResponseEntity.ok(items);
    }

    @GetMapping("/{id}/diff")
    public ResponseEntity<?> diff(@PathVariable Long id,
                                   @RequestParam Integer from,
                                   @RequestParam Integer to) {
        try {
            return ResponseEntity.ok(service.diff(id, from, to));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @GetMapping("/versions/{versionId}/references")
    public ResponseEntity<?> references(@PathVariable Long versionId) {
        try {
            return ResponseEntity.ok(service.references(versionId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
        }
    }

    /* ---------------- 新建 / 删除 ---------------- */

    @PostMapping
    public ResponseEntity<?> create(@RequestBody TemplateRequest req, HttpServletRequest http) {
        try {
            InspectionTemplate t = service.create(req.code(), req.name(), req.equipmentType(),
                    req.description(), req.items(), operator(http, req.operator()));
            return ResponseEntity.status(HttpStatus.CREATED).body(t);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    /** 仅允许删除从未发布、未被计划引用的模板；其余情况请停用。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }
}
