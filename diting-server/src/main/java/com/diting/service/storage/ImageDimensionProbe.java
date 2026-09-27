package com.diting.service.storage;

import java.nio.charset.StandardCharsets;

/**
 * 图片像素尺寸探测（S16 补强，官方契约核验日期 2026-09-27）：只读文件头/段头，**不解码像素**。
 *
 * 为什么需要：阿里云 RecognizeGeneral 对尺寸有硬约束（长宽需大于 15 像素且小于 8192 像素、
 * 长宽比小于 50）。超限的图云端只会回 `illegalImageSize`——一次付费调用换一个必然失败的结果，
 * 之后还会按次数上限重试。上传校验与 OCR 调用前各查一次，把这类图挡在计费之前。
 *
 * 探测规则取自各容器格式的公开头结构：
 * PNG（IHDR）/ GIF（逻辑屏幕描述符）/ BMP（DIB 头）/ JPEG（SOFn 段）/ WebP（VP8X|VP8 |VP8L 块）/
 * TIFF（IFD 标签 256 + 257）。**探测不出来返回 null**（字节被截断、结构异常、未知变体）：
 * 不拦，交给云端判定——宁可多花一次调用，也不能把能识别的图误拒。
 */
public final class ImageDimensionProbe {

    /** 像素尺寸 */
    public record Size(int width, int height) {
        public String display() {
            return width + "×" + height;
        }
    }

    /** 尺寸探测所需的最大前缀字节数参考值（JPEG 的 SOFn 与 TIFF 的 IFD 可能离文件头较远） */
    public static final int PROBE_BYTES = 64 * 1024;

    private ImageDimensionProbe() {
    }

    /**
     * 按文件头识别出的格式取像素尺寸。
     *
     * @param bytes 图片字节（可只是前缀，够覆盖头结构即可）
     * @return 尺寸；无法判定返回 null
     */
    public static Size probe(byte[] bytes) {
        // 最低只要够 ImageFormatSniffer 认格式（≥4 字节）；各格式自己的长度边界在各自方法里查——
        // GIF 头只有 10 字节，外层门檻卡太紧会让它永远探测不出（曾因此漏判）。
        if (bytes == null || bytes.length < 4) {
            return null;
        }
        String format = ImageFormatSniffer.sniff(bytes);
        if (format == null) {
            return null;
        }
        return switch (format) {
            case "png" -> png(bytes);
            case "gif" -> gif(bytes);
            case "bmp" -> bmp(bytes);
            case "jpg" -> jpeg(bytes);
            case "webp" -> webp(bytes);
            case "tiff" -> tiff(bytes);
            default -> null;
        };
    }

    /**
     * 官方受理范围校验（RecognizeGeneral「重要提示」）。
     *
     * @param size            尺寸；null（探测不出）时不做判定
     * @param minPixels       长宽下限，需**大于**该值
     * @param maxPixels       长宽上限，需**小于**该值
     * @param maxAspectRatio  长宽比上限，需**小于**该值（<=0 表示不校验）
     * @return 超出范围时返回可直接展示的中文原因；在范围内或无法判定时返回 null
     */
    public static String outOfOcrRange(Size size, int minPixels, int maxPixels, int maxAspectRatio) {
        if (size == null) {
            return null;
        }
        int min = Math.min(size.width(), size.height());
        int max = Math.max(size.width(), size.height());
        if (min <= minPixels || max >= maxPixels) {
            return "图片尺寸超出识别范围：长宽需大于 " + minPixels + " 像素且小于 " + maxPixels
                + " 像素（当前 " + size.display() + " 像素）";
        }
        if (maxAspectRatio > 0 && (double) max / min >= maxAspectRatio) {
            return "图片尺寸超出识别范围：长宽比需小于 " + maxAspectRatio
                + "（当前 " + size.display() + " 像素）";
        }
        return null;
    }

    /** PNG：8 字节签名 + 4 字节长度 + 4 字节类型（IHDR）+ 宽 4 + 高 4 */
    private static Size png(byte[] b) {
        if (b.length < 24) {
            return null;
        }
        return sane(u32(b, 16, false), u32(b, 20, false));
    }

    /** GIF：逻辑屏幕描述符宽高各 2 字节小端 */
    private static Size gif(byte[] b) {
        if (b.length < 10) {
            return null;
        }
        return sane(u16(b, 6, true), u16(b, 8, true));
    }

    /** BMP：DIB 头宽高各 4 字节小端（高度为负表示自顶向下） */
    private static Size bmp(byte[] b) {
        if (b.length < 26) {
            return null;
        }
        int width = u32(b, 18, true);
        int height = Math.abs(u32(b, 22, true));
        return sane(width, height);
    }

