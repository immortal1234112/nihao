package com.platform.image.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HTTP API 客户端 —— 封装 OkHttp，供模型适配器调用外部生成 API。
 */
@Slf4j
public class ApiClient {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ApiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public ApiClient(int connectTimeoutSec, int readTimeoutSec) {
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 同步 POST JSON 调用。
     */
    public String callSync(String endpoint, String apiKey, Map<String, Object> body) throws IOException {
        String json = objectMapper.writeValueAsString(body);

        Request request = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .build();

        log.debug("API call: {} {}", endpoint, body.get("prompt") != null
                ? ((String) body.get("prompt")).substring(0, Math.min(30, ((String) body.get("prompt")).length()))
                : "");

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                throw new IOException("API error " + response.code() + ": " + errorBody);
            }
            return response.body() != null ? response.body().string() : "";
        }
    }

    /**
     * 异步 POST（返回 taskId 用于轮询）。
     */
    public String callAsync(String endpoint, String apiKey, Map<String, Object> body) throws IOException {
        String json = objectMapper.writeValueAsString(body);

        Request request = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() == 202 && response.body() != null) {
                // 从响应中提取 taskId
                String respBody = response.body().string();
                @SuppressWarnings("unchecked")
                Map<String, Object> map = objectMapper.readValue(respBody, Map.class);
                return (String) map.getOrDefault("taskId", "");
            }
            throw new IOException("Async API returned " + response.code());
        }
    }

    /**
     * GET 轮询任务状态。
     */
    public String pollStatus(String endpoint, String taskId, String apiKey) throws IOException {
        String url = endpoint.endsWith("/") ? endpoint + taskId : endpoint + "/" + taskId;

        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            }
            throw new IOException("Poll status returned " + response.code());
        }
    }

    /**
     * 获取底层的 OkHttpClient（供 RetryPolicy 等使用）。
     */
    public OkHttpClient getHttpClient() {
        return httpClient;
    }
}
