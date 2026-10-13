package com.astra.freyja.controller;

import com.astra.freyja.capability.model.CapabilityModels.*;
import com.astra.freyja.capability.service.CapabilityCatalogService;
import com.astra.freyja.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/system/ai-capabilities")
@RequiredArgsConstructor
public class CapabilityController {
    private final CapabilityCatalogService catalog;
    @GetMapping public R<Map<String, Object>> get() { return R.ok(catalog.view()); }
    @PutMapping("/draft") public R<Map<String, Object>> save(@RequestBody Edit input) { catalog.save(input); return R.ok(catalog.view()); }
    @PostMapping("/publish") public R<Map<String, Object>> publish(@RequestBody Publish input) { catalog.publish(input); return R.ok(catalog.view()); }
    @PostMapping("/rollback") public R<Map<String, Object>> rollback(@RequestBody Rollback input) { catalog.rollback(input); return R.ok(catalog.view()); }
}
