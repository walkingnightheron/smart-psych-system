package com.psych.assessment.controller;

import com.psych.assessment.cache.CacheService;
import com.psych.assessment.cache.CacheService.CacheMetricsVO;
import com.psych.assessment.common.Result;
import com.psych.assessment.mq.MqFaultInjector;
import com.psych.assessment.mq.ReportConsumerService;
import com.psych.assessment.mq.ReportTaskDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminCacheAndOpsController {

    private final CacheService cacheService;
    private final MqFaultInjector mqFaultInjector;
    private final ReportTaskDispatcher reportTaskDispatcher;
    private final ReportConsumerService reportConsumerService;

    @GetMapping("/cache/metrics")
    public Result<CacheMetricsVO> getCacheMetrics() {
        return Result.ok(cacheService.getMetrics());
    }

    @DeleteMapping("/cache/evict")
    public Result<Map<String, Object>> evictCache(@RequestParam(value = "key", required = false) String key) {
        int evicted;
        String scope;
        if (key != null && !key.isBlank()) {
            evicted = cacheService.evictKey(key.trim());
            scope = key.trim();
        } else {
            evicted = cacheService.evictAll();
            scope = "psych:*";
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", scope);
        data.put("evictedCount", evicted);
        return Result.ok(data);
    }

    @GetMapping("/mq/status")
    public Result<Map<String, Object>> getMqStatus() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transportMode", reportTaskDispatcher.getTransportMode());
        data.put("simulateSendFailure", mqFaultInjector.isSimulateSendFailure());
        data.put("simulateConsumeFailure", mqFaultInjector.isSimulateConsumeFailure());
        data.put("idempotentSkipCount", reportConsumerService.getIdempotentSkipCount());
        return Result.ok(data);
    }

    @PostMapping("/mq/fault")
    public Result<Map<String, Object>> configureMqFault(
            @RequestParam(value = "sendFailure", required = false) Boolean sendFailure,
            @RequestParam(value = "consumeFailure", required = false) Boolean consumeFailure,
            @RequestParam(value = "reset", defaultValue = "false") boolean reset) {
        if (reset) {
            mqFaultInjector.resetAll();
        } else {
            if (sendFailure != null) {
                mqFaultInjector.setSimulateSendFailure(sendFailure);
            }
            if (consumeFailure != null) {
                mqFaultInjector.setSimulateConsumeFailure(consumeFailure);
            }
        }
        return getMqStatus();
    }
}
