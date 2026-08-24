package com.example.demo.controller;

import com.example.demo.dto.RuntimeSkillApiEntry;
import com.example.demo.dto.RuntimeSkillDetail;
import com.example.demo.dto.RuntimeSkillSummary;
import com.example.demo.service.RuntimeSkillCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 与 Agent 传输协议无关的 Runtime Skill 只读发现 API。
 */
@RestController
@RequestMapping("/api/skills")
@Tag(name = "运行时 Skills", description = "观察 Skill 目录、渐进式正文和 API 索引")
public class RuntimeSkillController {

    private final RuntimeSkillCatalogService catalogService;

    public RuntimeSkillController(RuntimeSkillCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    @Operation(
        summary = "列出运行时 Skills",
        description = "返回不含正文的 Level 1 Skill 目录，按名称排序"
    )
    public ResponseEntity<List<RuntimeSkillSummary>> listSkills() {
        return ResponseEntity.ok(catalogService.summaries());
    }

    @GetMapping("/api-index")
    @Operation(
        summary = "读取 Skill API index",
        description = "返回由运行时 Skills 建立的稳定 method/path 白名单"
    )
    public ResponseEntity<Map<String, RuntimeSkillApiEntry>> apiIndex() {
        return ResponseEntity.ok(catalogService.apiIndex());
    }

    @GetMapping("/{name}")
    @Operation(
        summary = "读取单个 Skill",
        description = "返回指定 Skill 的 Level 2 正文和 Level 3 API reference 指针"
    )
    public ResponseEntity<RuntimeSkillDetail> getSkill(@PathVariable String name) {
        return ResponseEntity.ok(catalogService.detail(name));
    }
}
