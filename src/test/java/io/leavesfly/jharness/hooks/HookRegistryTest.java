package io.leavesfly.jharness.hooks;

import io.leavesfly.jharness.agent.hooks.HookEvent;
import io.leavesfly.jharness.agent.hooks.HookRegistry;
import io.leavesfly.jharness.agent.hooks.schemas.HookDefinition.AgentHookDefinition;
import io.leavesfly.jharness.agent.hooks.schemas.HookDefinition.CommandHookDefinition;
import io.leavesfly.jharness.agent.hooks.schemas.HookDefinition.HttpHookDefinition;
import io.leavesfly.jharness.agent.hooks.schemas.HookDefinition.PromptHookDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HookRegistryTest {

    @Test
    void testRegisterAndRetrieveHooks() {
        HookRegistry registry = new HookRegistry();

        CommandHookDefinition hook = new CommandHookDefinition();
        hook.setCommand("echo test");
        hook.setTimeoutSeconds(5);

        registry.register(HookEvent.PRE_TOOL_USE, hook);
        assertEquals(1, registry.size());

        var hooks = registry.get(HookEvent.PRE_TOOL_USE);
        assertEquals(1, hooks.size());
    }

    @Test
    void testMultipleHooksForSameEvent() {
        HookRegistry registry = new HookRegistry();

        CommandHookDefinition hook1 = new CommandHookDefinition();
        hook1.setCommand("echo first");
        hook1.setTimeoutSeconds(5);

        CommandHookDefinition hook2 = new CommandHookDefinition();
        hook2.setCommand("echo second");
        hook2.setTimeoutSeconds(5);

        registry.register(HookEvent.PRE_TOOL_USE, hook1);
        registry.register(HookEvent.PRE_TOOL_USE, hook2);

        var hooks = registry.get(HookEvent.PRE_TOOL_USE);
        assertEquals(2, hooks.size());
    }

    @Test
    void testGetSummary() {
        HookRegistry registry = new HookRegistry();

        CommandHookDefinition hook = new CommandHookDefinition();
        hook.setCommand("test");
        hook.setTimeoutSeconds(5);
        registry.register(HookEvent.PRE_TOOL_USE, hook);

        String summary = registry.summary();
        assertNotNull(summary);
        assertTrue(summary.contains("PRE_TOOL_USE"));
    }

    @Test
    void testEmptyRegistry() {
        HookRegistry registry = new HookRegistry();
        assertEquals(0, registry.size());
        assertTrue(registry.get(HookEvent.PRE_TOOL_USE).isEmpty());
    }
}

class HookDefinitionTest {

    @Test
    void testCommandHookDefinition() {
        CommandHookDefinition hook = new CommandHookDefinition();
        hook.setCommand("echo test");
        hook.setTimeoutSeconds(10);
        hook.setBlockOnFailure(false);

        assertEquals("echo test", hook.getCommand());
        assertEquals(10, hook.getTimeoutSeconds());
        assertFalse(hook.isBlockOnFailure());
    }

    @Test
    void testHttpHookDefinition() {
        HttpHookDefinition hook = new HttpHookDefinition();
        hook.setUrl("http://localhost:8080/hook");
        hook.setTimeoutSeconds(5);
        hook.setBlockOnFailure(true);

        assertEquals("http://localhost:8080/hook", hook.getUrl());
        assertEquals(5, hook.getTimeoutSeconds());
        assertTrue(hook.isBlockOnFailure());
    }

    @Test
    void testPromptHookDefinition() {
        PromptHookDefinition hook = new PromptHookDefinition();
        hook.setPrompt("Analyze this code");
        hook.setModel("test-model");
        hook.setTimeoutSeconds(30);

        assertEquals("Analyze this code", hook.getPrompt());
        assertEquals("test-model", hook.getModel());
    }

    @Test
    void testAgentHookDefinition() {
        AgentHookDefinition hook = new AgentHookDefinition();
        hook.setPrompt("Run tests");
        hook.setModel("test-model");
        hook.setTimeoutSeconds(60);

        assertEquals("Run tests", hook.getPrompt());
    }
}
