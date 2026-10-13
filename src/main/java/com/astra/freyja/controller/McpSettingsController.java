package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.mcp.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/system/mcp")
@RequiredArgsConstructor
public class McpSettingsController {
    private final McpRuntimeControl control;
    @GetMapping public R<Map<String, Object>> get() { return R.ok(control.view()); }
    @PutMapping public R<Map<String, Object>> save(@RequestBody McpRuntimeSettings.Update input) { control.update(input); return R.ok(control.view()); }
    @PutMapping("/enabled") public R<Map<String, Object>> enabled(@RequestBody McpRuntimeSettings.Switch input) { control.switchServer(input); return R.ok(control.view()); }
    @PutMapping("/tools/{name}") public R<Map<String, Object>> tool(@PathVariable String name, @RequestBody McpRuntimeSettings.Switch input) { control.switchTool(name, input); return R.ok(control.view()); }
}
