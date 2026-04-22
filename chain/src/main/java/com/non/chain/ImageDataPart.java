package com.non.chain;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Base64 图片数据内容部件。
 */
public class ImageDataPart implements ContentPart {

    private static final Map<String, String> EXTENSION_TO_MIME = new HashMap<>();
    static {
        EXTENSION_TO_MIME.put("png", "image/png");
        EXTENSION_TO_MIME.put("jpg", "image/jpeg");
        EXTENSION_TO_MIME.put("jpeg", "image/jpeg");
        EXTENSION_TO_MIME.put("gif", "image/gif");
        EXTENSION_TO_MIME.put("webp", "image/webp");
    }

    private final String base64Data;
    private final String mimeType;

    public ImageDataPart(String base64Data, String mimeType) {
        this.base64Data = base64Data;
        this.mimeType = mimeType;
    }

    public String base64Data() {
        return base64Data;
    }

    public String mimeType() {
        return mimeType;
    }

    /**
     * 通过 base64 数据和 MIME 类型创建图片部件。
     */
    public static ImageDataPart of(String base64Data, String mimeType) {
        return new ImageDataPart(base64Data, mimeType);
    }

    /**
     * 从本地文件创建图片部件，自动检测 MIME 类型。
     */
    public static ImageDataPart fromFile(String filePath) {
        File file = new File(filePath);
        String fileName = file.getName();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) {
            throw new IllegalArgumentException("无法识别的文件类型，文件缺少扩展名: " + filePath);
        }
        String extension = fileName.substring(dotIndex + 1).toLowerCase();
        String mimeType = EXTENSION_TO_MIME.get(extension);
        if (mimeType == null) {
            throw new IllegalArgumentException("不支持的图片格式: " + extension);
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String base64Data = Base64.getEncoder().encodeToString(bytes);
            return new ImageDataPart(base64Data, mimeType);
        } catch (IOException e) {
            throw new RuntimeException("读取图片文件失败: " + filePath, e);
        }
    }
}
