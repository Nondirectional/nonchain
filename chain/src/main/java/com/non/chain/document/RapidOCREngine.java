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
 * 基于 RapidOCR 的 OCR 引擎实现。
 * <p>
 * 通过内联 Python 脚本调用 RapidOCR Python API 执行文字识别，
 * 仅输出识别到的纯文本。需通过 pip 安装 rapidocr。
 */
public class RapidOCREngine implements OcrEngine {

    private double textScore = 0.5;

    public RapidOCREngine() {
    }

    public RapidOCREngine setTextScore(double textScore) {
        this.textScore = textScore;
        return this;
    }

    @Override
    public String recognize(BufferedImage image) {
        File tempFile = null;
        try {
            tempFile = Files.createTempFile("rapidocr-", ".png").toFile();
            ImageIO.write(image, "png", tempFile);

            List<String> cmd = buildCommand(tempFile.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // 不合并 stderr，INFO 日志输出到 stderr，OCR 文本输出到 stdout
            pb.redirectErrorStream(false);

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

    private List<String> buildCommand(String imagePath) {
        String escapedPath = imagePath.replace("\\", "\\\\").replace("'", "\\'");
        String script = String.format(
                "import logging;logging.disable(logging.CRITICAL);"
                        + "from rapidocr import RapidOCR;"
                        + "e=RapidOCR(params={'Global.text_score':%s});"
                        + "r=e(r'%s');"
                        + "print('\\n'.join(r.txts)) if r.txts else None",
                textScore, escapedPath);

        List<String> cmd = new ArrayList<>();
        cmd.add("python3");
        cmd.add("-c");
        cmd.add(script);
        return cmd;
    }
}
