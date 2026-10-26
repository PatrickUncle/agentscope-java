/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Child event-source path segment must stay unique per spawned instance so that two concurrent
 * spawns of the SAME agent type (same-turn parallel fan-out) do not collide downstream, while
 * remaining stable for one instance across spawn → send → resume.
 */
class AgentSpawnToolSpawnPathSegmentTest {

    @Test
    void sameAgentIdDifferentSpawnKeys_yieldDistinctSegments() {
        String keyA = "agent:investigator:" + UUID.randomUUID();
        String keyB = "agent:investigator:" + UUID.randomUUID();

        String segA = AgentSpawnTool.spawnPathSegment("investigator", keyA);
        String segB = AgentSpawnTool.spawnPathSegment("investigator", keyB);

        assertNotEquals(segA, segB, "parallel spawns of one type must not share a source path");
        assertTrue(segA.startsWith("investigator#"), "segment keeps agentId prefix: " + segA);
        assertTrue(segB.startsWith("investigator#"), "segment keeps agentId prefix: " + segB);
    }

    @Test
    void sameSpawnKey_isStableAcrossCalls() {
        String key = "agent:writer:" + UUID.randomUUID();
        assertEquals(
                AgentSpawnTool.spawnPathSegment("writer", key),
                AgentSpawnTool.spawnPathSegment("writer", key),
                "one spawned instance must map to one path for its whole lifecycle");
    }

    @Test
    void persistentDeterministicKey_usesHashTail() {
        String key = "agent:reviewer:abc123def456";
        assertEquals("reviewer#abc123def456", AgentSpawnTool.spawnPathSegment("reviewer", key));
    }

    @Test
    void keyWithoutTail_fallsBackToPlainAgentId() {
        assertEquals("plain", AgentSpawnTool.spawnPathSegment("plain", null));
        assertEquals("plain", AgentSpawnTool.spawnPathSegment("plain", "agent:plain:"));
        assertEquals("plain", AgentSpawnTool.spawnPathSegment("plain", "no-colon"));
    }

    @Test
    void segmentStillContainsAgentId_forConsumersMatchingByChildId() {
        String seg = AgentSpawnTool.spawnPathSegment("data-analyst", "agent:data-analyst:xyz");
        assertTrue(seg.contains("data-analyst"));
    }
}
