package com.example.demo.controller;

import com.example.demo.model.ExplainRequest;
import com.example.demo.service.ExplainResultService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ExplainResultController {

    private final ExplainResultService explainResultService;

    public ExplainResultController(ExplainResultService explainResultService) {
        this.explainResultService = explainResultService;
    }

    @PostMapping("/explain-result")
    public String explainResult(@RequestBody ExplainRequest request) {
        return explainResultService.explainResult(request);
    }
}
