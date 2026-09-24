package com.astra.freyja.skill;

import com.astra.freyja.common.BizException;
import com.astra.freyja.skill.validation.SkillPackageValidator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillPackageValidatorTest {

    private final SkillPackageValidator validator = new SkillPackageValidator();

    @Test
    void acceptsStandardZipAndPreservesOptionalDirectories() {
        byte[] zip = zip(List.of(
                entry("SKILL.md", skillMarkdown()),
                entry("references/camera.md", "镜头参考"),
                entry("scripts/render.sh", "#!/bin/sh\necho render"),
                entry("assets/look.bin", "binary")));

        var result = validator.validate(new MockMultipartFile("file", "cinematography.zip", "application/zip", zip));

        assertThat(result.getMetadata().getName()).isEqualTo("cinematography");
        assertThat(result.getMetadata().getDescription()).isEqualTo("摄影知识");
        assertThat(result.getEntrypointContent()).contains("# Camera");
        assertThat(result.getFiles()).containsKeys("SKILL.md", "references/camera.md", "scripts/render.sh", "assets/look.bin");
        assertThat(result.getPackageHash()).startsWith("sha256:");
    }

    @Test
    void acceptsSingleTopLevelDirectoryWhenDirectoryMatchesSkillName() {
        byte[] zip = zip(List.of(
                entry("cinematography/SKILL.md", skillMarkdown()),
                entry("cinematography/references/camera.md", "参考")));

        var result = validator.validate(new MockMultipartFile("file", "cinematography.zip", "application/zip", zip));

        assertThat(result.getFiles()).containsKeys("SKILL.md", "references/camera.md");
    }

    @Test
    void rejectsLegacySingleMarkdownUpload() {
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile(
                "file", "cinematography.md", "text/markdown", skillMarkdown().getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(".zip");
    }

    @Test
    void rejectsMissingFrontmatter() {
        byte[] zip = zip(List.of(entry("SKILL.md", "# Camera")));

        assertThatThrownBy(() -> validator.validate(new MockMultipartFile(
                "file", "invalid.zip", "application/zip", zip)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("frontmatter");
    }

    @Test
    void rejectsZipSlipPath() {
        byte[] zip = zip(List.of(entry("../SKILL.md", skillMarkdown())));

        assertThatThrownBy(() -> validator.validate(new MockMultipartFile(
                "file", "invalid.zip", "application/zip", zip)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("穿透");
    }

    private String skillMarkdown() {
        return "---\n"
                + "name: cinematography\n"
                + "description: 摄影知识\n"
                + "license: Apache-2.0\n"
                + "metadata:\n"
                + "  author: freyja\n"
                + "allowed-tools: Read\n"
                + "---\n"
                + "# Camera\n";
    }

    private ZipFileEntry entry(String path, String content) {
        return new ZipFileEntry(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] zip(List<ZipFileEntry> entries) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                for (ZipFileEntry entry : entries) {
                    zip.putNextEntry(new ZipEntry(entry.path()));
                    zip.write(entry.content());
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record ZipFileEntry(String path, byte[] content) {
    }
}
