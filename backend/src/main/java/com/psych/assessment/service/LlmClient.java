package com.psych.assessment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * OpenAI 兼容 LLM HTTP 客户端（POST {baseUrl}/chat/completions）。
 * 安全红线：绝不记录/回传 apiKey；日志仅含 model、耗时与错误类型；异常消息经脱敏处理。
 * 声明为 @Component 便于集成测试 @MockBean 替换。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    private static final int MAX_CONNECT_TIMEOUT_SECONDS = 10;
    private static final int ERROR_SNIPPET_MAX_LENGTH = 200;

    private final ObjectMapper objectMapper;

    public String chat(String baseUrl, String apiKey, String model, double temperature, int maxTokens,
                       int timeoutSeconds, String systemPrompt, String userPrompt) {
        if (isBlank(baseUrl)) {
            throw new IllegalArgumentException("LLM baseUrl 未配置");
        }
        if (isBlank(apiKey)) {
            throw new IllegalArgumentException("LLM apiKey 未配置");
        }
        if (isBlank(model)) {
            throw new IllegalArgumentException("LLM model 未配置");
        }

        int readTimeout = timeoutSeconds <= 0 ? 30 : timeoutSeconds;
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(Math.min(readTimeout, MAX_CONNECT_TIMEOUT_SECONDS)))
                .withReadTimeout(Duration.ofSeconds(readTimeout));
        RestClient restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();

        Map<String, Object> requestBody = buildChatRequestBody(model, temperature, maxTokens, systemPrompt, userPrompt, true);

        String url = trimTrailingSlash(baseUrl.trim()) + "/chat/completions";
        long start = System.currentTimeMillis();
        try {
            String responseJson = postChat(restClient, url, apiKey, requestBody);
            long latencyMs = System.currentTimeMillis() - start;
            String content = extractContent(responseJson, apiKey);
            log.info("LLM chat success: model={}, latencyMs={}, replyLength={}", model, latencyMs, content.length());
            return content;
        } catch (RestClientResponseException ex) {
            String errBody = ex.getResponseBodyAsString();
            if (ex.getStatusCode().value() == 400 && isTemperatureConstraintError(errBody)) {
                boolean requireOne = errBody != null && errBody.toLowerCase().contains("only 1");
                Map<String, Object> retryBody = requireOne
                        ? buildChatRequestBody(model, 1.0, maxTokens, systemPrompt, userPrompt, true)
                        : buildChatRequestBody(model, temperature, maxTokens, systemPrompt, userPrompt, false);
                log.info("LLM model={} rejected temperature={}, retrying with {}",
                        model, temperature, requireOne ? "temperature=1.0" : "omitted temperature");
                try {
                    String retryJson = postChat(restClient, url, apiKey, retryBody);
                    long latencyMs = System.currentTimeMillis() - start;
                    String content = extractContent(retryJson, apiKey);
                    log.info("LLM chat retry success: model={}, latencyMs={}, replyLength={}", model, latencyMs, content.length());
                    return content;
                } catch (RestClientResponseException retryEx) {
                    long latencyMs = System.currentTimeMillis() - start;
                    log.warn("LLM chat retry http error: model={}, status={}, latencyMs={}", model, retryEx.getStatusCode().value(), latencyMs);
                    throw new IllegalStateException(
                            "LLM 服务返回错误(HTTP " + retryEx.getStatusCode().value() + "): " + sanitize(retryEx.getResponseBodyAsString(), apiKey), retryEx);
                }
            }
            long latencyMs = System.currentTimeMillis() - start;
            log.warn("LLM chat http error: model={}, status={}, latencyMs={}", model, ex.getStatusCode().value(), latencyMs);
            throw new IllegalStateException(
                    "LLM 服务返回错误(HTTP " + ex.getStatusCode().value() + "): " + sanitize(errBody, apiKey), ex);
        } catch (ResourceAccessException ex) {
            long latencyMs = System.currentTimeMillis() - start;
            log.warn("LLM chat network error: model={}, latencyMs={}, errorType={}", model, latencyMs, ex.getClass().getSimpleName());
            throw new IllegalStateException("无法连接 LLM 服务或请求超时，请检查 baseUrl 与网络连通性", ex);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            long latencyMs = System.currentTimeMillis() - start;
            log.warn("LLM chat failed: model={}, latencyMs={}, errorType={}", model, latencyMs, ex.getClass().getSimpleName());
            throw new IllegalStateException("LLM 调用失败: " + ex.getClass().getSimpleName(), ex);
        }
    }

    private Map<String, Object> buildChatRequestBody(String model, double temperature, int maxTokens,
                                                     String systemPrompt, String userPrompt, boolean includeTemperature) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        if (includeTemperature) {
            requestBody.put("temperature", temperature);
        }
        // 推理模型（如 kimi-k2.6 / deepseek-reasoner / r1）的 reasoning_content 计入 max_tokens，
        // 若上限过小会截断最终正文，自动抬升至至少 3072。
        int effectiveMaxTokens = isReasoningModel(model) ? Math.max(maxTokens, 3072) : maxTokens;
        requestBody.put("max_tokens", effectiveMaxTokens);
        return requestBody;
    }

    private boolean isReasoningModel(String model) {
        if (model == null) {
            return false;
        }
        String lower = model.toLowerCase();
        return lower.contains("kimi-k2") || lower.contains("reasoner") || lower.contains("-r1") || lower.contains("thinking");
    }

    private String postChat(RestClient restClient, String url, String apiKey, Map<String, Object> requestBody) {
        byte[] bytes = restClient.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(byte[].class);
        return bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private boolean isTemperatureConstraintError(String responseBody) {
        if (responseBody == null) {
            return false;
        }
        String lower = responseBody.toLowerCase();
        return lower.contains("temperature")
                && (lower.contains("only 1") || lower.contains("invalid temperature") || lower.contains("unsupported") || lower.contains("not allowed"));
    }

    /**
     * 从上游拉取 OpenAI 兼容模型列表：GET {baseUrl}/models，Bearer 头，解析 {data:[{id}]}，
     * 取 id 列表去重排序；失败抛异常（消息经脱敏，不含 key）。
     */
    public List<String> listModels(String baseUrl, String apiKey, int timeoutSeconds) {
        if (isBlank(baseUrl)) {
            throw new IllegalArgumentException("LLM baseUrl 未配置");
        }
        if (isBlank(apiKey)) {
            throw new IllegalArgumentException("LLM apiKey 未配置");
        }
        int readTimeout = timeoutSeconds <= 0 ? 30 : timeoutSeconds;
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(Math.min(readTimeout, MAX_CONNECT_TIMEOUT_SECONDS)))
                .withReadTimeout(Duration.ofSeconds(readTimeout));
        RestClient restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();

        String url = trimTrailingSlash(baseUrl.trim()) + "/models";
        long start = System.currentTimeMillis();
        try {
            byte[] bytes = restClient.get()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(byte[].class);
            String responseJson = bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            long latencyMs = System.currentTimeMillis() - start;
            List<String> models = extractModels(responseJson, apiKey);
            log.info("LLM listModels success: count={}, latencyMs={}", models.size(), latencyMs);
            return models;
        } catch (RestClientResponseException ex) {
            long latencyMs = System.currentTimeMillis() - start;
            log.warn("LLM listModels http error: status={}, latencyMs={}", ex.getStatusCode().value(), latencyMs);
            throw new IllegalStateException(
                    "LLM 服务返回错误(HTTP " + ex.getStatusCode().value() + "): " + sanitize(ex.getResponseBodyAsString(), apiKey), ex);
        } catch (ResourceAccessException ex) {
            long latencyMs = System.currentTimeMillis() - start;
            log.warn("LLM listModels network error: latencyMs={}, errorType={}", latencyMs, ex.getClass().getSimpleName());
            throw new IllegalStateException("无法连接 LLM 服务或请求超时，请检查 baseUrl 与网络连通性", ex);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            long latencyMs = System.currentTimeMillis() - start;
            log.warn("LLM listModels failed: latencyMs={}, errorType={}", latencyMs, ex.getClass().getSimpleName());
            throw new IllegalStateException("LLM 模型列表拉取失败: " + ex.getClass().getSimpleName(), ex);
        }
    }

    private List<String> extractModels(String responseJson, String apiKey) {
        if (isBlank(responseJson)) {
            throw new IllegalStateException("LLM 服务返回空响应");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseJson);
        } catch (Exception ex) {
            throw new IllegalStateException("LLM 响应解析失败: " + sanitize(responseJson, apiKey), ex);
        }
        JsonNode data = root.path("data");
        if (data.isMissingNode() || !data.isArray()) {
            JsonNode errorMessage = root.path("error").path("message");
            if (!errorMessage.isMissingNode() && !errorMessage.isNull()) {
                throw new IllegalStateException("LLM 服务返回错误: " + sanitize(errorMessage.asText(), apiKey));
            }
            throw new IllegalStateException("LLM 响应缺少 data 数组字段: " + sanitize(responseJson, apiKey));
        }
        TreeSet<String> ids = new TreeSet<>();
        for (JsonNode item : data) {
            String id = item.path("id").asText(null);
            if (id != null && !id.isBlank()) {
                ids.add(id.trim());
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalStateException("LLM 响应未包含任何模型 id");
        }
        return new ArrayList<>(ids);
    }

    private String extractContent(String responseJson, String apiKey) {
        if (isBlank(responseJson)) {
            throw new IllegalStateException("LLM 服务返回空响应");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseJson);
        } catch (Exception ex) {
            throw new IllegalStateException("LLM 响应解析失败: " + sanitize(responseJson, apiKey), ex);
        }
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
        if (!contentNode.isMissingNode() && !contentNode.isNull() && !contentNode.asText().isBlank()) {
            return contentNode.asText().trim();
        }
        JsonNode errorMessage = root.path("error").path("message");
        if (!errorMessage.isMissingNode() && !errorMessage.isNull()) {
            throw new IllegalStateException("LLM 服务返回错误: " + sanitize(errorMessage.asText(), apiKey));
        }
        throw new IllegalStateException("LLM 响应缺少 choices[0].message.content 字段: " + sanitize(responseJson, apiKey));
    }

    /**
     * 防御性脱敏：截断长文本并剔除任何可能出现的 Key 明文。
     */
    private String sanitize(String text, String apiKey) {
        if (text == null) {
            return "无响应内容";
        }
        String safe = text;
        if (!isBlank(apiKey)) {
            safe = safe.replace(apiKey, "***");
        }
        safe = safe.replaceAll("\\s+", " ").trim();
        return safe.length() > ERROR_SNIPPET_MAX_LENGTH ? safe.substring(0, ERROR_SNIPPET_MAX_LENGTH) : safe;
    }

    private String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
