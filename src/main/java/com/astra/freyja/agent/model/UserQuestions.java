package com.astra.freyja.agent.model;

import com.astra.freyja.common.BizException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Model-authored questions and explicit user answers; no local semantic inference. */
public record UserQuestions(List<Question> questions) {
    public record Option(String label, String description, boolean recommended) {}
    public record Question(String id, String header, String question, List<Option> options) {}
    public record Answer(String questionId, String optionLabel, String customAnswer) {}

    public UserQuestions validated() {
        if (questions == null || questions.isEmpty() || questions.size() > 3)
            throw new BizException("每次需要提供 1–3 个问题");
        var ids = new HashSet<String>();
        for (var q : questions) {
            if (q == null || !text(q.id(), 80) || !ids.add(q.id()) || !text(q.header(), 40) || !text(q.question(), 2000))
                throw new BizException("问题需要唯一 ID、简短标题和完整问题文本");
            if (q.options() == null) throw new BizException("自由填写问题的 options 请使用空数组");
            if (!q.options().isEmpty() && (q.options().size() < 2 || q.options().size() > 3))
                throw new BizException("选择题需要提供 2–3 个选项");
            var labels = new HashSet<String>();
            int recommended = 0;
            for (var o : q.options()) {
                if (o == null || !text(o.label(), 100) || !labels.add(o.label()) || !text(o.description(), 500))
                    throw new BizException("选项需要唯一名称和说明");
                if (o.recommended()) recommended++;
            }
            if (recommended > 1) throw new BizException("每个问题最多推荐一个选项");
        }
        return this;
    }

    public String answerText(List<Answer> answers) {
        validated();
        if (answers == null || answers.size() != questions.size()) throw new BizException("请回答所有问题");
        var ids = new HashSet<String>();
        var lines = new java.util.ArrayList<String>();
        for (var answer : answers) {
            if (answer == null || !ids.add(answer.questionId())) throw new BizException("回答的问题 ID 不能重复");
            var q = questions.stream().filter(v -> Objects.equals(v.id(), answer.questionId())).findFirst()
                    .orElseThrow(() -> new BizException("回答不属于当前问题"));
            boolean selected = answer.optionLabel() != null && !answer.optionLabel().isBlank();
            boolean custom = answer.customAnswer() != null && !answer.customAnswer().isBlank();
            if (selected == custom) throw new BizException("每个问题请选择一个选项或填写回答");
            if (selected && q.options().stream().noneMatch(o -> o.label().equals(answer.optionLabel())))
                throw new BizException("选择的选项不属于当前问题");
            if (custom && !text(answer.customAnswer(), 4000)) throw new BizException("每个回答最长 4000 字");
            lines.add(q.header() + "：" + (custom ? answer.customAnswer().trim() : answer.optionLabel()));
        }
        return String.join("\n", lines);
    }
    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
}
