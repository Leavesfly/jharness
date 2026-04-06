package io.leavesfly.jharness;

import io.leavesfly.jharness.commands.handlers.SessionCommandHandler;
import io.leavesfly.jharness.engine.model.ConversationMessage;
import io.leavesfly.jharness.engine.model.MessageRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionCommandsTest {

    @TempDir
    Path tempDir;

    @Test
    void testRewindTurns() {
        List<ConversationMessage> messages = List.of(
                ConversationMessage.userText("Hello"),
                ConversationMessage.assistantText("Hi"),
                ConversationMessage.userText("How are you?"),
                ConversationMessage.assistantText("Fine!")
        );

        List<ConversationMessage> rewound = SessionCommandHandler.rewindTurns(messages, 1);
        assertTrue(rewound.size() < messages.size());
    }

    @Test
    void testRewindTurnsEmpty() {
        List<ConversationMessage> rewound = SessionCommandHandler.rewindTurns(List.of(), 1);
        assertTrue(rewound.isEmpty());
    }

    @Test
    void testCompactMessages() {
        List<ConversationMessage> messages = List.of(
                ConversationMessage.userText("Message 1"),
                ConversationMessage.assistantText("Response 1"),
                ConversationMessage.userText("Message 2"),
                ConversationMessage.assistantText("Response 2"),
                ConversationMessage.userText("Message 3"),
                ConversationMessage.assistantText("Response 3"),
                ConversationMessage.userText("Message 4"),
                ConversationMessage.assistantText("Response 4")
        );

        List<ConversationMessage> compacted = SessionCommandHandler.compactMessages(messages, 2);
        assertTrue(compacted.size() <= 3);
        assertEquals(MessageRole.ASSISTANT, compacted.get(0).getRole());
    }

    @Test
    void testCompactMessagesNoCompactionNeeded() {
        List<ConversationMessage> messages = List.of(
                ConversationMessage.userText("Q1"),
                ConversationMessage.assistantText("A1")
        );

        List<ConversationMessage> compacted = SessionCommandHandler.compactMessages(messages, 6);
        assertEquals(messages.size(), compacted.size());
    }

    @Test
    void testExportSessionMarkdown() throws Exception {
        List<ConversationMessage> messages = List.of(
                ConversationMessage.userText("Hello world"),
                ConversationMessage.assistantText("Hi there!")
        );

        Path outputPath = SessionCommandHandler.exportSessionMarkdown(tempDir, messages);

        assertTrue(Files.exists(outputPath));
        String content = Files.readString(outputPath);
        assertTrue(content.contains("# JHarness Session Transcript"));
        assertTrue(content.contains("User"));
        assertTrue(content.contains("Hello world"));
    }
}
