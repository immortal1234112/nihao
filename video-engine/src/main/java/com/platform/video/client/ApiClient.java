package com.platform.video.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 视频 API 客户端 —— readTimeout=300s（视频生成耗时长）。
 */
@Slf4j
public class ApiClient {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ApiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public String callSync(String endpoint, String apiKey, Map<String, Object> body) throws IOException {
        String json = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                throw new IOException("API error " + response.code() + ": " + errorBody);
            }
            return response.body() != null ? response.body().string() : "";
        }
    }

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
                String respBody = response.body().string();
                @SuppressWarnings("unchecked")
                Map<String, Object> map = objectMapper.readValue(respBody, Map.class);
                return (String) map.getOrDefault("taskId", "");
            }
            throw new IOException("Async API returned " + response.code());
        }
    }

    /**
     * GET 轮询（视频轮询间隔 5s，由调用方控制）。
     */
    public String pollStatus(String endpoint, String taskId, String apiKey) throws IOException {
        String url = endpoint.endsWith("/") ? endpoint + taskId : endpoint + "/" + taskId;
        Request request = new Request.Builder()
                .url(url).get()
                .header("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            }
            throw new IOException("Poll status returned " + response.code());
        }
    }
}
