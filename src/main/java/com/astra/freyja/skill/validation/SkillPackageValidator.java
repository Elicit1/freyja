package com.astra.freyja.skill.validation;

import com.astra.freyja.common.BizException;
import com.astra.freyja.skill.model.StandardSkillMetadata;
import com.astra.freyja.skill.model.StandardSkillPackage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Agent Skills 标准包校验器。
 *
 * <p>支持标准 Skill 目录的 ZIP 表示。ZIP 可以包含
 * references、scripts、assets 或其他标准允许的附属文件；这些文件只会被保存，
 * 系统不会在服务端自动执行脚本。</p>
 */
@Slf4j
@Component
public class SkillPackageValidator {

    public static final long MAX_ZIP_BYTES = 20 * 1024 * 1024L;
    public static final int MAX_ENTRY_COUNT = 200;
    public static final long MAX_SINGLE_FILE_BYTES = 10 * 1024 * 1024L;
    public static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 50 * 1024 * 1024L;

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Set<String> IGNORED_ARCHIVE_FILES = Set.of(".DS_Store");

    /** 校验并解析标准 Skill ZIP 上传文件。 */
    public StandardSkillPackage validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("Skill 文件不能为空");
        }
        String filename = StringUtils.defaultString(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".zip")) {
            throw new BizException("标准 Skill 只支持包含 SKILL.md 的 .zip 目录包，不兼容旧的单文件 .md 上传");
        }
        if (file.getSize() > MAX_ZIP_BYTES) {
            throw new BizException("Skill ZIP 压缩包不能超过 20MB");
        }
        try (InputStream inputStream = file.getInputStream()) {
            return validateZip(inputStream, file.getSize());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[SkillPackageValidator] 解析标准 Skill ZIP 失败: {}", e.getMessage());
            throw new BizException("解析 Skill ZIP 失败: " + e.getMessage());
        }
    }

    /** 公开给单元测试和内部迁移工具使用的 ZIP 校验入口。 */
    public StandardSkillPackage validateZip(InputStream inputStream, long compressedSize) {
        if (inputStream == null) {
            throw new BizException("Skill ZIP 输入流不能为空");
        }
        if (compressedSize > MAX_ZIP_BYTES) {
            throw new BizException("Skill ZIP 压缩包不能超过 20MB");
        }

        Map<String, byte[]> rawFiles = new LinkedHashMap<>();
        int entryCount = 0;
        long totalSize = 0;
        try (ZipInputStream zip = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                entryCount++;
                if (entryCount > MAX_ENTRY_COUNT) {
                    throw new BizException("Skill ZIP 文件数量不能超过 " + MAX_ENTRY_COUNT + " 个");
                }

                String normalizedPath = normalizePath(entry.getName());
                if (shouldIgnore(normalizedPath)) {
                    zip.closeEntry();
                    continue;
                }
                if (rawFiles.containsKey(normalizedPath)) {
                    throw new BizException("Skill ZIP 包含重复文件路径: " + normalizedPath);
                }

                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                long fileSize = 0;
                int read;
                while ((read = zip.read(chunk)) != -1) {
                    fileSize += read;
                    totalSize += read;
                    if (fileSize > MAX_SINGLE_FILE_BYTES) {
                        throw new BizException("Skill 文件 [" + normalizedPath + "] 解压后不能超过 10MB");
                    }
                    if (totalSize > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                        throw new BizException("Skill ZIP 解压后总大小不能超过 50MB");
                    }
                    buffer.write(chunk, 0, read);
                }
                rawFiles.put(normalizedPath, buffer.toByteArray());
                zip.closeEntry();
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("读取 Skill ZIP 内容失败: " + e.getMessage());
        }

        if (rawFiles.isEmpty()) {
            throw new BizException("Skill ZIP 包为空");
        }

        ArchiveRoot archiveRoot = stripSingleRootDirectory(rawFiles);
        return buildPackage(archiveRoot.files(), archiveRoot.rootDirectory());
    }

    private StandardSkillPackage buildPackage(Map<String, byte[]> files, String archiveRootDirectory) {
        byte[] skillBytes = files.get("SKILL.md");
        if (skillBytes == null) {
            throw new BizException("Skill 根目录缺少必需的 SKILL.md 文件");
        }
        String skillContent = decodeUtf8("SKILL.md", skillBytes);
        StandardSkillMetadata metadata = parseFrontmatter(skillContent);

        if (archiveRootDirectory != null && !archiveRootDirectory.equals(metadata.getName())) {
            throw new BizException("Skill 根目录名 [" + archiveRootDirectory
                    + "] 必须与 SKILL.md frontmatter 的 name [" + metadata.getName() + "] 一致");
        }

        Map<String, String> fileHashes = new LinkedHashMap<>();
        MessageDigest packageDigest = sha256Digest();
        List<String> sortedPaths = new ArrayList<>(files.keySet());
        Collections.sort(sortedPaths);
        for (String path : sortedPaths) {
            byte[] bytes = files.get(path);
            String hash = sha256(bytes);
            fileHashes.put(path, hash);
            packageDigest.update(path.getBytes(StandardCharsets.UTF_8));
            packageDigest.update((byte) 0);
            packageDigest.update(bytes);
        }

        long totalSize = files.values().stream().mapToLong(bytes -> bytes.length).sum();
        return StandardSkillPackage.builder()
                .metadata(metadata)
                .entrypointContent(skillContent)
                .files(new LinkedHashMap<>(files))
                .fileHashes(fileHashes)
                .packageHash("sha256:" + toHex(packageDigest.digest()))
                .totalSize(totalSize)
                .build();
    }

    private StandardSkillMetadata parseFrontmatter(String content) {
        String normalized = StringUtils.defaultString(content).replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.startsWith("\uFEFF")) {
            normalized = normalized.substring(1);
        }
        String[] lines = normalized.split("\n", -1);
        if (lines.length < 3 || !"---".equals(lines[0].trim())) {
            throw new BizException("SKILL.md 必须以 YAML frontmatter 开始，缺少首行 ---");
        }

        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(lines[i].trim()) || "...".equals(lines[i].trim())) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new BizException("SKILL.md YAML frontmatter 缺少结束标记 ---");
        }

        String yamlText = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, end));
        try {
            LoaderOptions options = new LoaderOptions();
            Yaml yaml = new Yaml(new SafeConstructor(options));
            Object loaded = yaml.load(new ByteArrayInputStream(yamlText.getBytes(StandardCharsets.UTF_8)));
            if (!(loaded instanceof Map<?, ?> values)) {
                throw new BizException("SKILL.md YAML frontmatter 必须是对象");
            }

            StandardSkillMetadata metadata = StandardSkillMetadata.builder()
                    .name(stringField(values, "name"))
                    .description(stringField(values, "description"))
                    .license(stringField(values, "license"))
                    .compatibility(stringField(values, "compatibility"))
                    .allowedTools(stringField(values, "allowed-tools"))
                    .metadata(readMetadata(values.get("metadata")))
                    .build();
            validateMetadata(metadata);
            return metadata;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("解析 SKILL.md YAML frontmatter 失败: " + e.getMessage());
        }
    }

    private Map<String, String> readMetadata(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new BizException("SKILL.md 的 metadata 必须是键值对象");
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) {
                throw new BizException("SKILL.md 的 metadata 键和值必须是字符串");
            }
            String key = ((String) entry.getKey()).trim();
            String item = ((String) entry.getValue()).trim();
            if (key.isEmpty()) {
                throw new BizException("SKILL.md 的 metadata 键不能为空");
            }
            metadata.put(key, item);
        }
        return metadata;
    }

    private void validateMetadata(StandardSkillMetadata metadata) {
        String name = StringUtils.trimToEmpty(metadata.getName());
        if (name.length() > 64 || !NAME_PATTERN.matcher(name).matches()
                || name.contains("--") || name.startsWith("-") || name.endsWith("-")) {
            throw new BizException("SKILL.md frontmatter 的 name 不合法，必须是 1-64 位小写字母、数字和中划线组成的 slug");
        }
        metadata.setName(name);

        String description = StringUtils.trimToEmpty(metadata.getDescription());
        if (description.isEmpty() || description.length() > 1024) {
            throw new BizException("SKILL.md frontmatter 的 description 必须为 1-1024 个字符");
        }
        metadata.setDescription(description);

        if (StringUtils.isNotBlank(metadata.getLicense())) {
            metadata.setLicense(metadata.getLicense().trim());
        }
        if (StringUtils.isNotBlank(metadata.getCompatibility()) && metadata.getCompatibility().length() > 500) {
            throw new BizException("SKILL.md frontmatter 的 compatibility 不能超过 500 个字符");
        }
        if (StringUtils.isNotBlank(metadata.getAllowedTools())) {
            metadata.setAllowedTools(metadata.getAllowedTools().trim());
        }
    }

    private ArchiveRoot stripSingleRootDirectory(Map<String, byte[]> rawFiles) {
        String root = null;
        for (String path : rawFiles.keySet()) {
            int slash = path.indexOf('/');
            if (slash < 1) {
                return new ArchiveRoot(rawFiles, null);
            }
            String candidate = path.substring(0, slash);
            if (root == null) {
                root = candidate;
            } else if (!root.equals(candidate)) {
                return new ArchiveRoot(rawFiles, null);
            }
        }
        if (root == null) {
            return new ArchiveRoot(rawFiles, null);
        }
        Map<String, byte[]> normalized = new LinkedHashMap<>();
        String prefix = root + "/";
        for (Map.Entry<String, byte[]> entry : rawFiles.entrySet()) {
            String path = entry.getKey();
            if (!path.startsWith(prefix)) {
                return new ArchiveRoot(rawFiles, null);
            }
            String stripped = path.substring(prefix.length());
            if (stripped.isEmpty()) {
                continue;
            }
            normalized.put(stripped, entry.getValue());
        }
        return new ArchiveRoot(normalized, root);
    }

    private String normalizePath(String rawPath) {
        if (StringUtils.isBlank(rawPath)) {
            throw new BizException("Skill ZIP 包含空文件路径");
        }
        String path = rawPath.replace('\\', '/').trim();
        if (path.startsWith("/") || path.contains(":") || path.length() > 256) {
            throw new BizException("Skill ZIP 包含非法路径: " + rawPath);
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new BizException("Skill ZIP 包含路径穿透风险: " + rawPath);
            }
        }
        return String.join("/", segments);
    }

    private boolean shouldIgnore(String path) {
        return IGNORED_ARCHIVE_FILES.contains(path)
                || path.startsWith("__MACOSX/")
                || path.endsWith("/.DS_Store");
    }

    private String decodeUtf8(String filename, byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            String content = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            for (byte value : bytes) {
                if (value == 0) {
                    throw new BizException("文件 [" + filename + "] 不能包含二进制 NULL 字节");
                }
            }
            return content;
        } catch (CharacterCodingException e) {
            throw new BizException("文件 [" + filename + "] 必须是合法 UTF-8 文本");
        }
    }

    private String stringField(Map<?, ?> values, String fieldName) {
        Object value = values.get(fieldName);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new BizException("SKILL.md frontmatter 的 " + fieldName + " 必须是字符串");
        }
        return ((String) value).trim();
    }

    private String sha256(byte[] bytes) {
        return "sha256:" + toHex(MessageDigestHolder.digest(bytes));
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private record ArchiveRoot(Map<String, byte[]> files, String rootDirectory) {
    }

    private static final class MessageDigestHolder {
        private static byte[] digest(byte[] bytes) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(bytes);
            } catch (Exception e) {
                throw new IllegalStateException("SHA-256 算法不可用", e);
            }
        }
    }
}
