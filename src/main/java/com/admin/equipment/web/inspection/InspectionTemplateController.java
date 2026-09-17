package com.admin.equipment.web.inspection;

import com.admin.equipment.model.inspection.InspectionTemplate;
import com.admin.equipment.model.inspection.InspectionTemplateVersion;
import com.admin.equipment.model.inspection.InspectionTemplateVersionItem;
import com.admin.equipment.service.inspection.InspectionTemplateService;
import com.admin.equipment.service.inspection.InspectionTemplateService.ItemSpec;
import com.admin.equipment.service.inspection.InspectionTemplateService.VersionDetail;
import com.admin.equipment.service.inspection.InspectionTemplateService.VersionDiff;
import com.admin.equipment.service.inspection.InspectionTemplateService.VersionReferences;
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
                                   String description, List<ItemSpec> items) {}

    public record PublishRequest(String operator) {}

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

    /** 当前发布版项目快照；无发布版时回退旧模板项（兼容未迁移数据）。 */
    @GetMapping("/{id}/items")
    public ResponseEntity<?> listItems(@PathVariable Long id) {
        if (!service.getById(id).isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "模板不存在"));
        }
        List<InspectionTemplateVersionItem> published = service.getCurrentPublishedItems(id);
        if (!published.isEmpty()) {
            return ResponseEntity.ok(published);
        }
        return ResponseEntity.ok(service.getItems(id));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody TemplateRequest req) {
        try {
            InspectionTemplate t = service.create(req.code(), req.name(), req.equipmentType(),
                    req.description(), req.items());
            return ResponseEntity.status(HttpStatus.CREATED).body(t);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    /** 更新模板头信息并维护草稿；已发布版本快照不受影响。 */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody TemplateRequest req) {
        try {
            InspectionTemplate t = service.update(id, req.name(), req.equipmentType(),
                    req.description(), req.items());
            return ResponseEntity.ok(t);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
        }
    }

    // ---------- 版本流程：草稿 -> 发布 -> 停用 ----------

    @GetMapping("/{id}/versions")
    public ResponseEntity<?> listVersions(@PathVariable Long id) {
        if (!service.getById(id).isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "模板不存在"));
        }
        return ResponseEntity.ok(service.listVersions(id));
    }

    @GetMapping("/{id}/draft")
    public ResponseEntity<?> getDraft(@PathVariable Long id) {
        if (!service.getById(id).isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "模板不存在"));
        }
        InspectionTemplateVersion draft = service.getDraft(id).orElse(null);
        List<InspectionTemplateVersionItem> items = draft != null ? service.getVersionItems(draft.getId()) : List.of();
        return ResponseEntity.ok(new VersionDetail(draft, items));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<?> publish(@PathVariable Long id, @RequestBody(required = false) PublishRequest req) {
        try {
            String operator = req != null ? req.operator() : null;
            InspectionTemplateVersion v = service.publish(id, operator);
            return ResponseEntity.ok(v);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @GetMapping("/versions/{versionId}")
    public ResponseEntity<?> getVersion(@PathVariable Long versionId) {
        try {
            return ResponseEntity.ok(service.getVersionDetail(versionId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
        }
    }

    @GetMapping("/versions/diff")
    public ResponseEntity<?> diffVersions(@RequestParam Long from, @RequestParam Long to) {
        try {
            VersionDiff diff = service.diffVersions(from, to);
            return ResponseEntity.ok(diff);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @GetMapping("/versions/{versionId}/references")
    public ResponseEntity<?> versionReferences(@PathVariable Long versionId) {
        try {
            VersionReferences refs = service.getVersionReferences(versionId);
            return ResponseEntity.ok(refs);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/versions/{versionId}/deprecate")
    public ResponseEntity<?> deprecateVersion(@PathVariable Long versionId) {
        try {
            InspectionTemplateVersion v = service.deprecateVersion(versionId);
            return ResponseEntity.ok(v);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @DeleteMapping("/versions/{versionId}")
    public ResponseEntity<?> deleteVersion(@PathVariable Long versionId) {
        try {
            service.deleteVersion(versionId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }
}
