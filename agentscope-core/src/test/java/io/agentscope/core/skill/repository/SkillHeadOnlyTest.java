package io.agentscope.core.skill.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillFileSystemHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * head-only 技能加载契约回归测试 (2026-10-22 生产 520MB 驻留事故修复):
 *
 * <ul>
 *   <li>lazy 注册只留元数据: 正文/资源不驻留, 带正文摘要供变更检测
 *   <li>load_skill_through_path 通道经 originDir 磁盘现读, 语义与驻留时代一致
 *   <li>超过内联上限的资源抛 ResourceTooLargeException (不进上下文)
 * </ul>
 */
class SkillHeadOnlyTest {

    @TempDir Path baseDir;

    private Path writeSkill(String name, String body, String resourceRel, int resourceSizeBytes)
            throws IOException {
        Path dir = baseDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + name + " desc.\n---\n" + body);
        if (resourceRel != null) {
            Path res = dir.resolve(resourceRel);
            Files.createDirectories(res.getParent());
            byte[] data = new byte[resourceSizeBytes];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) ('a' + (i % 26));
            }
            Files.write(res, data);
        }
        return dir;
    }

    @Test
    @DisplayName("lazy repo 注册只留元数据, 正文/资源经 originDir 现读")
    void lazyRepositoryLoadsHeadOnly() throws IOException {
        writeSkill("big", "# Body", "references/tpl.bin", 1024);
        try (FileSystemSkillRepository repo =
                new FileSystemSkillRepository(baseDir, true, null, true)) {
            List<AgentSkill> skills = repo.getAllSkills();
            assertEquals(1, skills.size());
            AgentSkill s = skills.get(0);
            assertEquals("big", s.getName());
            assertNull(s.getSkillContent(), "head-only: 正文不应驻留");
            assertTrue(s.getResources().isEmpty(), "head-only: 资源不应驻留");
            assertNotNull(s.getContentDigest(), "变更检测需要正文摘要");
            assertNotNull(s.getOriginDir(), "磁盘现读需要 originDir");

            // 现读通道: 正文全文与资源
            assertTrue(
                    SkillFileSystemHelper.readFullMarkdownFromDir(s.getOriginDir().get())
                            .contains("# Body"));
            Optional<String> res =
                    SkillFileSystemHelper.readResourceFromDir(
                            s.getOriginDir().get(), "references/tpl.bin");
            assertTrue(res.isPresent());
            assertTrue(res.get().startsWith("base64:"));
        }
    }

    @Test
    @DisplayName("超过内联上限的资源抛 ResourceTooLargeException")
    void oversizeResourceRejected() throws IOException {
        Path dir = writeSkill("huge", "# Body", "references/huge.bin", 600 * 1024);
        SkillFileSystemHelper.ResourceTooLargeException ex =
                assertThrows(
                        SkillFileSystemHelper.ResourceTooLargeException.class,
                        () ->
                                SkillFileSystemHelper.readResourceFromDir(
                                        dir, "references/huge.bin"));
        assertEquals("references/huge.bin", ex.relPath);
        assertTrue(ex.sizeBytes > SkillFileSystemHelper.MAX_INLINE_RESOURCE_BYTES);
    }

    @Test
    @DisplayName("路径穿越与不存在文件的现读返回 empty")
    void sanitizeAndMissing() throws IOException {
        Path dir = writeSkill("safe", "# Body", null, 0);
        assertEquals(
                Optional.empty(), SkillFileSystemHelper.readResourceFromDir(dir, "../escape.txt"));
        assertEquals(Optional.empty(), SkillFileSystemHelper.readResourceFromDir(dir, "nope.txt"));
    }
}
