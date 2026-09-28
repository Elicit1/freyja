package com.astra.freyja.skill.tool;

import com.astra.freyja.dao.AiSkillFileMapper;
import com.astra.freyja.entity.AiSkillFile;
import com.astra.freyja.skill.repository.SkillRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** 为一次模型请求提供只读的 Skill references/ 文本读取工具。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadSkillFileToolFactory {

    private static final long MAX_REFERENCE_BYTES = 128 * 1024;

    private final AiSkillFileMapper fileMapper;
    private final SkillRepository skillRepository;

    public ToolCallback createTool(LoadSkillToolSession session) {
        return FunctionToolCallback.builder("read_skill_file", (ReadSkillFileRequest request) -> {
            String name = request == null ? null : StringUtils.trimToNull(request.getName());
            String path = request == null ? null : StringUtils.trimToNull(request.getPath());
            log.info("[ReadSkillFile] requested: name={}, path={}", name, StringUtils.abbreviate(path, 256));
            if (name == null || path == null) {
                return error(name, path, "必须提供 Skill 名称和 references/ 下的相对路径");
            }
            name = name.toLowerCase(Locale.ROOT);
            // 只接受包内的规范相对路径，不允许 URL、绝对路径、路径折叠或分隔符别名。
            if (!path.startsWith("references/") || path.contains("\\") || path.contains(":")
                    || path.length() > 256 || !path.matches("references/[A-Za-z0-9._/-]+\\.(?:txt|md)")
                    || path.contains("//") || path.contains("/./") || path.contains("/../")
                    || path.endsWith("/.") || path.endsWith("/..")) {
                return error(name, path, "只允许读取 Skill 包 references/ 下的 .txt 或 .md 文件");
            }
            if (!session.hasLoaded(name)) {
                return error(name, path, "请先成功调用 load_skill 加载该 Skill");
            }
            Long versionId = session.resolveVersion(name).orElse(null);
            if (versionId == null) {
                return error(name, path, "Skill 不在本次请求的版本快照中");
            }
            try {
                AiSkillFile file = fileMapper.selectOne(new LambdaQueryWrapper<AiSkillFile>()
                        .eq(AiSkillFile::getSkillVersionId, versionId)
                        .eq(AiSkillFile::getRelativePath, path));
                if (file == null || !"REFERENCE".equals(file.getFileType())) {
                    return error(name, path, "当前 Skill 版本中不存在该参考文本文件");
                }
                if (file.getContentSize() == null || file.getContentSize() < 0
                        || file.getContentSize() > MAX_REFERENCE_BYTES) {
                    return error(name, path, "参考文本文件超过读取大小限制");
                }
                if (StringUtils.isBlank(file.getObjectKey()) || StringUtils.isBlank(file.getContentHash())) {
                    return error(name, path, "参考文本文件缺少存储或校验信息");
                }
                String content = skillRepository.loadTextFile(
                        file.getObjectKey(), file.getContentHash(), file.getContentSize());
                if (StringUtils.isBlank(content)) {
                    return error(name, path, "参考文本文件正文为空");
                }
                session.recordReferenceFile(name, path);
                log.info("[ReadSkillFile] loaded: name={}, versionId={}, path={}, bytes={}, chars={}, estimatedTokens={}",
                        name, versionId, path, file.getContentSize(), content.length(), Math.ceilDiv(content.length(), 4));
                return new ReadSkillFileResponse("SUCCESS", name, path, content, null);
            } catch (Exception e) {
                log.warn("[ReadSkillFile] failed: name={}, versionId={}, path={}, reason={}",
                        name, versionId, path, e.getMessage());
                return new ReadSkillFileResponse("ERROR", name, path, null, "读取 Skill 参考文件失败");
            }
        })
                .description("当已加载 Skill 的 SKILL.md 指示读取 references/ 文件时，在生成最终答案前调用本工具获取完整正文。name 是 Skill 名称，path 是 SKILL.md 中的相对路径。返回当前版本的文件正文。")
                .inputType(ReadSkillFileRequest.class)
                .build();
    }

    private ReadSkillFileResponse error(String name, String path, String message) {
        log.warn("[ReadSkillFile] rejected: name={}, path={}, reason={}",
                name, StringUtils.abbreviate(path, 256), message);
        return new ReadSkillFileResponse("ERROR", name, path, null, message);
    }
}
