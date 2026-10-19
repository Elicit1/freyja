package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiSkillFileMapper;
import com.astra.freyja.entity.AiSkillFile;
import com.astra.freyja.skill.model.SkillCatalogItem;
import com.astra.freyja.skill.repository.SkillRepository;
import com.astra.freyja.skill.service.SkillCatalogService;
import com.astra.freyja.skill.service.SkillContentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Published knowledge only: never exports internal AI callbacks, executes scripts or changes Skills. */
@Component
@RequiredArgsConstructor
public class McpSkillTools {
    private static final int MAX_TEXT_BYTES = 128 * 1024;
    private final SkillCatalogService catalog;
    private final SkillContentService contents;
    private final AiSkillFileMapper files;
    private final SkillRepository repository;
    public record Read(String name, @ToolParam(required = false) String versionId) {}
    public record ReadFile(String name, String versionId, String path) {}

    public List<McpTool<?>> tools() {
        return List.of(
                McpTool.read("list_skills", "创作开始及任务阶段改变时先读取已启用且已发布的 Skill 轻量目录，按 name/description 选择并读取相关正文，不返回全文。平台创作先读 freyja-platform-guide；原创、续写、改编或实质重写剧情先读 story-writing 及其 references/story-design.md、references/story-review.md，在当前任务中设计、举证自审和修稿，无阻断问题再简短说明并保存剧本，纯查询/排序/媒体更新不触发故事重审。分镜使用 cinematography/camera-direction；人物/造型设定图使用 character-sheet-design；MiniMax H3 视频卡片使用 h3-prompt-writing。按任务读取参考文件，用户明确要求和锁定优先。", McpInputs.Search.class, (c, i) -> list(i)),
                McpTool.read("read_skill", "按名称读取已启用 Skill 的 SKILL.md 和可读 references/ 文件目录；可传目录中的 versionId 固定版本，版本改变返回409。仅返回知识，不调用内部 AI。H3 首尾帧随后读取 base-en.txt，多模态参考读取 ref-en.txt。", Read.class, (c, i) -> read(i)),
                McpTool.read("read_skill_file", "读取 read_skill 返回的 references/ 下 .md/.txt 文本；必填 name、versionId、path，固定版本防止规范漂移。单文件最多128 KiB，不执行脚本、不返回存储地址。", ReadFile.class, (c, i) -> readFile(i)));
    }
    private Object list(McpInputs.Search input) {
        int page = McpScope.page(input.page()), size = McpScope.size(input.size());
        if (input.keyword() != null && input.keyword().length() > 200) throw new BizException(400, "关键词过长");
        String keyword = input.keyword() == null ? "" : input.keyword().trim().toLowerCase(Locale.ROOT);
        List<SkillCatalogItem> matches = catalog.getEnabledCatalog().stream().filter(item ->
                (item.getName() + " " + Objects.toString(item.getDisplayName(), "") + " " + Objects.toString(item.getDescription(), "")).toLowerCase(Locale.ROOT).contains(keyword)).toList();
        long start = (long) (page - 1) * size;
        List<SkillCatalogItem> records = start >= matches.size() ? List.of() : matches.subList((int) start, (int) Math.min(start + size, matches.size()));
        return Map.of("records", records, "page", page, "size", size, "total", matches.size(), "hasMore", start + size < matches.size());
    }
    private SkillCatalogItem enabled(String name, String versionId) {
        if (name == null || !name.matches("[a-z0-9][a-z0-9-]{0,63}")) throw new BizException(400, "Skill name 必须为小写 slug");
        SkillCatalogItem item = catalog.getEnabledCatalog().stream().filter(s -> name.equals(s.getName())).findFirst()
                .orElseThrow(() -> new BizException(404, "SKILL_UNAVAILABLE: Skill 未启用或未发布"));
        if (versionId != null && !Objects.equals(McpScope.id(versionId), item.getVersionId()))
            throw new BizException(409, "SKILL_VERSION_CHANGED: 请重新读取 Skill 目录和正文");
        return item;
    }
    private Object read(Read input) {
        var item = enabled(input.name(), input.versionId());
        var loaded = contents.loadSkillVersion(item.getVersionId());
        if (loaded == null || !item.getName().equals(loaded.getName())) throw new BizException(409, "Skill 版本内容不一致");
        String content = bounded(loaded.getContent());
        List<Object> references = new ArrayList<>();
        for (var file : files.selectList(new LambdaQueryWrapper<AiSkillFile>().eq(AiSkillFile::getSkillVersionId, item.getVersionId())
                .eq(AiSkillFile::getFileType, "REFERENCE").orderByAsc(AiSkillFile::getRelativePath))) {
            if (safePath(file.getRelativePath()) && file.getContentSize() != null && file.getContentSize() >= 0 && file.getContentSize() <= MAX_TEXT_BYTES)
                references.add(Map.of("path", file.getRelativePath(), "size", file.getContentSize()));
        }
        return Map.of("name", item.getName(), "version", item.getVersion(), "versionId", item.getVersionId().toString(),
                "contentHash", Objects.toString(loaded.getContentHash(), ""), "content", content, "references", references);
    }
    private Object readFile(ReadFile input) {
        if (!safePath(input.path())) throw new BizException(400, "只允许 references/ 下规范相对路径的 .md/.txt 文件");
        if (input.versionId() == null) throw new BizException(400, "versionId 必填，请先读取 Skill");
        var item = enabled(input.name(), input.versionId());
        var file = files.selectOne(new LambdaQueryWrapper<AiSkillFile>().eq(AiSkillFile::getSkillVersionId, item.getVersionId())
                .eq(AiSkillFile::getRelativePath, input.path()).eq(AiSkillFile::getFileType, "REFERENCE"));
        if (file == null) throw new BizException(404, "Skill 参考文件不存在");
        if (file.getContentSize() == null || file.getContentSize() < 0 || file.getContentSize() > MAX_TEXT_BYTES)
            throw new BizException(413, "Skill 参考文件最多128 KiB");
        if (file.getObjectKey() == null || file.getObjectKey().isBlank() || file.getContentHash() == null || file.getContentHash().isBlank())
            throw new BizException(409, "Skill 参考文件缺少存储或校验信息");
        String content;
        try { content = repository.loadTextFile(file.getObjectKey(), file.getContentHash(), file.getContentSize()); }
        catch (RuntimeException e) { throw new BizException(502, "Skill 参考文件读取失败"); }
        return Map.of("name", item.getName(), "versionId", item.getVersionId().toString(), "path", input.path(),
                "contentHash", file.getContentHash(), "content", bounded(content));
    }
    private static String bounded(String text) {
        if (text == null || text.isBlank()) throw new BizException(409, "Skill 正文为空");
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) throw new BizException(413, "Skill 单次正文最多128 KiB，请拆分到 references/ 文件");
        return text;
    }
    private static boolean safePath(String path) {
        return path != null && path.length() <= 256 && path.matches("references/[A-Za-z0-9._/-]+\\.(?:md|txt)")
                && !path.contains("//") && !path.contains("/./") && !path.contains("/../");
    }
}
