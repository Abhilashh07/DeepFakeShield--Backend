
package com.deepfakeshield.service;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.deepfakeshield.model.DetectionResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class DetectionService {

    private static final int SIZE = 224;

    private static final float[] MEAN = {
        0.485f, 0.456f, 0.406f
    };

    private static final float[] STD = {
        0.229f, 0.224f, 0.225f
    };

    private final List<DetectionResult> history =
            new CopyOnWriteArrayList<>();

    private OrtEnvironment environment;
    private OrtSession session;
    private String inputName;

    @PostConstruct
    public void loadModel() {
        Path modelPath = Path.of(
                "models", "model_qint8.onnx"
        ).toAbsolutePath().normalize();

        if (!Files.isRegularFile(modelPath)) {
            throw new IllegalStateException(
                    "Model file not found: " + modelPath
            );
        }

        try {
            environment = OrtEnvironment.getEnvironment();

            OrtSession.SessionOptions options =
                    new OrtSession.SessionOptions();

            try {
                session = environment.createSession(
                        modelPath.toString(), options
                );
            } finally {
                options.close();
            }

            inputName = session.getInputNames()
                    .stream()
                    .filter(name -> name.equals("input"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "The model does not have an input named input."
                    ));

            System.out.println(
                    "DeepFakeShield ONNX model loaded successfully."
            );
            System.out.println("Model input: " + inputName);

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to load the ONNX model: "
                            + e.getMessage(), e
            );
        }
    }

    public DetectionResult analyze(MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(
                    "Please upload a non-empty image."
            );
        }

        String contentType = file.getContentType() == null
                ? ""
                : file.getContentType();

        if (!contentType.startsWith("image/")) {
            throw new IllegalArgumentException(
                    "Please upload an image. Video detection "
                            + "is not configured for this model."
            );
        }

        try {
            BufferedImage original = ImageIO.read(
                    new ByteArrayInputStream(file.getBytes())
            );

            if (original == null) {
                throw new IllegalArgumentException(
                        "The uploaded file is not a supported image."
                );
            }

            BufferedImage image = prepareImage(original);
            float[] pixels = preprocess(image);
            float fakeProbability = predict(pixels);

            boolean likelyFake = fakeProbability >= 0.5f;

            double confidence = 100.0 * (
                    likelyFake
                            ? fakeProbability
                            : 1.0f - fakeProbability
            );

            String verdict = likelyFake
                    ? "LIKELY FAKE"
                    : "LIKELY REAL";

            String explanation = String.format(
                    "Model-estimated AI-generated probability: %.2f%%. "
                    + "This is an estimate, not proof of authenticity. "
                    + "The model is intended for portrait images.",
                    fakeProbability * 100.0
            );

            DetectionResult result = new DetectionResult(
                    UUID.randomUUID().toString(),
                    file.getOriginalFilename() == null
                            ? "uploaded-image"
                            : file.getOriginalFilename(),
                    contentType,
                    file.getSize(),
                    verdict,
                    confidence,
                    explanation,
                    "ONNX AI MODEL",
                    Instant.now()
            );

            history.add(0, result);

            while (history.size() > 30) {
                history.remove(history.size() - 1);
            }

            return result;

        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Could not read the uploaded image.", e
            );
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "AI image analysis failed: " + e.getMessage(), e
            );
        }
    }

    private float predict(float[] pixels) throws Exception {

        long[] shape = {1, 3, SIZE, SIZE};

        try (OnnxTensor tensor = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(pixels),
                shape
        )) {
            try (OrtSession.Result result = session.run(
                    Collections.singletonMap(inputName, tensor)
            )) {
                Object output = result.get(0).getValue();

                float logit;

                if (output instanceof float[][] values
                        && values.length > 0
                        && values[0].length > 0) {
                    logit = values[0][0];
                } else if (output instanceof float[] values
                        && values.length > 0) {
                    logit = values[0];
                } else {
                    throw new IllegalStateException(
                            "Unexpected ONNX output format: "
                                    + output.getClass().getName()
                    );
                }

                return (float) (1.0 / (
                        1.0 + Math.exp(-logit)
                ));
            }
        }
    }

    private BufferedImage prepareImage(BufferedImage original) {

        int width = original.getWidth();
        int height = original.getHeight();

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Invalid image dimensions."
            );
        }

        double scale = 256.0 / Math.min(width, height);

        int newWidth = Math.max(SIZE,
                (int) Math.round(width * scale));
        int newHeight = Math.max(SIZE,
                (int) Math.round(height * scale));

        BufferedImage resized = new BufferedImage(
                newWidth, newHeight, BufferedImage.TYPE_INT_RGB
        );

        Graphics2D graphics = resized.createGraphics();

        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC
            );
            graphics.drawImage(
                    original, 0, 0, newWidth, newHeight, null
            );
        } finally {
            graphics.dispose();
        }

        int x = (newWidth - SIZE) / 2;
        int y = (newHeight - SIZE) / 2;

        BufferedImage cropped = new BufferedImage(
                SIZE, SIZE, BufferedImage.TYPE_INT_RGB
        );

        Graphics2D cropGraphics = cropped.createGraphics();

        try {
            cropGraphics.drawImage(
                    resized,
                    0, 0, SIZE, SIZE,
                    x, y, x + SIZE, y + SIZE,
                    null
            );
        } finally {
            cropGraphics.dispose();
        }

        return cropped;
    }

    private float[] preprocess(BufferedImage image) {

        int planeSize = SIZE * SIZE;
        float[] data = new float[3 * planeSize];

        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {

                int rgb = image.getRGB(x, y);
                int index = y * SIZE + x;

                float red = ((rgb >> 16) & 255) / 255.0f;
                float green = ((rgb >> 8) & 255) / 255.0f;
                float blue = (rgb & 255) / 255.0f;

                data[index] = (red - MEAN[0]) / STD[0];

                data[planeSize + index] =
                        (green - MEAN[1]) / STD[1];

                data[2 * planeSize + index] =
                        (blue - MEAN[2]) / STD[2];
            }
        }

        return data;
    }

    public List<DetectionResult> history() {
        return List.copyOf(history);
    }

    @PreDestroy
    public void closeModel() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                System.err.println(
                        "Error closing ONNX model: " + e.getMessage()
                );
            }
        }
    }
}
