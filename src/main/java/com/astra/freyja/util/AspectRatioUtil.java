package com.astra.freyja.util;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 画幅比例与生图尺寸解析工具类。
 * 负责解析短剧配置中的画幅比例 (9:16, 16:9 等) 或具体分辨率尺寸 (1920*1080, 1080*1920 等)，
 * 并将其统一规整为生图引擎标准的 "宽x高" (如 1920x1080, 720x1280) 格式。
 */
public final class AspectRatioUtil {

    /** 匹配宽*高或宽x高格式，例如 1920*1080, 1920x1080, 1080*1920, 1280*720, 1024×1024 */
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("^(\\d{3,4})\\s*[*xX×]\\s*(\\d{3,4})$");

    /** 短剧系统默认生图尺寸（9:16 竖屏标准） */
    public static final String DEFAULT_SHORT_DRAMA_SIZE = "720x1280";

    /** 常见标准标清与全高清画幅尺寸 */
    public static final String SIZE_9_16_HD = "1080x1920";
    public static final String SIZE_9_16_SD = "720x1280";
    public static final String SIZE_16_9_HD = "1920x1080";
    public static final String SIZE_16_9_SD = "1280x720";
    public static final String SIZE_1_1 = "1024x1024";
    public static final String SIZE_4_3 = "1024x768";
    public static final String SIZE_3_4 = "768x1024";
    public static final String SIZE_2_3 = "1024x1536";
    public static final String SIZE_3_2 = "1536x1024";

    private AspectRatioUtil() {
    }

    /**
     * 根据画幅配置或分辨率输入推导生图引擎标准的尺寸格式。
     * 支持输入具体尺寸 (如 1920*1080, 1080*1920) 自动归一化为标准的 1920x1080；
     * 也支持输入业务比例 (如 16:9, 9:16) 映射为推荐分辨率。
     *
     * @param input 画幅比例或尺寸字符串，如 "1920*1080", "16:9", "9:16" 等
     * @return 标准生图尺寸格式，如 "1920x1080", "720x1280"
     */
    public static String resolveSize(String input) {
        if (StringUtils.isBlank(input)) {
            return DEFAULT_SHORT_DRAMA_SIZE;
        }

        String raw = input.trim();

        // 1. 如果输入本身就是形如 1920*1080, 1920x1080, 1080*1920 的具体分辨率尺寸，
        // 将分隔符统一规整为生图 API 所需的小写 'x'
        Matcher matcher = RESOLUTION_PATTERN.matcher(raw);
        if (matcher.matches()) {
            int width = Integer.parseInt(matcher.group(1));
            int height = Integer.parseInt(matcher.group(2));
            return width + "x" + height;
        }

        // 2. 如果输入的是比例字符串 (如 "16:9", "9:16", "1:1" 等)
        String ratio = raw.replace('/', ':').toLowerCase();
        return switch (ratio) {
            case "16:9" -> SIZE_16_9_SD; // 16:9 横屏剧集标清 1280x720
            case "9:16" -> SIZE_9_16_SD; // 9:16 竖屏短剧标清 720x1280
            case "1:1" -> SIZE_1_1;
            case "4:3" -> SIZE_4_3;
            case "3:4" -> SIZE_3_4;
            case "2:3" -> SIZE_2_3;
            case "3:2" -> SIZE_3_2;
            default -> DEFAULT_SHORT_DRAMA_SIZE;
        };
    }

    /**
     * 兼容方法名
     */
    public static String resolveSizeByAspectRatio(String aspectRatio) {
        return resolveSize(aspectRatio);
    }

    /**
     * 根据尺寸反向推导画幅比例，例如 1920x1080 / 1920*1080 推导为 16:9。
     *
     * @param size 分辨率尺寸字符串，如 "1920*1080", "1920x1080"
     * @return 画幅比例，如 "16:9", "9:16", "1:1"
     */
    public static String resolveRatioBySize(String size) {
        if (StringUtils.isBlank(size)) {
            return "9:16";
        }
        Matcher matcher = RESOLUTION_PATTERN.matcher(size.trim());
        if (matcher.matches()) {
            int w = Integer.parseInt(matcher.group(1));
            int h = Integer.parseInt(matcher.group(2));
            if (w == h) {
                return "1:1";
            }
            double ratio = (double) w / h;
            // 16:9 约为 1.778
            if (Math.abs(ratio - (16.0 / 9.0)) < 0.05) {
                return "16:9";
            }
            // 9:16 约为 0.5625
            if (Math.abs(ratio - (9.0 / 16.0)) < 0.05) {
                return "9:16";
            }
            // 4:3 约为 1.333
            if (Math.abs(ratio - (4.0 / 3.0)) < 0.05) {
                return "4:3";
            }
            // 3:4 约为 0.75
            if (Math.abs(ratio - (3.0 / 4.0)) < 0.05) {
                return "3:4";
            }
            // 3:2 约为 1.5
            if (Math.abs(ratio - (3.0 / 2.0)) < 0.05) {
                return "3:2";
            }
            // 2:3 约为 0.667
            if (Math.abs(ratio - (2.0 / 3.0)) < 0.05) {
                return "2:3";
            }
        }
        return "9:16";
    }
}
