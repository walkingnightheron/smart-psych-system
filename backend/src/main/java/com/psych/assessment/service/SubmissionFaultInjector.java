package com.psych.assessment.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controllable fault-injection hook for REQ06 atomic rollback verification.
 * Disabled by default (-1). When failAtItemIndex >= 0, throws RuntimeException
 * right before inserting that 0-based item index so tests can prove answer_sheet rolls back 100%.
 */
@Component
public class SubmissionFaultInjector {

    private final AtomicInteger failAtItemIndex = new AtomicInteger(-1);

    public void setFailAtItemIndex(int index) {
        failAtItemIndex.set(index);
    }

    public int getFailAtItemIndex() {
        return failAtItemIndex.get();
    }

    public void reset() {
        failAtItemIndex.set(-1);
    }

    public void checkBeforeItemInsert(int currentZeroBasedIndex) {
        int target = failAtItemIndex.get();
        if (target >= 0 && currentZeroBasedIndex == target) {
            throw new IllegalStateException("模拟答卷作答明细写入中途异常(index=" + currentZeroBasedIndex + ")，验证整单原子回滚");
        }
    }
}
