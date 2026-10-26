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

package io.agentscope.core.skill.util;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.MarkdownSkillParser.ParsedMarkdown;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for file system operations related to skills.
 *
 * <p>This class provides reusable operations for loading, listing, saving, and
 * deleting skills on the file system. It is designed to be shared by multiple
 * repositories such as file system and Git based repositories.
 *
 */
public final class SkillFileSystemHelper {

    private static final Logger logger = LoggerFactory.getLogger(SkillFileSystemHelper.class);
    private static final String SKILL_FILE_NAME = "SKILL.md";

    private SkillFileSystemHelper() {}

    /**
     * Loads a skill by name from the given base directory.
     *
     * @param baseDir The base directory containing skill folders
     * @param skillName The skill name to load
     * @param source The source identifier for the created skill
     * @return The loaded AgentSkill
     * @throws IllegalArgumentException if the skill cannot be found or is invalid
     */
    public static AgentSkill loadSkill(Path baseDir, String skillName, String source) {
        Path skillDir =
                findSkillDirectoryByName(baseDir, skillName)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Skill directory does not exist for skill name: "
                                                        + skillName));
        return loadSkillFromDirectory(skillDir, source);
    }

    /**
     * Loads a skill from a specific directory, eagerly reading every non-hidden file in the
     * skill tree into memory. Equivalent to {@link #loadSkillFromDirectory(Path, String, boolean)
     * loadSkillFromDirectory(skillDir, source, true)}.
     */
    public static AgentSkill loadSkillFromDirectory(Path skillDir, String source) {
        return loadSkillFromDirectory(skillDir, source, true);
    }

    /**
     * Loads a skill from a specific directory.
     *
     * <p>The returned skill always carries an absolute {@code originDir} matching {@code skillDir},
     * so callers (prompt provider, load tool) can address files on disk without a second copy.
     *
     * @param skillDir The directory containing SKILL.md
     * @param source The source identifier for the created skill
     * @param includeResources when {@code true}, all non-hidden files under {@code skillDir} are
     *     pre-loaded into the skill's in-memory resource map (legacy behavior). When {@code false},
     *     only SKILL.md is read; callers fall back to disk through {@code originDir} on demand.
     * @return The loaded AgentSkill (with {@code originDir} populated)
     * @throws IllegalArgumentException if SKILL.md is missing or invalid
     */
    public static AgentSkill loadSkillFromDirectory(
            Path skillDir, String source, boolean includeResources) {
        if (!Files.exists(skillDir)) {
            throw new IllegalArgumentException("Skill directory does not exist: " + skillDir);
        }

        if (!Files.isDirectory(skillDir)) {
            throw new IllegalArgumentException("Skill path is not a directory: " + skillDir);
        }

        Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
        if (!Files.exists(skillFile)) {
            throw new IllegalArgumentException(
                    "SKILL.md not found in skill directory: " + skillDir);
        }

        try {
            String skillMdContent = Files.readString(skillFile, StandardCharsets.UTF_8);
            Map<String, String> resources =
                    includeResources ? loadResources(skillDir, skillFile) : null;
            AgentSkill base = SkillUtil.createFrom(skillMdContent, resources, source);
            return base.toBuilder().originDir(skillDir.toAbsolutePath().normalize()).build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load skill from: " + skillDir, e);
        }
    }

    /**
     * 工具结果内联资源的体积上限。超过该值的资源不进对话上下文 (load 工具改回
     * 文件路径并提示用 shell/read_file 处理), 防止大文件 (如 PPT 模板 base64)
     * 一次性撑爆上下文与堆 — 2026-10-22 生产 520MB 驻留事故的护栏。
     */
    public static final long MAX_INLINE_RESOURCE_BYTES = 512 * 1024;

    /** 资源超过 {@link #MAX_INLINE_RESOURCE_BYTES} 时由读取方法抛出, 调用方转提示。 */
    public static final class ResourceTooLargeException extends RuntimeException {
        /** 技能源目录内的相对路径。 */
        public final String relPath;

        /** 文件绝对路径, 供提示信息引用。 */
        public final Path file;

        /** 文件字节数。 */
        public final long sizeBytes;

        public ResourceTooLargeException(String relPath, Path file, long sizeBytes) {
            super("resource too large: " + relPath + " (" + sizeBytes + " bytes)");
            this.relPath = relPath;
            this.file = file;
            this.sizeBytes = sizeBytes;
        }
    }

    /**
     * head-only 加载技能: 瞬时读 SKILL.md 解析 name/description 后立即丢弃正文,
     * 返回仅含元数据的 AgentSkill (正文/资源运行时经 originDir 磁盘现读)。
     *
     * <p>注册路径 (getAllSkills) 用它取代全量加载, 使技能常驻内存成本从
     * "正文+全部资源 (可达 MB 级)" 降到元数据 (~KB 级)。
     *
     * @param skillDir The directory containing SKILL.md
     * @param source The source identifier for the created skill
     * @return head-only AgentSkill, {@code originDir} 指向 skillDir
     */
    public static AgentSkill loadSkillHead(Path skillDir, String source) {
        Path skillFile = requireSkillFile(skillDir);
        try {
            String skillMdContent = Files.readString(skillFile, StandardCharsets.UTF_8);
            ParsedMarkdown parsed = MarkdownSkillParser.parse(skillMdContent);
            Map<String, Object> metadata = parsed.getMetadata();
            if (parsed.getContent() == null || parsed.getContent().isEmpty()) {
                throw new IllegalArgumentException(
                        "The SKILL.md must have content except for the YAML Front Matter.");
            }
            return AgentSkill.headOf(
                    metadata,
                    source,
                    skillDir.toAbsolutePath().normalize(),
                    sha256Hex(skillMdContent));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load skill head from: " + skillDir, e);
        }
    }

    /**
     * head-only 加载 (按名解析目录), 供 getSkill 兜底路径使用, 与
     * {@link #loadSkillHead(Path, String)} 同语义。
     */
    public static AgentSkill loadSkillHead(Path baseDir, String skillName, String source) {
        Path skillDir =
                findSkillDirectoryByName(baseDir, skillName)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Skill directory does not exist for skill name: "
                                                        + skillName));
        return loadSkillHead(skillDir, source);
    }

    /**
     * head-only 批量加载: 目录下每个技能只解析 SKILL.md 元数据, 正文/资源不驻留。
     * 与 {@link #getAllSkills(Path, String)} 对应的注册期瘦身版本。
     */
    public static List<AgentSkill> getAllSkillHeads(Path baseDir, String source) {
        List<AgentSkill> skills = new ArrayList<>();
        try (Stream<Path> subdirs = Files.list(baseDir)) {
            subdirs.filter(Files::isDirectory)
                    .forEach(
                            dir -> {
                                if (hasSkillFile(dir)) {
                                    try {
                                        skills.add(loadSkillHead(dir, source));
                                    } catch (Exception e) {
                                        logger.warn(
                                                "Failed to load skill head from '{}': {}",
                                                dir,
                                                e.getMessage(),
                                                e);
                                    }
                                }
                            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to list skill directories", e);
        }
        return skills;
    }

    /**
     * 磁盘现读技能的 SKILL.md 全文 (frontmatter + 正文原文)。
     *
     * <p>head-only 技能的 load_skill("SKILL.md") 与晋升/写回等需要正文的场景
     * 统一走这里; 读原文而非重组, 保证与 skill_manage patch 的字节一致性。
     *
     * @param originDir 技能源目录
     * @return SKILL.md 原文
     */
    public static String readFullMarkdownFromDir(Path originDir) {
        try {
            return Files.readString(requireSkillFile(originDir), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read SKILL.md from: " + originDir, e);
        }
    }

    /**
     * 磁盘现读技能资源文件 (load_skill_through_path 的按需通道)。
     *
     * <p>文本按 UTF-8 原样返回; 二进制按 {@code base64:} 前缀约定编码, 与预加载
     * 时代的资源 map 语义一致。超过 {@link #MAX_INLINE_RESOURCE_BYTES} 抛
     * {@link ResourceTooLargeException}, 由调用方转为路径提示。
     *
     * @param originDir 技能源目录
     * @param relPath 相对路径 (拒绝绝对路径与 "..")
     * @return 内容; 文件不存在/不可读/路径非法时为 empty
     */
    public static Optional<String> readResourceFromDir(Path originDir, String relPath) {
        if (relPath == null || relPath.isBlank()) {
            return Optional.empty();
        }
        String normalized = relPath.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            return Optional.empty();
        }
        if (SKILL_FILE_NAME.equals(normalized)) {
            return Optional.of(readFullMarkdownFromDir(originDir));
        }
        Path target = originDir.resolve(normalized).normalize();
        if (!target.startsWith(originDir.normalize())) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(target) || !Files.isReadable(target)) {
            return Optional.empty();
        }
        try {
            long size = Files.size(target);
            if (size > MAX_INLINE_RESOURCE_BYTES) {
                throw new ResourceTooLargeException(normalized, target, size);
            }
            String content = Files.readString(target, StandardCharsets.UTF_8);
            return Optional.of(content);
        } catch (MalformedInputException e) {
            try {
                byte[] bytes = Files.readAllBytes(target);
                if (bytes.length > MAX_INLINE_RESOURCE_BYTES) {
                    throw new ResourceTooLargeException(normalized, target, bytes.length);
                }
                return Optional.of("base64:" + Base64.getEncoder().encodeToString(bytes));
            } catch (IOException ex) {
                logger.warn("Failed to read binary resource: {}", target, ex);
                return Optional.empty();
            }
        } catch (IOException e) {
            logger.warn("Failed to read resource: {}", target, e);
            return Optional.empty();
        }
    }

    /**
     * 列出技能目录下可作为资源的相对路径 (不含 SKILL.md 与隐藏文件),
     * 供 load 工具的"未找到→列可选项"与技能集指纹使用。
     */
    public static List<String> listResourceRelPaths(Path skillDir) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(skillDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !p.getFileName().toString().equals(SKILL_FILE_NAME))
                    .forEach(p -> out.add(skillDir.relativize(p).toString().replace('\\', '/')));
        } catch (IOException e) {
            logger.warn("Failed to list resources of: {}", skillDir, e);
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static String sha256Hex(String content) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(d.length * 2);
            for (byte b : d) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Path requireSkillFile(Path skillDir) {
        if (!Files.isDirectory(skillDir)) {
            throw new IllegalArgumentException("Skill directory does not exist: " + skillDir);
        }
        Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
        if (!Files.exists(skillFile)) {
            throw new IllegalArgumentException(
                    "SKILL.md not found in skill directory: " + skillDir);
        }
        return skillFile;
    }

    /**
     * Retrieves all skill names by parsing SKILL.md metadata in each skill folder.
     *
     * @param baseDir The base directory containing skill folders
     * @return A list of skill names sorted alphabetically
     */
    public static List<String> getAllSkillNames(Path baseDir) {
        List<String> skillNames = new ArrayList<>();

        try (Stream<Path> subdirs = Files.list(baseDir)) {
            subdirs.filter(Files::isDirectory)
                    .forEach(
                            dir -> {
                                if (hasSkillFile(dir)) {
                                    readSkillName(dir).ifPresent(skillNames::add);
                                }
                            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to list skill directories", e);
        }

        skillNames.sort(String::compareTo);
        return skillNames;
    }

    /**
     * Retrieves all skills from the base directory with resources pre-loaded into memory.
     * Equivalent to {@link #getAllSkills(Path, String, boolean) getAllSkills(baseDir, source,
     * true)}.
     */
    public static List<AgentSkill> getAllSkills(Path baseDir, String source) {
        return getAllSkills(baseDir, source, true);
    }

    /**
     * Retrieves all skills from the base directory.
     *
     * @param baseDir The base directory containing skill folders
     * @param source The source identifier for created skills
     * @param includeResources when {@code false}, each skill's resource map is left empty and
     *     callers must rely on the skill's {@code originDir} for disk-side reads
     * @return A list of skills
     */
    public static List<AgentSkill> getAllSkills(
            Path baseDir, String source, boolean includeResources) {
        List<AgentSkill> skills = new ArrayList<>();

        try (Stream<Path> subdirs = Files.list(baseDir)) {
            subdirs.filter(Files::isDirectory)
                    .forEach(
                            dir -> {
                                if (hasSkillFile(dir)) {
                                    try {
                                        skills.add(
                                                loadSkillFromDirectory(
                                                        dir, source, includeResources));
                                    } catch (Exception e) {
                                        logger.warn(
                                                "Failed to load skill from '{}': {}",
                                                dir,
                                                e.getMessage(),
                                                e);
                                    }
                                }
                            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to list skill directories", e);
        }

        return skills;
    }

    /**
     * Saves skills to the base directory.
     *
     * @param baseDir The base directory to save skills
     * @param skills The skills to save
     * @param force Whether to overwrite existing skills
     * @return true if all skills were saved, false otherwise
     */
    public static boolean saveSkills(Path baseDir, List<AgentSkill> skills, boolean force) {
        if (skills == null || skills.isEmpty()) {
            return false;
        }

        int size = skills.size();
        int saveCount = 0;
        try {
            for (AgentSkill skill : skills) {
                String skillName = skill.getName();
                Path skillDir = validateAndResolvePath(baseDir, skillName);

                if (Files.exists(skillDir)) {
                    if (!force) {
                        logger.info(
                                "Skill directory already exists and force=false: {}", skillName);
                        continue; // Skip to the next skill if force=false
                    } else {
                        logger.info("Overwriting existing skill directory: {}", skillName);
                        deleteDirectory(skillDir);
                    }
                }

                Files.createDirectories(skillDir);

                String skillMdContent =
                        MarkdownSkillParser.generate(skill.getMetadata(), skill.getSkillContent());

                Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
                Files.writeString(skillFile, skillMdContent, StandardCharsets.UTF_8);

                Map<String, String> resources = skill.getResources();
                for (Map.Entry<String, String> entry : resources.entrySet()) {
                    Path resourceFile = skillDir.resolve(entry.getKey());
                    Files.createDirectories(resourceFile.getParent());
                    String content = entry.getValue();
                    if (content != null && content.startsWith("base64:")) {
                        String base64 = content.substring("base64:".length());
                        byte[] bytes = Base64.getDecoder().decode(base64);
                        Files.write(resourceFile, bytes);
                    } else {
                        Files.writeString(resourceFile, content, StandardCharsets.UTF_8);
                    }
                }

                saveCount++;
                logger.info("Successfully saved skill: {}", skillName);
            }
            boolean allSaved = (size == saveCount);
            if (!allSaved) {
                logger.warn("Not all skills were saved. Saved {} of {}", saveCount, size);
            }

            return allSaved;
        } catch (IOException e) {
            logger.error("Failed to save skills", e);
            throw new RuntimeException("Failed to save skills", e);
        }
    }

    /**
     * Deletes a skill by name based on SKILL.md metadata.
     *
     * @param baseDir The base directory containing skill folders
     * @param skillName The skill name to delete
     * @return true if deleted, false if not found
     */
    public static boolean deleteSkill(Path baseDir, String skillName) {
        Optional<Path> skillDir = findSkillDirectoryByName(baseDir, skillName);
        if (skillDir.isEmpty()) {
            logger.warn("Skill directory does not exist: {}", skillName);
            return false;
        }

        try {
            deleteDirectory(skillDir.get());
            logger.info("Successfully deleted skill: {}", skillName);
            return true;
        } catch (IOException e) {
            logger.error("Failed to delete skill: {}", skillName, e);
            throw new RuntimeException("Failed to delete skill: " + skillName, e);
        }
    }

    /**
     * Checks whether a skill exists by scanning SKILL.md metadata.
     *
     * @param baseDir The base directory containing skill folders
     * @param skillName The skill name to check
     * @return true if a matching skill exists
     */
    public static boolean skillExists(Path baseDir, String skillName) {
        if (skillName == null || skillName.isEmpty()) {
            return false;
        }
        return findSkillDirectoryByName(baseDir, skillName).isPresent();
    }

    /**
     * Validates that a resolved path is within the base directory.
     *
     * @param baseDir The base directory to constrain resolution
     * @param skillName The skill name to resolve
     * @return The validated absolute path
     * @throws IllegalArgumentException if the path escapes the base directory
     */
    public static Path validateAndResolvePath(Path baseDir, String skillName) {
        if (skillName == null || skillName.isEmpty()) {
            throw new IllegalArgumentException("Skill name cannot be null or empty");
        }

        Path absoluteBaseDir = baseDir.toAbsolutePath().normalize();
        Path resolvedPath = absoluteBaseDir.resolve(skillName).normalize();

        if (!resolvedPath.startsWith(absoluteBaseDir)) {
            throw new IllegalArgumentException(
                    "Invalid skill name: path traversal detected. Skill name '"
                            + skillName
                            + "' would escape base directory");
        }

        return resolvedPath;
    }

    /**
     * Checks if a directory contains a SKILL.md file.
     *
     * @param dir The directory to check
     * @return true if SKILL.md exists in the directory
     */
    public static boolean hasSkillFile(Path dir) {
        try (Stream<Path> files = Files.walk(dir, 1)) {
            return files.anyMatch(p -> p.getFileName().toString().equals(SKILL_FILE_NAME));
        } catch (IOException e) {
            logger.warn("Failed to check for SKILL.md in directory: {}", dir, e);
            return false;
        }
    }

    /**
     * Recursively deletes a directory and all its contents.
     *
     * @param directory The directory to delete
     * @throws IOException if deletion fails
     */
    public static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    logger.error("Failed to delete: {}", path, e);
                                    throw new RuntimeException("Failed to delete: " + path, e);
                                }
                            });
        }
    }

    /**
     * Registers a shutdown hook to delete the given directory on JVM exit.
     *
     * @param directory The directory to delete at shutdown
     */
    public static Thread registerTempDirectoryCleanup(Path directory) {
        Thread hook =
                new Thread(
                        () -> {
                            try {
                                deleteDirectory(directory);
                                logger.debug(
                                        "Cleaned up temporary directory on shutdown: {}",
                                        directory);
                            } catch (Exception e) {
                                logger.warn(
                                        "Failed to cleanup temp directory on shutdown: {}",
                                        directory,
                                        e);
                            }
                        });
        Runtime.getRuntime().addShutdownHook(hook);
        return hook;
    }

    private static Map<String, String> loadResources(Path skillDir, Path skillFile) {
        Map<String, String> resources = new HashMap<>();
        // Recursive traversal starting from the root directory
        collectResources(skillDir, skillDir, skillFile, resources);
        return resources;
    }

    private static void collectResources(
            Path currentDir, Path skillDir, Path skillFile, Map<String, String> resources) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(currentDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    if (!entry.getFileName().toString().startsWith(".")) {
                        collectResources(entry, skillDir, skillFile, resources);
                    }
                } else {
                    if (isValidResource(entry, skillFile)) {
                        readAndPutResource(entry, skillDir, resources);
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to open directory: {}", currentDir, e);
        }
    }

    private static void readAndPutResource(
            Path file, Path skillDir, Map<String, String> resources) {
        String relativePath = skillDir.relativize(file).toString().replace('\\', '/');
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            resources.put(relativePath, content);
        } catch (MalformedInputException e) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                String base64 = Base64.getEncoder().encodeToString(bytes);
                resources.put(relativePath, "base64:" + base64);
            } catch (IOException ex) {
                logger.warn("Failed to read binary resource file: {}", file, ex);
            }
        } catch (IOException e) {
            logger.warn("Failed to read resource file: {}", file, e);
        }
    }

    private static boolean isValidResource(Path file, Path skillFile) {
        if (file.equals(skillFile)) {
            return false;
        }

        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return false;
        }

        if (file.getFileName().toString().startsWith(".")) {
            return false;
        }

        try {
            return !Files.isHidden(file);
        } catch (IOException e) {
            logger.warn("Failed to check attributes for file: {}", file, e);
            return true;
        }
    }

    private static Optional<Path> findSkillDirectoryByName(Path baseDir, String skillName) {
        if (skillName == null || skillName.isEmpty()) {
            return Optional.empty();
        }

        try (Stream<Path> subdirs = Files.list(baseDir)) {
            return subdirs.filter(Files::isDirectory)
                    .filter(SkillFileSystemHelper::hasSkillFile)
                    .filter(dir -> skillName.equals(readSkillName(dir).orElse(null)))
                    .findFirst();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list skill directories", e);
        }
    }

    private static Optional<String> readSkillName(Path skillDir) {
        Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
        if (!Files.exists(skillFile)) {
            return Optional.empty();
        }

        try {
            String skillMdContent = Files.readString(skillFile, StandardCharsets.UTF_8);
            ParsedMarkdown parsed = MarkdownSkillParser.parse(skillMdContent);
            Map<String, Object> metadata = parsed.getMetadata();
            Object nameObject = metadata.get("name");
            String name = nameObject != null ? String.valueOf(nameObject) : null;
            if (name == null || name.isEmpty()) {
                logger.warn("Missing skill name in SKILL.md: {}", skillFile);
                return Optional.empty();
            }
            return Optional.of(name);
        } catch (Exception e) {
            logger.warn("Failed to parse SKILL.md: {}", skillFile, e);
            return Optional.empty();
        }
    }
}
