package com.psych.assessment.mq;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controllable fault injection hook for REQ10 verification:
 * 1) simulateSendFailure: throws exception before message reaches consumer (verifying sheet stays committed & last_error recorded).
 * 2) simulateConsumeFailure: throws exception inside consumer calculation (verifying retryCount increment & bounded FAILED state).
 */
@Component
public class MqFaultInjector {

    private final AtomicBoolean simulateSendFailure = new AtomicBoolean(false);
    private final AtomicBoolean simulateConsumeFailure = new AtomicBoolean(false);
    private final AtomicInteger consumeFailTimesRemaining = new AtomicInteger(0);

    public void setSimulateSendFailure(boolean enabled) {
        simulateSendFailure.set(enabled);
    }

    public boolean isSimulateSendFailure() {
        return simulateSendFailure.get();
    }

    public void setSimulateConsumeFailure(boolean enabled) {
        simulateConsumeFailure.set(enabled);
        if (!enabled) {
            consumeFailTimesRemaining.set(0);
        }
    }

    public void failNextConsumptions(int times) {
        consumeFailTimesRemaining.set(Math.max(0, times));
    }

    public boolean isSimulateConsumeFailure() {
        return simulateConsumeFailure.get() || consumeFailTimesRemaining.get() > 0;
    }

    public void checkBeforeSend(Long sheetId) {
        if (simulateSendFailure.get()) {
            throw new IllegalStateException("模拟RabbitMQ Broker连接中断，sheetId=" + sheetId);
        }
    }

    public void checkDuringConsume(Long sheetId) {
        if (simulateConsumeFailure.get()) {
            throw new IllegalStateException("模拟异步计分引擎计算异常，sheetId=" + sheetId);
        }
        int remaining = consumeFailTimesRemaining.get();
        if (remaining > 0 && consumeFailTimesRemaining.compareAndSet(remaining, remaining - 1)) {
            throw new IllegalStateException("模拟受控消费瞬断异常(剩余" + (remaining - 1) + "次)，sheetId=" + sheetId);
        }
    }

    public void resetAll() {
        simulateSendFailure.set(false);
        simulateConsumeFailure.set(false);
        consumeFailTimesRemaining.set(0);
    }
}
