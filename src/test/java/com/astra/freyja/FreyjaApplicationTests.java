package com.astra.freyja;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties="freyja.video-queue.enabled=false")
class FreyjaApplicationTests {

    @Test
    void contextLoads() {
    }

}
