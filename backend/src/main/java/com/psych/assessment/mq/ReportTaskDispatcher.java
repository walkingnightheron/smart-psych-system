package com.psych.assessment.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;

@Slf4j
@Service
public class ReportTaskDispatcher {

    private final ObjectProvider<RabbitTemplate> rabbitTemplateProvider;
    private final ReportConsumerService consumerService;
    private final DispatchErrorRecorder dispatchErrorRecorder;
    private final MqFaultInjector faultInjector;
    private final Executor reportTaskExecutor;

    private volatile String transportMode;

    public ReportTaskDispatcher(
            ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
            ReportConsumerService consumerService,
            DispatchErrorRecorder dispatchErrorRecorder,
            MqFaultInjector faultInjector,
            @Qualifier("reportTaskExecutor") Executor reportTaskExecutor,
            @Value("${app.mq.transport:rabbit}") String transportMode) {
        this.rabbitTemplateProvider = rabbitTemplateProvider;
        this.consumerService = consumerService;
        this.dispatchErrorRecorder = dispatchErrorRecorder;
        this.faultInjector = faultInjector;
        this.reportTaskExecutor = reportTaskExecutor;
        this.transportMode = transportMode;
    }

    public void setTransportMode(String transportMode) {
        this.transportMode = transportMode;
    }

    public String getTransportMode() {
        return transportMode;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSheetSubmittedAfterCommit(SheetSubmittedEvent event) {
        log.info("AFTER_COMMIT event triggered for sheetId={}, reportId={}", event.getSheetId(), event.getReportId());
        dispatch(event.getSheetId());
    }

    public boolean dispatch(Long sheetId) {
        try {
            faultInjector.checkBeforeSend(sheetId);
            sendToTransport(sheetId);
            return true;
        } catch (Exception ex) {
            String errMsg = "MQ消息投递失败: " + ex.getMessage();
            log.error("Failed to dispatch report task for sheetId={}, recording last_error without rolling back sheet",
                    sheetId, ex);
            try {
                dispatchErrorRecorder.record(sheetId, errMsg);
            } catch (Exception persistEx) {
                log.error("Failed to persist last_error for sheetId={} in a new transaction", sheetId, persistEx);
            }
            return false;
        }
    }

    public void dispatchRetry(Long sheetId) {
        dispatch(sheetId);
    }

    private void sendToTransport(Long sheetId) {
        String payload = String.valueOf(sheetId);
        if ("sync".equalsIgnoreCase(transportMode)) {
            consumerService.consumeReportTask(sheetId);
            return;
        }
        if ("async".equalsIgnoreCase(transportMode)) {
            reportTaskExecutor.execute(() -> consumerService.consumeReportTask(sheetId));
            return;
        }
        // Default: real RabbitMQ transport ("rabbit" or "rabbit-with-fallback")
        RabbitTemplate rabbitTemplate = rabbitTemplateProvider.getIfAvailable();
        if (rabbitTemplate == null) {
            if ("rabbit-with-fallback".equalsIgnoreCase(transportMode)) {
                reportTaskExecutor.execute(() -> consumerService.consumeReportTask(sheetId));
                return;
            }
            throw new IllegalStateException("RabbitTemplate未就绪，无法投递消息");
        }
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.REPORT_EXCHANGE,
                    RabbitMqConfig.REPORT_ROUTING_KEY,
                    payload
            );
        } catch (Exception ex) {
            if ("rabbit-with-fallback".equalsIgnoreCase(transportMode)) {
                log.warn("RabbitMQ broker unreachable ({}), falling back to local async executor", ex.getMessage());
                reportTaskExecutor.execute(() -> consumerService.consumeReportTask(sheetId));
                return;
            }
            throw ex;
        }
    }
}