    /** JPEG：顺序扫段，遇到 SOFn（C0–CF，排除 C4/C8/CC）取高、宽 */
    private static Size jpeg(byte[] b) {
        int pos = 2;
        while (pos + 4 <= b.length) {
            if ((b[pos] & 0xFF) != 0xFF) {
                pos++; // 填充或错位：向前找下一个标记
                continue;
            }
            int marker = b[pos + 1] & 0xFF;
            if (marker == 0xFF) {
                pos++; // 填充字节
                continue;
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                pos += 2; // 无长度字段的标记
                continue;
            }
            if (marker == 0xDA) {
                return null; // 已进入压缩数据，仍未见 SOFn
            }
            int segLen = u16(b, pos + 2, false);
            if (segLen < 2) {
                return null;
            }
            boolean sof = marker >= 0xC0 && marker <= 0xCF
                && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof && pos + 9 <= b.length) {
                return sane(u16(b, pos + 7, false), u16(b, pos + 5, false));
            }
            pos += 2 + segLen;
        }
        return null;
    }

    /** WebP：RIFF 容器内的 VP8X（扩展）/ VP8 （有损）/ VP8L（无损）三种块 */
    private static Size webp(byte[] b) {
        if (b.length < 16) {
            return null;
        }
        String chunk = new String(b, 12, 4, StandardCharsets.US_ASCII);
        return switch (chunk) {
            case "VP8X" -> b.length < 30 ? null : sane(u24(b, 24) + 1, u24(b, 27) + 1);
            case "VP8 " -> {
                if (b.length < 30 || (b[23] & 0xFF) != 0x9D || (b[24] & 0xFF) != 0x01
                    || (b[25] & 0xFF) != 0x2A) {
                    yield null;
                }
                yield sane(u16(b, 26, true) & 0x3FFF, u16(b, 28, true) & 0x3FFF);
            }
            case "VP8L" -> {
                if (b.length < 25 || (b[20] & 0xFF) != 0x2F) {
                    yield null;
                }
                long bits = u32(b, 21, true);
                yield sane((int) (bits & 0x3FFF) + 1, (int) ((bits >>> 14) & 0x3FFF) + 1);
            }
            default -> null;
        };
    }

    /** TIFF：按字节序读 IFD，从标签 256(ImageWidth)/257(ImageLength) 取值 */
    private static Size tiff(byte[] b) {
        if (b.length < 8) {
            return null;
        }
        boolean little = b[0] == 'I' && b[1] == 'I';
        if (!little && !(b[0] == 'M' && b[1] == 'M')) {
            return null;
        }
        int ifd = u32(b, 4, little);
        if (ifd <= 0 || ifd + 2 > b.length) {
            return null;
        }
        int count = u16(b, ifd, little);
        int width = 0;
        int height = 0;
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > b.length) {
                break; // 前缀被截断：不猜
            }
            int tag = u16(b, entry, little);
            if (tag != 256 && tag != 257) {
                continue;
            }
            int type = u16(b, entry + 2, little);
            int value;
            if (type == 3) {
                value = u16(b, entry + 8, little); // SHORT：值就放在值字段里
            }
            else if (type == 4) {
                value = u32(b, entry + 8, little); // LONG
            }
            else {
                continue;
            }
            if (tag == 256) {
                width = value;
            }
            else {
                height = value;
            }
        }
        return sane(width, height);
    }

    private static Size sane(int width, int height) {
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535) {
            return null; // 结构可疑：当作探测不出，交给云端
        }
        return new Size(width, height);
    }

    private static int u16(byte[] b, int at, boolean little) {
        if (at + 2 > b.length) {
            return -1;
        }
        int a = b[at] & 0xFF;
        int c = b[at + 1] & 0xFF;
        return little ? (c << 8 | a) : (a << 8 | c);
    }

    private static int u24(byte[] b, int at) {
        if (at + 3 > b.length) {
            return -1;
        }
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16);
    }

    private static int u32(byte[] b, int at, boolean little) {
        if (at + 4 > b.length) {
            return -1;
        }
        int a = b[at] & 0xFF;
        int c = b[at + 1] & 0xFF;
        int d = b[at + 2] & 0xFF;
        int e = b[at + 3] & 0xFF;
        long value = little
            ? ((long) e << 24 | d << 16 | c << 8 | a)
            : ((long) a << 24 | c << 16 | d << 8 | e);
        if (value > Integer.MAX_VALUE) {
            return -1; // 尺寸字段不可能这么大：按探测不出处理
        }
        return (int) value;
    }
}
