package com.astra.freyja.skill.service;

import com.astra.freyja.dao.AiSkillMapper;
import com.astra.freyja.entity.AiSkill;
import com.astra.freyja.skill.model.StandardSkillMetadata;
import com.astra.freyja.skill.model.StandardSkillPackage;
import com.astra.freyja.skill.validation.SkillPackageValidator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class SkillBuiltinInitializerTest {

    @Mock
    private AiSkillMapper skillMapper;

    @Mock
    private SkillAdminService skillAdminService;

    @Mock
    private SkillPackageValidator packageValidator;

    @InjectMocks
    private SkillBuiltinInitializer initializer;

    @Test
    void initBuiltinSkills_skipsWhenSkillAlreadyExists(@TempDir Path tempDir) throws Exception {
        // 创建测试 ZIP
        File zipFile = tempDir.resolve("camera-direction.zip").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("SKILL.md"));
            zos.write("---\nname: camera-direction\ndescription: test\n---\n# Test".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        StandardSkillPackage pkg = StandardSkillPackage.builder()
                .metadata(StandardSkillMetadata.builder().name("camera-direction").description("test").build())
                .entrypointContent("# Test")
                .files(Map.of("SKILL.md", new byte[0]))
                .packageHash("hash")
                .totalSize(100L)
                .build();
        when(packageValidator.validateZip(any(InputStream.class), anyLong())).thenReturn(pkg);

        AiSkill existing = new AiSkill();
        existing.setName("camera-direction");
        existing.setCurrentVersionId(12345L);
        when(skillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        ReflectionTestUtils.setField(initializer, "customSkillsDir", tempDir.toString());
        initializer.initBuiltinSkills();

        verify(skillAdminService, never()).saveSkillPackage(any(), any(), any(), any());
    }

    @Test
    void initBuiltinSkills_importsWhenSkillNotExists(@TempDir Path tempDir) throws Exception {
        File zipFile = tempDir.resolve("cinematography.zip").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("SKILL.md"));
            zos.write("---\nname: cinematography\ndescription: 摄影知识\n---\n# Camera".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        StandardSkillPackage pkg = StandardSkillPackage.builder()
                .metadata(StandardSkillMetadata.builder().name("cinematography").description("摄影知识").build())
                .entrypointContent("# Camera")
                .files(Map.of("SKILL.md", new byte[0]))
                .packageHash("hash")
                .totalSize(100L)
                .build();
        when(packageValidator.validateZip(any(InputStream.class), anyLong())).thenReturn(pkg);
        when(skillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        ReflectionTestUtils.setField(initializer, "customSkillsDir", tempDir.toString());
        initializer.initBuiltinSkills();

        verify(skillAdminService, times(1)).saveSkillPackage(eq(null), eq(null), eq(10), eq(pkg));
    }

    @Test
    void distributableCharacterSheetSkillIsValidatedAndAutoPublished(@TempDir Path tempDir) throws Exception {
        Path archive = Path.of("skills", "character-sheet-design.zip");
        Files.copy(archive, tempDir.resolve(archive.getFileName()));
        ReflectionTestUtils.setField(initializer, "customSkillsDir", tempDir.toString());
        ReflectionTestUtils.setField(initializer, "packageValidator", new SkillPackageValidator());
        when(skillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        initializer.initBuiltinSkills();
        var imported = org.mockito.ArgumentCaptor.forClass(StandardSkillPackage.class);
        verify(skillAdminService).saveSkillPackage(eq(null), eq(null), eq(10), imported.capture());
        var pkg = imported.getValue();
        assertEquals("character-sheet-design", pkg.getMetadata().getName());
        String source = Files.readString(Path.of("skills", "character-sheet-design", "SKILL.md"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(source, pkg.getEntrypointContent());
        assertArrayEquals(source.getBytes(StandardCharsets.UTF_8), pkg.getFiles().get("SKILL.md"));
        assertTrue(pkg.getTotalSize() < 128 * 1024L);
        verifyNoInteractions(packageValidator);
    }
}
