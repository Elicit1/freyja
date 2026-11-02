package com.astra.freyja.service.video;

import java.util.*;
import java.util.regex.*;
import static com.astra.freyja.service.video.RenderValidationReport.*;

/** Parses a closed protocol grammar. Never infers actions, identities or camera intent. */
public final class RenderInputValidator {
    private RenderInputValidator() {}
    private static final List<String> REF_SECTIONS = List.of("subject_definitions", "summary", "retention_analysis",
            "detailed_description", "overall_soundscape", "non_diegetic_music");
    private static final List<String> FL_SECTIONS = List.of("integrated_multimodal_description", "overall_soundscape", "non_diegetic_music");
    private static final Pattern SECTION = Pattern.compile("(?m)^[\\t ]*(subject_definitions|summary|retention_analysis|detailed_description|integrated_multimodal_description|overall_soundscape|non_diegetic_music):[\\t ]*");
    private static final Pattern TAG = Pattern.compile("<[\\t ]*(?:Picture|Audio|Video|Subject|Image|Ref|Frame|Img|Clip)[^>\\r\\n]*(?:>|$)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern VALID_TAG = Pattern.compile("<(Picture|Audio|Video|Subject) ([1-9][0-9]*)>");
    private static final Pattern BARE_TAG = Pattern.compile("(?<![<\\w])(?:Picture|Audio|Video|Subject) [0-9]+\\b(?!>)");
    private static final Pattern DEFINITION = Pattern.compile("(?m)^[\\t ]*(<Subject [1-9][0-9]*>)(?:[\\t ]+is\\b|[\\t ]*:)");
    private static final Pattern ALIGNMENT = Pattern.compile("^How the reference pictures align with the target video[^\\r\\n]*$");

    public static RenderValidationReport validate(Long shotId, String prompt, boolean reference, List<Slot> slots) {
        var report = new RenderValidationReport();
        report.setSlots(new ArrayList<>(slots));
        String original = prompt == null ? "" : prompt;
        String text = maskLiterals(original);
        List<String> expected = reference ? REF_SECTIONS : FL_SECTIONS;
        Matcher sections = SECTION.matcher(text);
        List<String> names = new ArrayList<>(); List<Integer> starts = new ArrayList<>(), ends = new ArrayList<>();
        while (sections.find()) { names.add(sections.group(1)); starts.add(sections.start()); ends.add(sections.end()); }
        if (!names.equals(expected)) add(report, shotId, "PROMPT_SECTIONS_INVALID", null, null,
                "提示词段落必须完整且按顺序出现：" + String.join(" → ", expected), "按当前生成模式重新生成或修正段落");
        for (int i = 0; i < names.size(); i++) {
            // Quoted literals are still valid section content, even though they are not bindings.
            if (original.substring(ends.get(i), i + 1 < starts.size() ? starts.get(i + 1) : original.length()).isBlank())
                add(report, shotId, "PROMPT_SECTION_EMPTY", null, line(original, starts.get(i)),
                        names.get(i) + " 段落不能为空", "补充内容；无声音或配乐时填写 N/A");
        }
        Set<String> used = new LinkedHashSet<>(), subjects = new LinkedHashSet<>(), defined = new LinkedHashSet<>();
        Matcher tags = TAG.matcher(text);
        while (tags.find()) {
            String label = tags.group(); Matcher valid = VALID_TAG.matcher(label);
            if (!valid.matches()) {
                add(report, shotId, "REFERENCE_LABEL_INVALID", label, line(original, tags.start()),
                        "素材标签格式无效：" + label, "使用 <Picture N>、<Audio N>、<Video N> 或 <Subject N>，编号从 1 开始");
                continue;
            }
            if ("Subject".equals(valid.group(1))) subjects.add(label); else used.add(label);
        }
        String bareText = text;
        if (!reference) {
            int headerEnd = starts.isEmpty() ? text.length() : starts.getFirst();
            String header = text.substring(0, headerEnd).strip();
            if (!ALIGNMENT.matcher(header).matches())
                add(report, shotId, "FRAME_ALIGNMENT_MISSING", null, 1, "首尾帧提示词缺少正式时间对齐声明", "使用当前首尾帧模式的对齐声明");
            else {
                Matcher barePictures = Pattern.compile("(?<![<\\w])Picture ([1-9][0-9]*)\\b(?!>)").matcher(header);
                while (barePictures.find()) used.add("<Picture " + barePictures.group(1) + ">");
                if (!used.containsAll(List.of("<Picture 1>", "<Picture 2>")))
                    add(report, shotId, "FRAME_ALIGNMENT_MISSING", null, 1, "时间对齐声明必须明确 Picture 1 和 Picture 2", "补全首尾帧对齐声明");
            }
            bareText = " ".repeat(headerEnd) + text.substring(headerEnd);
        }
        Matcher bare = BARE_TAG.matcher(bareText);
        while (bare.find()) add(report, shotId, "REFERENCE_LABEL_INVALID", bare.group(), line(original, bare.start()),
                "正文素材引用缺少尖括号：" + bare.group(), "使用正式的 <Picture N>、<Audio N>、<Video N> 或 <Subject N> 标签");
        Set<String> attached = new LinkedHashSet<>(); slots.forEach(s -> attached.add(s.label()));
        for (String label : used) if (!attached.contains(label))
            add(report, shotId, "REFERENCE_NOT_ATTACHED", label, labelLine(text, label),
                    "提示词引用了 " + label + "，但本次未挂载该素材", "补充对应素材，或修改提示词引用后重新检查");
        for (String label : attached) if (!used.contains(label))
            add(report, shotId, "ATTACHED_REFERENCE_UNUSED", label, null,
                    "已挂载 " + label + "，但提示词未引用该素材", "补充正式引用，或移除该素材后重新检查");
        int definitionSection = names.indexOf("subject_definitions");
        if (definitionSection >= 0) {
            int end = definitionSection + 1 < starts.size() ? starts.get(definitionSection + 1) : text.length();
            Matcher definitions = DEFINITION.matcher(text.substring(ends.get(definitionSection), end));
            while (definitions.find()) {
                if (!defined.add(definitions.group(1))) add(report, shotId, "SUBJECT_DEFINED_TWICE", definitions.group(1), null,
                        "主体重复定义：" + definitions.group(1), "每个主体只定义一次");
            }
        }
        for (String label : subjects) if (!defined.contains(label))
            add(report, shotId, "SUBJECT_UNDEFINED", label, labelLine(text, label),
                    "主体 " + label + " 没有正式定义", "在 subject_definitions 中使用 <Subject N> is ... 定义主体");
        report.setSubmissionReady(report.getIssues().isEmpty());
        report.setDispatchReady(report.isSubmissionReady() && slots.stream().noneMatch(Slot::pending));
        return report;
    }

    /** Literal dialogue and quoted on-screen text cannot declare bindings. Preserve offsets/newlines. */
    static String maskLiterals(String text) {
        char[] masked = text.toCharArray();
        Matcher literal = Pattern.compile("<d>[\\s\\S]*?</d>|\"(?:\\\\.|[^\"\\\\])*\"|“[^”]*”").matcher(text);
        while (literal.find()) for (int i = literal.start(); i < literal.end(); i++)
            if (masked[i] != '\n' && masked[i] != '\r') masked[i] = ' ';
        return new String(masked);
    }
    private static Integer labelLine(String text, String label) { int index = text.indexOf(label); return index < 0 ? null : line(text, index); }
    private static int line(String text, int offset) { return 1 + (int)text.substring(0, offset).chars().filter(c -> c == '\n').count(); }
    private static void add(RenderValidationReport report, Long shotId, String code, String label, Integer line, String message, String suggestion) {
        report.getIssues().add(new Issue(code, shotId, label, "prompt", line, message, suggestion));
    }
}
