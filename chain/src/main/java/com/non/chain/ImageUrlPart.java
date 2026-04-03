package com.non.chain;

/**
 * 图片 URL 内容部件。
 */
public class ImageUrlPart implements ContentPart {

    private final String url;

    public ImageUrlPart(String url) {
        this.url = url;
    }

    public String url() {
        return url;
    }

    public static ImageUrlPart of(String url) {
        return new ImageUrlPart(url);
    }
}
