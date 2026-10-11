package com.astra.freyja.agent;

import com.astra.freyja.agent.model.UserQuestions;
import com.astra.freyja.agent.model.UserQuestions.*;
import com.astra.freyja.agent.tool.RequestUserInputTool;
import com.astra.freyja.common.BizException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RequestUserInputToolTest {
    static UserQuestions questions() {
        return new UserQuestions(List.of(
                new Question("genre", "题材", "希望写什么题材？", List.of(new Option("奇幻", "描写新的世界", true), new Option("科幻", "描写未来科技", false))),
                new Question("protagonist", "主角", "主角有什么特点？", List.of())));
    }
    @Test void structuredQuestionsWaitAndSurviveSerialization() {
        var result = new RequestUserInputTool().execute(questions(), null);
        assertEquals("NEED_USER", result.kind());
        var json = new com.astra.freyja.agent.model.AgentJson();
        assertEquals(questions(), json.read(json.write(result.data()), UserQuestions.class));
        assertTrue(new RequestUserInputTool().descriptor().inputSchema().contains("recommended"));
    }
    @Test void selectedAndCustomAnswersAreExplicitAndMayIncludeNovelText() {
        assertEquals("题材：奇幻\n主角：喜欢观察星空", questions().answerText(List.of(
                new Answer("genre", "奇幻", null), new Answer("protagonist", null, "喜欢观察星空"))));
        assertTrue(questions().answerText(List.of(new Answer("genre", null, "历史"), new Answer("protagonist", null, "天文学家"))).contains("历史"));
    }
    @Test void missingDuplicateAndInventedAnswersAreRejected() {
        assertThrows(BizException.class, () -> questions().answerText(List.of(new Answer("genre", "奇幻", null))));
        assertThrows(BizException.class, () -> questions().answerText(List.of(new Answer("genre", "奇幻", null), new Answer("genre", "科幻", null))));
        assertThrows(BizException.class, () -> questions().answerText(List.of(new Answer("genre", "悬疑", null), new Answer("protagonist", null, "勇敢"))));
        assertThrows(BizException.class, () -> questions().answerText(List.of(new Answer("genre", "奇幻", "历史"), new Answer("protagonist", null, "勇敢"))));
    }
    @Test void invalidModelArgumentsReturnFeedbackInsteadOfEnteringWait() {
        var tool = new RequestUserInputTool();
        assertEquals("INVALID_QUESTIONS", tool.execute(new UserQuestions(List.of()), null).errorCode());
        assertEquals("INVALID_QUESTIONS", tool.execute(new UserQuestions(List.of(questions().questions().getFirst(), questions().questions().getFirst())), null).errorCode());
        var q = new Question("style", "风格", "选择风格", List.of(new Option("A", "A说明", true), new Option("B", "B说明", true)));
        assertEquals("INVALID_QUESTIONS", tool.execute(new UserQuestions(List.of(q)), null).errorCode());
    }
}
