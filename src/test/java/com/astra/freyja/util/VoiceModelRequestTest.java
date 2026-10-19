package com.astra.freyja.util;

import com.astra.freyja.common.BizException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VoiceModelRequestTest {
    Map<String,Object> request() {
        return new HashMap<>(Map.of("messages",List.of(Map.of("role","assistant","content","(愤怒)别走！[轻笑]"))));
    }
    @Test void rejectsTransportOverridesAndMalformedMessagesBeforeScheduling() {
        for (var override : List.of(Map.of("voice","external"),Map.of("format","mp3"))) {
            var request=request();request.put("audio",override);
            assertThrows(BizException.class,()->VoiceModelRequest.snapshot(request));
        }
        var request=request();request.put("stream",true);
        assertThrows(BizException.class,()->VoiceModelRequest.snapshot(request));
        assertThrows(BizException.class,()->VoiceModelRequest.snapshot(Map.of("messages",List.of(Map.of("role","assistant","content",123)))));
        assertThrows(BizException.class,()->VoiceModelRequest.snapshot(Map.of("messages",List.of())));
    }
    @Test void preservesOptionalDesignPreviewAndDoesNotInventSpokenText() {
        var request=Map.<String,Object>of("messages",List.of(Map.of("role","user","content","低沉的成年男声")),
                "audio",Map.of("optimize_text_preview",true));
        assertEquals(request,VoiceModelRequest.read(VoiceModelRequest.snapshot(request)));
        assertEquals("",VoiceModelRequest.text(request));
        assertThrows(BizException.class,()->VoiceModelRequest.read(null));
    }
}
