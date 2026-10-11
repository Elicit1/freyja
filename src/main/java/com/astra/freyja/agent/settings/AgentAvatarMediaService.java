package com.astra.freyja.agent.settings;

import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.agent.AgentConfigDTO.Pet;
import com.astra.freyja.service.MediaAssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipInputStream;

/** Imports data-only Codex Pet packages; no archive member is extracted or executed. */
@Service
@RequiredArgsConstructor
public class AgentAvatarMediaService {
    public static final long MAX_BYTES = 20 * 1024 * 1024;
    private static final int MAX_EXPANDED = 32 * 1024 * 1024;
    private final MediaAssetService media;
    private final AgentJson json;
    public record PetPackage(Pet pet, byte[] spritesheet, String filename) {}
    public record ImageInfo(String contentType, int width, int height) {}

    public Pet upload(MultipartFile file) {
        PetPackage pack = inspectPackage(file);
        var stored = media.upload(new BytesFile(pack.filename(), pack.pet().getContentType(), pack.spritesheet()), "agent-pet");
        pack.pet().setSpritesheetUrl(stored.getUrl());
        pack.pet().setObjectPath(stored.getObjectPath());
        return pack.pet();
    }
    public PetPackage inspectPackage(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".zip"))
            throw invalid("请上传不超过 20 MB 的 Codex Pet ZIP 包");
        try {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            Set<String> seen = new HashSet<>();
            int total = 0, count = 0;
            // Bound compressed bytes independently of MultipartFile's size metadata.
            byte[] archive;
            try (var input = file.getInputStream()) { archive = input.readNBytes((int) MAX_BYTES + 1); }
            if (archive.length > MAX_BYTES) throw invalid("ZIP 包不得超过 20 MB");
            try (var zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (++count > 64) throw invalid("宠物包文件数量不得超过 64");
                    String path = safePath(entry.getName(), entry.isDirectory());
                    if (!seen.add(path.toLowerCase(Locale.ROOT))) throw invalid("宠物包包含重复路径");
                    var output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192]; int read;
                    while ((read = zip.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_EXPANDED) throw invalid("宠物包解压内容不得超过 32 MB");
                        if (!entry.isDirectory()) output.write(buffer, 0, read);
                        if (path.endsWith("pet.json") && output.size() > 65536) throw invalid("pet.json 不得超过 64 KB");
                    }
                    if (!entry.isDirectory() && !path.startsWith("__MACOSX/") && !path.endsWith(".DS_Store"))
                        entries.put(path, output.toByteArray());
                }
            }
            var manifests = entries.keySet().stream().filter(p -> p.equals("pet.json") || p.endsWith("/pet.json")).toList();
            if (manifests.size() != 1) throw invalid("宠物包必须包含唯一的 pet.json");
            String manifestPath = manifests.getFirst();
            String root = manifestPath.substring(0, manifestPath.length() - "pet.json".length());
            String content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(entries.get(manifestPath))).toString();
            Map<?, ?> manifest = json.read(content, Map.class);
            Pet pet = new Pet();
            pet.setId(text(manifest, "id", null, 100));
            pet.setDisplayName(text(manifest, "displayName", null, 100));
            pet.setDescription(text(manifest, "description", "", 1000));
            String relative = safePath(text(manifest, "spritesheetPath", "spritesheet.webp", 500), false);
            String path = root + relative;
            byte[] sprite = entries.get(path);
            if (sprite == null) throw invalid("找不到 spritesheetPath 指定的精灵图");
            ImageInfo image = inspect(sprite);
            Object declared = manifest.get("spriteVersionNumber");
            int version = image.height() == 2288 ? 2 : 1;
            if (declared != null) {
                if (!(declared instanceof Number number) || number.doubleValue() != number.intValue()) throw invalid("spriteVersionNumber 必须为整数");
                version = number.intValue();
            }
            if ((version != 1 && version != 2) || image.width() != 1536 || image.height() != (version == 2 ? 2288 : 1872))
                throw invalid("v1 精灵图须为 1536×1872，v2 须为 1536×2288");
            String extension = image.contentType().equals("image/webp") ? ".webp" : ".png";
            if (!path.toLowerCase(Locale.ROOT).endsWith(extension)) throw invalid("精灵图扩展名与实际格式不一致");
            pet.setSpriteVersionNumber(version); pet.setRows(version == 2 ? 11 : 9);
            pet.setContentType(image.contentType()); pet.setSpritesheetUrl(null);
            return new PetPackage(pet, sprite, "spritesheet" + extension);
        } catch (IOException | IllegalArgumentException error) { throw invalid("宠物 ZIP 包损坏或格式无效"); }
    }
    private static String text(Map<?, ?> source, String key, String fallback, int limit) {
        Object value = source.get(key);
        if (value == null && fallback != null) return fallback;
        if (!(value instanceof String s) || (fallback == null && s.isBlank()) || s.length() > limit)
            throw invalid("pet.json 的 " + key + " 无效");
        return s;
    }
    private static String safePath(String input, boolean directory) {
        String path = input.replace('\\', '/');
        if (directory && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isBlank() || path.startsWith("/") || path.indexOf(':') >= 0 || path.indexOf('\0') >= 0
                || Arrays.stream(path.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")))
            throw invalid("宠物包包含不安全的路径");
        return path;
    }
    public static ImageInfo inspect(byte[] b) {
        if (b == null || b.length < 30) throw invalid("精灵图格式无效");
        if (b[0] == (byte) 0x89 && ascii(b, 1, 7).equals("PNG\r\n\u001a\n") && ascii(b, 12, 4).equals("IHDR")) {
            int width = big(b, 16), height = big(b, 20);
            if (width != 1536 || (height != 1872 && height != 2288)) throw invalid("PNG 精灵图画布尺寸无效");
            try {
                var image = ImageIO.read(new ByteArrayInputStream(b));
                if (image == null || !image.getColorModel().hasAlpha()) throw invalid("精灵图需要透明通道");
            } catch (IOException e) { throw invalid("PNG 精灵图损坏"); }
            return new ImageInfo("image/png", width, height);
        }
        if (!ascii(b, 0, 4).equals("RIFF") || !ascii(b, 8, 4).equals("WEBP") || unsigned32(b, 4) + 8 != b.length)
            throw invalid("仅支持静态 PNG/WebP 精灵图");
        int width = 0, height = 0, offset = 12, payloadWidth = 0, payloadHeight = 0, images = 0;
        boolean alpha = false;
        while (offset < b.length) {
            if (offset + 8 > b.length) throw invalid("WebP 精灵图损坏");
            String chunk = ascii(b, offset, 4);
            long length = unsigned32(b, offset + 4);
            int data = offset + 8;
            if (length > b.length - data || length + (length & 1) > b.length - data) throw invalid("WebP 精灵图损坏");
            if (chunk.equals("ANIM") || chunk.equals("ANMF")) throw invalid("请使用静态精灵图，动画由宠物状态驱动");
            switch (chunk) {
                case "VP8X" -> {
                    if (length != 10 || width != 0 || (b[data] & 2) != 0) throw invalid("WebP 扩展头无效");
                    width = 1 + little24(b, data + 4); height = 1 + little24(b, data + 7); alpha |= (b[data] & 16) != 0;
                }
                case "VP8L" -> {
                    if (length < 5 || b[data] != 0x2f || (b[data + 4] & 0xe0) != 0) throw invalid("WebP 无损头无效");
                    payloadWidth = 1 + ((b[data+1] & 255) | ((b[data+2] & 63) << 8));
                    payloadHeight = 1 + (((b[data+2] & 192) >> 6) | ((b[data+3] & 255) << 2) | ((b[data+4] & 15) << 10));
                    alpha |= (b[data+4] & 16) != 0; images++;
                }
                case "VP8 " -> {
                    if (length < 10 || b[data+3] != (byte) 0x9d || b[data+4] != 1 || b[data+5] != 0x2a) throw invalid("WebP 有损头无效");
                    payloadWidth = little16(b, data+6) & 16383; payloadHeight = little16(b, data+8) & 16383; images++;
                }
                case "ALPH" -> { if (length < 1) throw invalid("WebP 透明通道无效"); alpha = true; }
                default -> { }
            }
            offset = data + (int) length + ((int) length & 1);
        }
        if (width == 0) { width = payloadWidth; height = payloadHeight; }
        if (images != 1 || !alpha || width != 1536 || (height != 1872 && height != 2288)
                || width != payloadWidth || height != payloadHeight) throw invalid("WebP 精灵图尺寸或透明通道无效");
        return new ImageInfo("image/webp", width, height);
    }
    private static BizException invalid(String message) { return new BizException(400, message); }
    private static String ascii(byte[] b, int i, int size) { return new String(b, i, size, StandardCharsets.US_ASCII); }
    private static int big(byte[] b, int i) { return ByteBuffer.wrap(b, i, 4).getInt(); }
    private static int little16(byte[] b, int i) { return (b[i] & 255) | ((b[i+1] & 255) << 8); }
    private static int little24(byte[] b, int i) { return little16(b, i) | ((b[i+2] & 255) << 16); }
    private static long unsigned32(byte[] b, int i) { return Integer.toUnsignedLong(little24(b, i) | ((b[i+3] & 255) << 24)); }
    private record BytesFile(String filename, String type, byte[] bytes) implements MultipartFile {
        public String getName() { return "file"; }
        public String getOriginalFilename() { return filename; }
        public String getContentType() { return type; }
        public boolean isEmpty() { return bytes.length == 0; }
        public long getSize() { return bytes.length; }
        public byte[] getBytes() { return bytes; }
        public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        public void transferTo(File destination) throws IOException { Files.write(destination.toPath(), bytes); }
    }
}
