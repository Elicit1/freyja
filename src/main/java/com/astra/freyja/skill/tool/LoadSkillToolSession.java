package com.astra.freyja.skill.tool;

import com.astra.freyja.skill.model.LoadedSkill;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 单次导演规划请求中的 Skill 加载上下文会话。
 * 用于在 Tool Calling 执行期间跟踪加载的技能版本与内容哈希。
 *
 * <p>本会话不人为限制 Skill 数量或调用次数。是否继续加载由调用方/模型决定，
 * 工具只负责版本快照、重复加载幂等和调用审计。</p>
 */
@Getter
public class LoadSkillToolSession {

    /** 成功或失败的工具加载尝试次数，仅用于审计，不作为限制条件。 */
    private int callCount = 0;
    private final Map<String, LoadedSkill> loadedSkills = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Long> versionSnapshot = Collections.synchronizedMap(new LinkedHashMap<>());
    /** load_skill 工具实际收到的调用名称，包含重复和失败调用，按调用顺序保留。 */
    private final List<String> invocationHistory = Collections.synchronizedList(new ArrayList<>());

    public LoadSkillToolSession() {
    }

    public LoadSkillToolSession(Map<String, Long> snapshot) {
        if (snapshot != null) {
            snapshot.forEach((name, versionId) -> {
                if (name != null && versionId != null) {
                    versionSnapshot.put(name.trim().toLowerCase(), versionId);
                }
            });
        }
    }

    public Optional<Long> resolveVersion(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(versionSnapshot.get(name.trim().toLowerCase()));
    }

    public boolean hasLoaded(String name) {
        if (name == null) return false;
        return loadedSkills.containsKey(name.trim().toLowerCase());
    }

    public LoadedSkill getLoaded(String name) {
        if (name == null) return null;
        return loadedSkills.get(name.trim().toLowerCase());
    }

    public void recordInvocation(String name) {
        if (name != null && !name.isBlank()) {
            invocationHistory.add(name.trim().toLowerCase());
        }
    }

    public int getInvocationCount() {
        return invocationHistory.size();
    }

    /** 返回去重后的 Skill 调用名称，保持首次调用顺序。 */
    public List<String> getInvokedSkillNames() {
        synchronized (invocationHistory) {
            return invocationHistory.stream().distinct().toList();
        }
    }

    /** 返回完整调用历史快照，包含重复调用。 */
    public List<String> getInvocationHistory() {
        synchronized (invocationHistory) {
            return List.copyOf(invocationHistory);
        }
    }

    /** 返回成功加载的 Skill 名称快照。 */
    public List<String> getLoadedSkillNames() {
        synchronized (loadedSkills) {
            return List.copyOf(loadedSkills.keySet());
        }
    }

    public void recordCall(String name, LoadedSkill skill) {
        callCount++;
        if (skill != null && name != null && !name.isBlank()) {
            loadedSkills.put(name.trim().toLowerCase(), skill);
        }
    }

    /**
     * 将用户指定的必用 Skill 预加载到当前会话。
     * 预加载不计入工具调用次数，但后续重复的 load_skill 会返回 ALREADY_LOADED。
     */
    public void preload(String name, LoadedSkill skill) {
        if (name != null && !name.isBlank() && skill != null) {
            loadedSkills.put(name.trim().toLowerCase(), skill);
        }
    }
}
