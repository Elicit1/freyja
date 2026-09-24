package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.service.impl.CircuitBreakerChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CircuitBreakerChatModelTest {

    @Mock
    private ChatModel delegate;

    @Test
    void testNormalCall() {
        ChatResponse mockResponse = mock(ChatResponse.class);
        when(delegate.call(any(Prompt.class))).thenReturn(mockResponse);

        CircuitBreakerChatModel cb = new CircuitBreakerChatModel(delegate, "TestProvider", 1, 3, 10);
        ChatResponse response = cb.call(new Prompt("hello"));

        assertNotNull(response);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    @Test
    void testCircuitBreakerTripsAfterFailures() {
        when(delegate.call(any(Prompt.class))).thenThrow(new RuntimeException("Network timeout"));

        CircuitBreakerChatModel cb = new CircuitBreakerChatModel(delegate, "TestProvider", 1, 3, 10);

        // 3 consecutive failures
        assertThrows(RuntimeException.class, () -> cb.call(new Prompt("1")));
        assertThrows(RuntimeException.class, () -> cb.call(new Prompt("2")));
        assertThrows(RuntimeException.class, () -> cb.call(new Prompt("3")));

        // 4th call should fail-fast with BizException because circuit is open
        BizException ex = assertThrows(BizException.class, () -> cb.call(new Prompt("4")));
        assertTrue(ex.getMessage().contains("已熔断"));
        // Delegate was only called 3 times, 4th was rejected by circuit breaker
        verify(delegate, times(3)).call(any(Prompt.class));
    }

    @Test
    void testDisabledBreakerDoesNotTrip() {
        when(delegate.call(any(Prompt.class))).thenThrow(new RuntimeException("Error"));

        // enableBreaker = 0
        CircuitBreakerChatModel cb = new CircuitBreakerChatModel(delegate, "TestProvider", 0, 2, 10);

        assertThrows(RuntimeException.class, () -> cb.call(new Prompt("1")));
        assertThrows(RuntimeException.class, () -> cb.call(new Prompt("2")));
        assertThrows(RuntimeException.class, () -> cb.call(new Prompt("3")));

        verify(delegate, times(3)).call(any(Prompt.class));
    }

    @Test
    void shouldDelegateCurrentSpringAiOptionsApi() {
        ChatOptions options = mock(ChatOptions.class);
        when(delegate.getOptions()).thenReturn(options);

        CircuitBreakerChatModel cb = new CircuitBreakerChatModel(delegate, "TestProvider", 0, 2, 10);

        assertSame(options, cb.getOptions());
        assertSame(options, cb.getDefaultOptions());
        verify(delegate, times(2)).getOptions();
    }
}
