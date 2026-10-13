package com.deepfakeshield.controller;

import com.deepfakeshield.model.DetectionResult;
import com.deepfakeshield.service.DetectionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class DetectionController {
    private final DetectionService detectionService;

    public DetectionController(DetectionService detectionService) {
        this.detectionService = detectionService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "application", "DeepFakeShield API");
    }

    @PostMapping(value = "/detect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DetectionResult detect(@RequestParam("file") MultipartFile file) {
        return detectionService.analyze(file);
    }

    @GetMapping("/history")
    public List<DetectionResult> history() {
        return detectionService.history();
    }
}
