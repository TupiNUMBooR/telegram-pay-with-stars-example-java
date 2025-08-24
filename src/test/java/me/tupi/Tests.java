package me.tupi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

@SpringBootTest
class Tests {

    @Autowired
    Bot bot;

    @Value("${telegram.bot.username}")
    Long debugChatId;

    @Test
    void contextLoads() {}

    @Test
    void sendMessage() {
        bot.sendText(debugChatId, "test at %s".formatted(Instant.now()));
    }
}
