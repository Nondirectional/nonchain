package com.non.chain.document;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Tesseract CLI 的 OCR 引擎实现。
 * <p>
 * 通过 {@link ProcessBuilder} 调用本地安装的 tesseract 命令行工具执行文字识别，
 * 无需 JNA/Tess4J 原生库绑定，只要 tesseract 在系统 PATH 中即可使用。
 */
public class TesseractOCREngine implements OcrEngine {

    private String language = "chi_sim";
    private String dataPath;
    private int psm = -1;

    public TesseractOCREngine() {
    }

    public TesseractOCREngine(String dataPath, String language) {
        this.dataPath = dataPath;
        this.language = language;
    }

    public TesseractOCREngine setDataPath(String dataPath) {
        this.dataPath = dataPath;
        return this;
    }

    public TesseractOCREngine setLanguage(String language) {
        this.language = language;
        return this;
    }

    public TesseractOCREngine setPageSegMode(int mode) {
        this.psm = mode;
        return this;
    }

    @Override
    public String recognize(BufferedImage image) {
        File tempFile = null;
        try {
            tempFile = Files.createTempFile("tesseract-", ".png").toFile();
            ImageIO.write(image, "png", tempFile);

            List<String> cmd = buildCommand(tempFile);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            return exitCode == 0 ? output.toString().trim() : "";
        } catch (Exception e) {
            return "";
        } finally {
            if (tempFile != null) {
                tempFile.delete();
            }
        }
    }

    private List<String> buildCommand(File imageFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add("tesseract");
        if (dataPath != null) {
            cmd.add("--tessdata-dir");
            cmd.add(dataPath);
        }
        if (psm >= 0) {
            cmd.add("--psm");
            cmd.add(String.valueOf(psm));
        }
        cmd.add("-l");
        cmd.add(language);
        cmd.add(imageFile.getAbsolutePath());
        cmd.add("stdout");
        return cmd;
    }
}
