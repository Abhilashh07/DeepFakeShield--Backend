package com.deepfakeshield.model;

import java.time.Instant;

public record DetectionResult(
        String id,
        String fileName,
        String fileType,
        long fileSize,
        String verdict,
        double confidence,
        String explanation,
        String mode,
        Instant createdAt
) {}
