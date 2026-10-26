/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The injected {@code ## Subagents} guidance must match the tool's actual execution mode: the
 * legacy template teaches fire-and-forget/task tools (async mode), the sync template (rendered
 * under {@code AgentSpawnTool.CTX_FORCE_SYNC}) must not advertise machinery that cannot trigger
 * and must teach same-turn parallel fan-out and timeout-resume instead.
 */
class SubagentsMiddlewareSyncGuidanceTest {

    private static List<SubagentEntry> oneEntry() {
        return List.of(new SubagentEntry("investigator", "read-only investigator", null));
    }

    @Test
    void legacyTemplate_stillTeachesAsyncDispatch() {
        String section = SubagentsMiddleware.renderSubagentSection(oneEntry(), false);
        assertTrue(section.contains("agent_spawn"), "tool names present");
        assertTrue(section.contains("fire-and-forget"), "async guidance present in legacy mode");
        assertTrue(section.contains("wait_async_results"));
    }

    @Test
    void syncTemplate_omitsAsyncMachinery() {
        String section = SubagentsMiddleware.renderSubagentSection(oneEntry(), false, true);
        assertFalse(
                section.contains("fire-and-forget"),
                "sync guidance must not teach timeout_seconds=0 dispatch");
        assertFalse(section.contains("wait_async_results"), "no async task tools in sync mode");
        assertFalse(section.contains("task_output"), "no async task tools in sync mode");
        assertFalse(
                section.contains("timeout_promoted"),
                "timeout promotion cannot trigger under force-sync");
    }

    @Test
    void syncTemplate_teachesParallelFanOutAndResume() {
        String section = SubagentsMiddleware.renderSubagentSection(oneEntry(), false, true);
        assertTrue(
                section.contains("Synchronous execution model"),
                "sync execution model section present");
        assertTrue(section.contains("SAME"), "same-turn fan-out is the parallelism discipline");
        assertTrue(
                section.contains("CONCURRENTLY"), "concurrent same-turn execution must be stated");
        assertTrue(section.contains("status: timeout"), "timeout semantics documented");
        assertTrue(
                section.contains("do NOT re-spawn"), "resume via agent_send instead of re-spawn");
        assertTrue(section.contains("agent_spawn"), "tool names present");
        assertTrue(section.contains("investigator"), "available agent ids rendered");
    }

    @Test
    void syncTemplate_appliesToSessionModeToolNamesToo() {
        String section = SubagentsMiddleware.renderSubagentSection(oneEntry(), true, true);
        assertTrue(section.contains("sessions_spawn"));
        assertFalse(section.contains("fire-and-forget"));
    }
}
