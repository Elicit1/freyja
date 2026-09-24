package com.astra.freyja.skill;

import com.astra.freyja.dao.AiSkillFileMapper;
import com.astra.freyja.dao.AiSkillMapper;
import com.astra.freyja.dao.AiSkillVersionMapper;
import com.astra.freyja.entity.AiSkill;
import com.astra.freyja.entity.AiSkillFile;
import com.astra.freyja.entity.AiSkillVersion;
import com.astra.freyja.skill.model.StandardSkillMetadata;
import com.astra.freyja.skill.model.StandardSkillPackage;
import com.astra.freyja.skill.repository.SkillRepository;
import com.astra.freyja.skill.service.SkillAdminServiceImpl;
import com.astra.freyja.skill.validation.SkillPackageValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillAdminServiceTest {

    @Mock
    private AiSkillMapper skillMapper;
    @Mock
    private AiSkillVersionMapper versionMapper;
    @Mock
    private AiSkillFileMapper fileMapper;
    @Mock
    private SkillPackageValidator packageValidator;
    @Mock
    private SkillRepository skillRepository;
    @InjectMocks
    private SkillAdminServiceImpl service;

    @Test
    void uploadStandardPackageCreatesVersionAndSwitchesCurrentPointer() {
        byte[] entrypoint = "---\nname: cinematography\ndescription: 摄影知识\n---\n# Camera\n"
                .getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", entrypoint);
        files.put("references/camera.md", "参考资料".getBytes(StandardCharsets.UTF_8));
        StandardSkillPackage skillPackage = StandardSkillPackage.builder()
                .metadata(StandardSkillMetadata.builder().name("cinematography").description("摄影知识").build())
                .entrypointContent(new String(entrypoint, StandardCharsets.UTF_8))
                .files(files)
                .fileHashes(Map.of("SKILL.md", "sha256:entry", "references/camera.md", "sha256:reference"))
                .packageHash("sha256:package")
                .totalSize(entrypoint.length + "参考资料".getBytes(StandardCharsets.UTF_8).length)
                .build();
        when(packageValidator.validate(any())).thenReturn(skillPackage);
        doAnswer(invocation -> {
            AiSkill skill = invocation.getArgument(0);
            skill.setId(10L);
            return 1;
        }).when(skillMapper).insert(any(AiSkill.class));
        doAnswer(invocation -> {
            AiSkillVersion version = invocation.getArgument(0);
            version.setId(20L);
            return 1;
        }).when(versionMapper).insert(any(AiSkillVersion.class));
        when(skillMapper.selectOne(any())).thenReturn(null);
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(fileMapper.selectList(any())).thenReturn(List.of());

        var result = service.uploadPackage(null, "摄影", null,
                new MockMultipartFile("file", "cinematography.zip", "application/zip", new byte[]{1}));

        assertThat(result.getVersion().getVersion()).isEqualTo("1");
        assertThat(result.getVersion().getContentHash()).isEqualTo("sha256:package");
        verify(skillMapper, atLeastOnce()).updateById(any(AiSkill.class));
        verify(versionMapper).insert(argThat((AiSkillVersion v) -> v.getSkillId().equals(10L)
                && v.getRevisionNo() == 1 && v.getContent().equals(new String(entrypoint, StandardCharsets.UTF_8))));
        verify(fileMapper, times(2)).insert(any(AiSkillFile.class));
        verify(skillRepository, times(2)).saveFile(anyString(), any(), anyString());
    }

    @Test
    void deleteSkillRemovesVersionsAndMetadata() {
        AiSkill skill = new AiSkill();
        skill.setId(10L);
        skill.setName("cinematography");
        when(skillMapper.selectById(10L)).thenReturn(skill);
        AiSkillVersion version = new AiSkillVersion();
        version.setId(20L);
        when(versionMapper.selectList(any())).thenReturn(List.of(version));
        when(fileMapper.selectList(any())).thenReturn(List.of());

        service.deleteSkill(10L);

        verify(versionMapper).hardDeleteBySkillId(10L);
        verify(skillMapper).hardDeleteById(10L);
        verify(skillRepository).removeFiles(List.of());
    }

    @Test
    void exportSkillPackageBuildsZipWithAllFiles() throws Exception {
        AiSkill skill = new AiSkill();
        skill.setId(10L);
        skill.setName("cinematography");
        skill.setCurrentVersionId(20L);

        AiSkillVersion version = new AiSkillVersion();
        version.setId(20L);
        version.setSkillId(10L);
        version.setRevisionNo(2);
        version.setContent("---\nname: cinematography\n---\n# Camera v2");

        AiSkillFile file1 = new AiSkillFile();
        file1.setSkillVersionId(20L);
        file1.setRelativePath("SKILL.md");
        file1.setObjectKey("skills/cinematography/v2/entry.md");

        AiSkillFile file2 = new AiSkillFile();
        file2.setSkillVersionId(20L);
        file2.setRelativePath("references/shots.md");
        file2.setObjectKey("skills/cinematography/v2/shots.md");

        when(skillMapper.selectById(10L)).thenReturn(skill);
        when(versionMapper.selectById(20L)).thenReturn(version);
        when(fileMapper.selectList(any())).thenReturn(List.of(file1, file2));
        when(skillRepository.loadFile(eq("skills/cinematography/v2/entry.md"), any(), any()))
                .thenReturn("---\nname: cinematography\n---\n# Camera v2".getBytes(StandardCharsets.UTF_8));
        when(skillRepository.loadFile(eq("skills/cinematography/v2/shots.md"), any(), any()))
                .thenReturn("分镜参考内容".getBytes(StandardCharsets.UTF_8));

        var pkg = service.exportSkillPackage(10L);

        assertThat(pkg.filename()).isEqualTo("cinematography-v2.zip");
        assertThat(pkg.zipData()).isNotEmpty();

        // 校验解压出来的条目与内容
        Map<String, String> unzipped = new LinkedHashMap<>();
        try (var zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(pkg.zipData()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                unzipped.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
                zis.closeEntry();
            }
        }

        assertThat(unzipped).containsKeys("SKILL.md", "references/shots.md");
        assertThat(unzipped.get("SKILL.md")).contains("# Camera v2");
        assertThat(unzipped.get("references/shots.md")).isEqualTo("分镜参考内容");
    }

    @Test
    void exportSkillPackageThrowsWhenNoPublishedVersion() {
        AiSkill skill = new AiSkill();
        skill.setId(10L);
        skill.setName("empty-skill");
        skill.setCurrentVersionId(null);

        when(skillMapper.selectById(10L)).thenReturn(skill);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.exportSkillPackage(10L))
                .isInstanceOf(com.astra.freyja.common.BizException.class)
                .hasMessageContaining("暂无已发布版本");
    }
}
