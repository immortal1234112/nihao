package com.platform.agent.rag.service;

import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 文档解析服务 —— v1 仅支持 txt/md 纯文本（UTF-8，去 BOM，大小限制）。
 * 后续可扩展 PDF/Word（建议 Apache Tika）加一个分支即可。
 */
@Service
public class DocumentParseService {

    private static final Set<String> SUPPORTED_TYPES = Set.of("txt", "md");

    @Value("${rag.upload-max-size-bytes:2097152}")
    private long maxUploadSizeBytes;

    /**
     * 解析上传文件为纯文本。
     */
    public ParsedText parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文件不能为空");
        }
        String originalName = file.getOriginalFilename();
        String ext = ext(originalName);
        if (!SUPPORTED_TYPES.contains(ext)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "v1 仅支持 txt/md 纯文本文件");
        }
        if (file.getSize() > maxUploadSizeBytes) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "文件超过大小限制（" + (maxUploadSizeBytes / 1024 / 1024) + "MB）");
        }
        try {
            byte[] bytes = file.getBytes();
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) {
                text = text.substring(1);  // 去 BOM
            }
            if (text.isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "文件内容为空");
            }
            String fileName = originalName != null && !originalName.isBlank()
                    ? originalName
                    : "document." + ext;
            return new ParsedText(fileName, ext, text);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文件读取失败: " + e.getMessage());
        }
    }

    private String ext(String fileName) {
        if (fileName == null || fileName.isBlank()) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 解析结果。
     */
    public record ParsedText(String fileName, String fileType, String text) {
    }
}
