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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Subagent factories must propagate the parent's permission context. Regression test for the
 * production deadlock where a BYPASS-mode parent (embedding platform default) spawned children
 * with a trivial DEFAULT context: non-readonly MCP tools' self-check ({@code ask}) then suspended
 * the child's turn pending a confirmation nobody could answer, so any subagent touching MCP tools
 * died after one turn while the main agent ran the same tools freely under BYPASS.
 */
class SubagentPermissionPropagationTest {

    @TempDir Path workspace;

    private static Model stubModel() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text("ok").build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }

    private static SubagentDeclaration decl(String name) {
        // mainWorkspace 锚点只在 SHARED 模式被工厂使用, 测试传固定目录即可
        return AgentSpecLoader.parse(
                "---\ndescription: test subagent for " + name + "\n---\nYou are " + name + ".",
                name,
                Path.of("/tmp/subagent-perm-test-workspace"));
    }

    private HarnessAgent.Builder parentBuilder(
            PermissionContextState perm, SubagentDeclaration... decls) {
        HarnessAgent.Builder b =
                HarnessAgent.builder().name("parent").model(stubModel()).workspace(workspace);
        if (perm != null) {
            b.permissionContext(perm);
        }
        if (decls.length > 0) {
            b.subagents(List.of(decls));
        }
        return b;
    }

    @Test
    void declaredSubagentInheritsParentBypassMode() throws IOException {
        Files.createDirectories(workspace);
        List<SubagentEntry> entries =
                HarnessAgentBuilderSupport.buildStaticSubagentEntries(
                        parentBuilder(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.BYPASS)
                                        .build(),
                                decl("tester")),
                        workspace,
                        null);
        SubagentEntry entry =
                entries.stream().filter(e -> e.name().equals("tester")).findFirst().orElseThrow();
        Agent child = entry.factory().create(RuntimeContext.empty());
        PermissionContextState childCtx =
                ((HarnessAgent) child).getDelegate().getPermissionContext();
        assertEquals(PermissionMode.BYPASS, childCtx.getMode(), "child must run in parent's mode");
    }

    @Test
    void generalPurposeSubagentInheritsParentBypassMode() throws IOException {
        Files.createDirectories(workspace);
        List<SubagentEntry> entries =
                HarnessAgentBuilderSupport.buildStaticSubagentEntries(
                        parentBuilder(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.BYPASS)
                                        .build()),
                        workspace,
                        null);
        SubagentEntry entry =
                entries.stream()
                        .filter(e -> e.name().equals("general-purpose"))
                        .findFirst()
                        .orElseThrow();
        Agent child = entry.factory().create(RuntimeContext.empty());
        PermissionContextState childCtx =
                ((HarnessAgent) child).getDelegate().getPermissionContext();
        assertEquals(PermissionMode.BYPASS, childCtx.getMode(), "child must run in parent's mode");
    }

    @Test
    void unconfiguredParentKeepsLegacyChildDefault() throws IOException {
        // No permissionContext on the parent → children stay unconfigured (null baseline,
        // legacy trivial path) — upstream behavior preserved when the app sets nothing.
        Files.createDirectories(workspace);
        List<SubagentEntry> entries =
                HarnessAgentBuilderSupport.buildStaticSubagentEntries(
                        parentBuilder(null, decl("legacy")), workspace, null);
        SubagentEntry entry =
                entries.stream().filter(e -> e.name().equals("legacy")).findFirst().orElseThrow();
        Agent child = entry.factory().create(RuntimeContext.empty());
        PermissionContextState childCtx =
                ((HarnessAgent) child).getDelegate().getPermissionContext();
        assertTrue(
                childCtx == null || childCtx.getMode() == PermissionMode.DEFAULT,
                "no propagation when parent unconfigured, got: " + childCtx);
    }

    @Test
    void propagationAlsoHappensViaFullEntryBuild() throws IOException {
        // buildSubagentEntries (the non-static variant used by SubagentsMiddleware wiring)
        // shares the same factory methods — one smoke assertion guards against divergence.
        Files.createDirectories(workspace);
        List<SubagentEntry> entries =
                HarnessAgentBuilderSupport.buildSubagentEntries(
                        parentBuilder(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.BYPASS)
                                        .build(),
                                decl("smoke")),
                        workspace,
                        null);
        SubagentEntry entry =
                entries.stream().filter(e -> e.name().equals("smoke")).findFirst().orElseThrow();
        Agent child = entry.factory().create(RuntimeContext.empty());
        assertEquals(
                PermissionMode.BYPASS,
                ((HarnessAgent) child).getDelegate().getPermissionContext().getMode());
    }
}
