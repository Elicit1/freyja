package com.astra.freyja.controller;

import com.astra.freyja.agent.production.*;
import com.astra.freyja.common.R;
import com.astra.freyja.dto.agent.AgentRequests.Submission;
import com.astra.freyja.dto.agent.ProductionPreparationDTO.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/agent/production") @RequiredArgsConstructor
public class AgentProductionController {
    private final ProductionJobs jobs;
    private final ProductionPlans plans;
    @PostMapping("/start") public R<Submission> start(@RequestBody Start input) { return R.ok(jobs.start(input)); }
    @GetMapping("/runs/{id}/report") public R<Object> report(@PathVariable Long id) { return R.ok(jobs.report(id)); }
    @GetMapping("/shots/{id}/plan") public R<Object> plan(@PathVariable Long id) { return R.ok(plans.detail(id)); }
    @PostMapping("/refresh") public R<Object> refresh(@RequestBody Refresh input) { return R.ok(plans.refresh(input)); }
    @PutMapping("/shots/{id}/locks") public R<Object> locks(@PathVariable Long id, @RequestBody Locks input) { return R.ok(plans.lock(id, input)); }
}
