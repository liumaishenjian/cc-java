import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 从功能矩阵与人工维护的 Stage 状态生成静态项目进度看板。
 *
 * <p>使用 Java 源文件启动模式运行，不依赖 Maven 插件或第三方库：</p>
 *
 * <pre>
 * java scripts/ProgressDashboard.java
 * java scripts/ProgressDashboard.java --check
 * </pre>
 *
 * <p>功能矩阵是 Capability 和 Stage 路线的权威来源；
 * {@code progress-state.properties} 保存当前 Stage、Gate、阻塞项、最近验证以及
 * 对输入摘要的人工确认。HTML 是派生产物，不允许手工修改。</p>
 */
public final class ProgressDashboard {

    private static final String MATRIX_PATH = "docs/feature-parity-matrix.md";
    private static final String STATE_PATH = "docs/progress-state.properties";
    private static final String OUTPUT_PATH = "docs/progress.html";
    private static final String CAPABILITY_HEADER =
            "| ID | 参考能力 | Java 重实现目标 | 当前 | Stage | 参考 |";
    private static final Pattern CAPABILITY_ID = Pattern.compile("[A-Z][A-Z0-9]*-\\d+");
    private static final Pattern LEVEL = Pattern.compile("L([0-4])");
    private static final Pattern LEVEL_SNAPSHOT =
            Pattern.compile("(\\d+)\\s*项为\\s*(L[0-4])");
    private static final Pattern PERCENTAGE = Pattern.compile("(\\d+(?:\\.\\d+)?)%");
    private static final Pattern MARKDOWN_LINK =
            Pattern.compile("\\[([^]]+)]\\([^)]+\\)");
    private static final List<String> GATES =
            List.of("G0", "G1", "G2", "G3", "G4", "G5", "G6");
    private static final List<String> STAGE_IDS =
            List.of(
                    "S00", "S01", "S02", "S03", "S04", "S05", "S06", "S07",
                    "S08", "S09", "S10", "S11", "S12", "S13", "S14", "S15");
    private static final Set<String> GATE_STATUS_VALUES =
            Set.of("PASSED", "OPEN", "BLOCKED");
    private static final Set<String> STAGE_STATUS_VALUES =
            Set.of("ACCEPTED", "IN_PROGRESS", "BLOCKED", "PLANNED");
    private static final Set<String> EXIT_VALUES =
            Set.of("OPEN", "ACCEPTED", "BLOCKED");

    private ProgressDashboard() {
    }

    public static void main(String[] args) throws Exception {
        boolean check = parseArguments(args);
        Path root = findRepositoryRoot(Path.of("").toAbsolutePath().normalize());
        Path matrixPath = root.resolve(MATRIX_PATH);
        Path statePath = root.resolve(STATE_PATH);
        Path outputPath = root.resolve(OUTPUT_PATH);

        String matrix = Files.readString(matrixPath, StandardCharsets.UTF_8);
        Properties state = loadProperties(statePath);
        String matrixDigest = shortSha256(normalizeLines(matrix));
        String codeDigest = repositoryInputDigest(root);
        validateExpectedDigest(state, "inputs.matrix.digest", matrixDigest);
        validateExpectedDigest(state, "inputs.code.digest", codeDigest);
        DashboardData data = parse(matrix, state);
        String html = render(data, matrix, statePath, root);

        if (check) {
            verifyFresh(outputPath, html);
            System.out.println("Progress dashboard is up to date: " + outputPath);
            return;
        }

        Files.writeString(outputPath, html, StandardCharsets.UTF_8);
        System.out.println("Generated progress dashboard: " + outputPath);
    }

    private static boolean parseArguments(String[] args) {
        if (args.length == 0) {
            return false;
        }
        if (args.length == 1 && "--check".equals(args[0])) {
            return true;
        }
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            System.out.println("Usage: java scripts/ProgressDashboard.java [--check]");
            System.exit(0);
        }
        throw new IllegalArgumentException(
                "Unknown arguments. Usage: java scripts/ProgressDashboard.java [--check]");
    }

    private static Path findRepositoryRoot(Path start) {
        Path cursor = start;
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve(MATRIX_PATH))
                    && Files.isRegularFile(cursor.resolve("AGENTS.md"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Cannot locate cc-java repository root from " + start);
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static DashboardData parse(String matrix, Properties state) {
        List<String> lines = matrix.lines().toList();
        List<StageRow> stages = parseStages(lines);
        List<String> actualStageIds = stages.stream().map(StageRow::id).toList();
        if (!STAGE_IDS.equals(actualStageIds)) {
            throw new IllegalStateException(
                    "Stage table must contain each Stage from S00 through S15 exactly once. Found: "
                            + actualStageIds);
        }
        Set<String> validStages = stages.stream()
                .map(StageRow::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<CapabilityRow> capabilities = parseCapabilities(lines, validStages);
        if (capabilities.isEmpty()) {
            throw new IllegalStateException("No capability rows found in feature matrix");
        }

        String currentStage = required(state, "current.stage");
        String currentStatus = validatedStageStatus(
                required(state, "current.stage.status"),
                "current.stage.status");
        String currentExit = validatedExit(required(state, "current.stage.exit"));
        StageRow current = stages.stream()
                .filter(stage -> stage.id().equals(currentStage))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Current Stage does not exist in matrix: " + currentStage));
        String matrixCurrentStage = readSnapshotValue(lines, "当前阶段");
        if (!matrixCurrentStage.startsWith(currentStage)) {
            throw new IllegalStateException(
                    "progress-state current.stage="
                            + currentStage
                            + " conflicts with matrix snapshot: "
                            + matrixCurrentStage);
        }

        Map<String, Integer> levels = new LinkedHashMap<>();
        for (int level = 0; level <= 4; level++) {
            levels.put("L" + level, 0);
        }
        int score = 0;
        for (CapabilityRow capability : capabilities) {
            levels.compute(capability.level(), (ignored, count) -> count + 1);
            score += Math.min(3, Integer.parseInt(capability.level().substring(1)));
        }
        double coverage = score * 100.0 / (capabilities.size() * 3.0);
        validateMatrixSnapshot(lines, capabilities.size(), levels, coverage);

        Map<String, Integer> stageCapabilityCounts = new HashMap<>();
        for (StageRow stage : stages) {
            int count = 0;
            for (CapabilityRow capability : capabilities) {
                if (containsStage(capability.stages(), stage.id())) {
                    count++;
                }
            }
            stageCapabilityCounts.put(stage.id(), count);
        }

        List<GateRow> gates = new ArrayList<>();
        for (String gate : GATES) {
            gates.add(new GateRow(
                    gate,
                    validatedGateStatus(
                            required(state, "gate." + gate + ".status"),
                            "gate." + gate + ".status"),
                    required(state, "gate." + gate + ".summary")));
        }

        int blockerCount = Integer.parseInt(required(state, "blocker.count"));
        List<String> blockers = new ArrayList<>();
        for (int index = 1; index <= blockerCount; index++) {
            blockers.add(required(state, "blocker." + index));
        }

        return new DashboardData(
                current,
                currentStatus,
                currentExit,
                required(state, "current.stage.summary"),
                required(state, "current.next"),
                required(state, "last.updated"),
                required(state, "last.change"),
                required(state, "evidence.commit"),
                required(state, "evidence.classification"),
                required(state, "evidence.summary"),
                required(state, "evidence.command"),
                stages,
                capabilities,
                levels,
                coverage,
                stageCapabilityCounts,
                gates,
                blockers,
                state);
    }

    private static List<StageRow> parseStages(List<String> lines) {
        Map<String, StageRow> unique = new LinkedHashMap<>();
        boolean inTable = false;
        int tableCount = 0;
        for (String line : lines) {
            if (line.equals("| Stage | 主题 | 核心交付 |")) {
                if (inTable || tableCount > 0) {
                    throw new IllegalStateException("Stage table header must appear exactly once");
                }
                inTable = true;
                tableCount++;
                continue;
            }
            if (!inTable) {
                continue;
            }
            if (!line.startsWith("|")) {
                inTable = false;
                continue;
            }
            if (!line.endsWith("|")) {
                throw new IllegalStateException(
                        "Stage table row must end with '|': " + line);
            }
            List<String> cells = splitTableRow(line);
            if (line.startsWith("| ---")) {
                if (cells.size() != 3) {
                    throw new IllegalStateException(
                            "Stage table separator must contain 3 columns: " + line);
                }
                continue;
            }
            if (cells.size() != 3) {
                throw new IllegalStateException(
                        "Stage table row must contain 3 columns: " + line);
            }
            if (!cells.get(0).matches("S\\d{2}")) {
                throw new IllegalStateException(
                        "Invalid Stage ID in Stage table: " + cells.get(0));
            }
            StageRow row = new StageRow(
                    cells.get(0),
                    plainMarkdown(cells.get(1)),
                    plainMarkdown(cells.get(2)));
            if (unique.putIfAbsent(row.id(), row) != null) {
                throw new IllegalStateException("Duplicate Stage ID: " + row.id());
            }
        }
        if (tableCount == 0) {
            throw new IllegalStateException("Stage table was not found");
        }
        List<StageRow> stages = new ArrayList<>(unique.values());
        stages.sort(Comparator.comparing(StageRow::id));
        return List.copyOf(stages);
    }

    private static List<CapabilityRow> parseCapabilities(
            List<String> lines,
            Set<String> validStages) {
        List<CapabilityRow> capabilities = new ArrayList<>();
        Map<String, CapabilityRow> unique = new LinkedHashMap<>();
        boolean inCapabilityTable = false;
        int tableCount = 0;
        for (String line : lines) {
            if (CAPABILITY_HEADER.equals(line)) {
                inCapabilityTable = true;
                tableCount++;
                continue;
            }
            if (!inCapabilityTable) {
                continue;
            }
            if (!line.startsWith("|")) {
                inCapabilityTable = false;
                continue;
            }
            if (!line.endsWith("|")) {
                throw new IllegalStateException(
                        "Capability table row must end with '|': " + line);
            }
            List<String> cells = splitTableRow(line);
            if (line.startsWith("| ---")) {
                if (cells.size() != 6) {
                    throw new IllegalStateException(
                            "Capability table separator must contain 6 columns: " + line);
                }
                continue;
            }
            if (cells.size() != 6) {
                throw new IllegalStateException(
                        "Capability row must contain 6 columns: " + line);
            }
            if (!CAPABILITY_ID.matcher(cells.get(0)).matches()) {
                throw new IllegalStateException(
                        "Invalid Capability ID in capability table: " + cells.get(0));
            }
            if (!LEVEL.matcher(cells.get(3)).matches()) {
                throw new IllegalStateException(
                        "Invalid Capability Level for " + cells.get(0) + ": " + cells.get(3));
            }
            String stageCell = plainMarkdown(cells.get(4));
            for (String stage : stageCell.split("/")) {
                if (!validStages.contains(stage)) {
                    throw new IllegalStateException(
                            "Unknown Stage " + stage + " for Capability " + cells.get(0));
                }
            }
            CapabilityRow row = new CapabilityRow(
                    cells.get(0),
                    plainMarkdown(cells.get(1)),
                    plainMarkdown(cells.get(2)),
                    cells.get(3),
                    stageCell,
                    plainMarkdown(cells.get(5)));
            if (unique.putIfAbsent(row.id(), row) != null) {
                throw new IllegalStateException("Duplicate Capability ID: " + row.id());
            }
        }
        if (tableCount == 0) {
            throw new IllegalStateException(
                    "No capability table with the canonical header was found");
        }
        capabilities.addAll(unique.values());
        return List.copyOf(capabilities);
    }

    private static void validateMatrixSnapshot(
            List<String> lines,
            int capabilityCount,
            Map<String, Integer> levels,
            double coverage) {
        String countValue = readSnapshotValue(lines, "纳入追踪的 Capability ID");
        Matcher countMatcher = Pattern.compile("(\\d+)").matcher(countValue);
        if (!countMatcher.find() || Integer.parseInt(countMatcher.group(1)) != capabilityCount) {
            throw new IllegalStateException(
                    "Matrix snapshot capability count conflicts with parsed rows: "
                            + countValue
                            + " vs "
                            + capabilityCount);
        }

        String levelValue = readSnapshotValue(lines, "当前等级");
        Matcher levelMatcher = LEVEL_SNAPSHOT.matcher(levelValue);
        int mentioned = 0;
        int total = 0;
        while (levelMatcher.find()) {
            mentioned++;
            int expected = Integer.parseInt(levelMatcher.group(1));
            String level = levelMatcher.group(2);
            int actual = levels.get(level);
            if (expected != actual) {
                throw new IllegalStateException(
                        "Matrix snapshot " + level + " count is " + expected + ", parsed " + actual);
            }
            total += expected;
        }
        if (mentioned == 0 || total != capabilityCount) {
            throw new IllegalStateException(
                    "Matrix snapshot level counts do not cover all capabilities: " + levelValue);
        }

        String coverageValue = readSnapshotValue(lines, "当前能力覆盖");
        Matcher coverageMatcher = PERCENTAGE.matcher(coverageValue);
        double roundedCoverage = Math.round(coverage * 100.0) / 100.0;
        if (!coverageMatcher.find()
                || Math.abs(Double.parseDouble(coverageMatcher.group(1)) - roundedCoverage) > 0.0001) {
            throw new IllegalStateException(
                    "Matrix snapshot coverage conflicts with calculated coverage: "
                            + coverageValue
                            + " vs "
                            + roundedCoverage
                            + "%");
        }
    }

    private static List<String> splitTableRow(String line) {
        String content = line.substring(1, line.length() - 1);
        String[] raw = content.split("(?<!\\\\)\\|", -1);
        List<String> cells = new ArrayList<>(raw.length);
        for (String cell : raw) {
            cells.add(cell.trim().replace("\\|", "|"));
        }
        return cells;
    }

    private static String readSnapshotValue(List<String> lines, String metric) {
        for (String line : lines) {
            if (!line.startsWith("|")) {
                continue;
            }
            List<String> cells = splitTableRow(line);
            if (cells.size() == 2 && metric.equals(cells.get(0))) {
                return plainMarkdown(cells.get(1));
            }
        }
        throw new IllegalStateException("Missing matrix snapshot metric: " + metric);
    }

    private static boolean containsStage(String stages, String stageId) {
        return Pattern.compile("(^|[/,\\s])" + Pattern.quote(stageId) + "($|[/,\\s])")
                .matcher(stages)
                .find();
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing progress property: " + key);
        }
        return value.trim();
    }

    private static void validateExpectedDigest(
            Properties state,
            String key,
            String actual) {
        String expected = required(state, key);
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    key
                            + " is stale. Update progress-state.properties after reviewing the change. "
                            + "Expected current digest: "
                            + actual);
        }
    }

    private static String validatedGateStatus(String status, String key) {
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!GATE_STATUS_VALUES.contains(normalized)) {
            throw new IllegalStateException(
                    "Invalid Gate status for " + key + ": " + status);
        }
        return normalized;
    }

    private static String validatedStageStatus(String status, String key) {
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!STAGE_STATUS_VALUES.contains(normalized)) {
            throw new IllegalStateException(
                    "Invalid Stage status for " + key + ": " + status);
        }
        return normalized;
    }

    private static String validatedExit(String exit) {
        String normalized = exit.trim().toUpperCase(Locale.ROOT);
        if (!EXIT_VALUES.contains(normalized)) {
            throw new IllegalStateException("Invalid current.stage.exit: " + exit);
        }
        return normalized;
    }

    private static String render(
            DashboardData data,
            String matrix,
            Path statePath,
            Path repositoryRoot) throws IOException, NoSuchAlgorithmException {
        String stageRows = renderStageRows(data);
        String capabilityRows = renderCapabilityRows(data.capabilities());
        String gateRows = renderGateRows(data.gates());
        String blockerRows = renderBlockers(data.blockers());
        String levelOptions = renderLevelOptions(data.levels());
        String stageOptions = renderStageOptions(data.stages());
        String matrixDigest = shortSha256(normalizeLines(matrix));
        String stateDigest = shortSha256(
                normalizeLines(Files.readString(statePath, StandardCharsets.UTF_8)));
        String codeDigest = repositoryInputDigest(repositoryRoot);

        String template = """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <meta name="color-scheme" content="dark">
                  <title>cc-java · 项目进度看板</title>
                  <style>
                    :root {
                      --bg: #07111f;
                      --panel: rgba(15, 31, 50, .88);
                      --panel-strong: #10243a;
                      --line: rgba(148, 184, 214, .18);
                      --text: #eff7ff;
                      --muted: #91a9be;
                      --cyan: #54d6ff;
                      --green: #57e3a1;
                      --amber: #ffcb6b;
                      --red: #ff7b8b;
                      --blue: #7197ff;
                      --shadow: 0 24px 80px rgba(0, 0, 0, .32);
                    }
                    * { box-sizing: border-box; }
                    html { scroll-behavior: smooth; }
                    body {
                      margin: 0;
                      min-width: 320px;
                      color: var(--text);
                      background:
                        radial-gradient(circle at 12% 0%, rgba(84, 214, 255, .16), transparent 32rem),
                        radial-gradient(circle at 88% 12%, rgba(113, 151, 255, .15), transparent 30rem),
                        linear-gradient(180deg, #081624 0%, var(--bg) 55%);
                      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont,
                        "Segoe UI", "Microsoft YaHei", sans-serif;
                    }
                    a { color: var(--cyan); }
                    button, input, select { font: inherit; }
                    .shell { width: min(1480px, calc(100% - 32px)); margin: 0 auto; padding: 32px 0 64px; }
                    .hero {
                      position: relative;
                      overflow: hidden;
                      padding: 34px;
                      border: 1px solid var(--line);
                      border-radius: 26px;
                      background: linear-gradient(135deg, rgba(18, 45, 70, .96), rgba(11, 26, 43, .92));
                      box-shadow: var(--shadow);
                    }
                    .hero::after {
                      content: "";
                      position: absolute;
                      width: 300px;
                      height: 300px;
                      right: -90px;
                      top: -130px;
                      border-radius: 50%;
                      border: 46px solid rgba(84, 214, 255, .08);
                    }
                    .eyebrow {
                      margin: 0 0 12px;
                      color: var(--cyan);
                      font-size: 12px;
                      font-weight: 800;
                      letter-spacing: .18em;
                      text-transform: uppercase;
                    }
                    h1 { margin: 0; max-width: 850px; font-size: clamp(32px, 5vw, 64px); line-height: 1.02; }
                    .hero-copy { max-width: 900px; margin: 18px 0 0; color: #bfd0df; font-size: 17px; line-height: 1.75; }
                    .hero-meta { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 24px; }
                    .chip {
                      display: inline-flex;
                      align-items: center;
                      gap: 8px;
                      min-height: 34px;
                      padding: 7px 12px;
                      border: 1px solid var(--line);
                      border-radius: 999px;
                      color: #c9d8e4;
                      background: rgba(5, 15, 25, .42);
                      font-size: 13px;
                    }
                    .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--amber); box-shadow: 0 0 18px currentColor; }
                    .summary-grid {
                      display: grid;
                      grid-template-columns: repeat(4, minmax(0, 1fr));
                      gap: 14px;
                      margin: 18px 0;
                    }
                    .metric, .panel {
                      border: 1px solid var(--line);
                      background: var(--panel);
                      box-shadow: 0 12px 40px rgba(0, 0, 0, .18);
                    }
                    .metric { min-height: 150px; padding: 22px; border-radius: 20px; }
                    .metric-label { margin: 0 0 16px; color: var(--muted); font-size: 12px; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; }
                    .metric-value { margin: 0; font-size: clamp(28px, 4vw, 46px); font-weight: 800; line-height: 1; }
                    .metric-note { margin: 12px 0 0; color: #9fb3c5; font-size: 13px; line-height: 1.55; }
                    .progress-track { height: 9px; margin-top: 18px; overflow: hidden; border-radius: 999px; background: rgba(255,255,255,.08); }
                    .progress-bar { height: 100%; border-radius: inherit; background: linear-gradient(90deg, var(--cyan), var(--green)); box-shadow: 0 0 18px rgba(84,214,255,.48); }
                    .panel { margin-top: 18px; padding: 24px; border-radius: 22px; }
                    .panel-head { display: flex; align-items: end; justify-content: space-between; gap: 18px; margin-bottom: 18px; }
                    .panel h2 { margin: 0; font-size: 22px; }
                    .panel-desc { margin: 7px 0 0; color: var(--muted); font-size: 14px; line-height: 1.6; }
                    .callout {
                      display: grid;
                      grid-template-columns: 170px 1fr;
                      gap: 18px;
                      align-items: start;
                      border-left: 3px solid var(--amber);
                    }
                    .callout strong { color: var(--amber); font-size: 14px; letter-spacing: .08em; text-transform: uppercase; }
                    .callout p { margin: 0; color: #c7d5e1; line-height: 1.7; }
                    .gate-grid {
                      display: grid;
                      grid-template-columns: repeat(7, minmax(150px, 1fr));
                      gap: 10px;
                      padding: 0;
                      margin: 0;
                      list-style: none;
                      overflow-x: auto;
                    }
                    .gate {
                      min-height: 165px;
                      padding: 16px;
                      border: 1px solid var(--line);
                      border-radius: 16px;
                      background: rgba(4, 15, 25, .48);
                    }
                    .gate-id { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-weight: 900; }
                    .gate p { margin: 14px 0 0; color: #9eb3c5; font-size: 12px; line-height: 1.55; }
                    .badge {
                      display: inline-flex;
                      align-items: center;
                      justify-content: center;
                      min-height: 25px;
                      padding: 4px 9px;
                      border-radius: 999px;
                      font-size: 10px;
                      font-weight: 900;
                      letter-spacing: .06em;
                    }
                    .passed { color: var(--green); background: rgba(87, 227, 161, .1); border-color: rgba(87, 227, 161, .28); }
                    .open, .in-progress { color: var(--amber); background: rgba(255, 203, 107, .1); border-color: rgba(255, 203, 107, .28); }
                    .planned { color: var(--muted); background: rgba(145, 169, 190, .09); }
                    .blocked { color: var(--red); background: rgba(255, 123, 139, .1); }
                    .accepted { color: var(--green); background: rgba(87, 227, 161, .1); }
                    .table-wrap { overflow: auto; border: 1px solid var(--line); border-radius: 16px; }
                    table { width: 100%; border-collapse: collapse; min-width: 850px; }
                    th {
                      position: sticky;
                      top: 0;
                      z-index: 1;
                      padding: 13px 14px;
                      color: #a9bed0;
                      background: #10243a;
                      font-size: 11px;
                      letter-spacing: .06em;
                      text-align: left;
                      text-transform: uppercase;
                    }
                    td { padding: 14px; border-top: 1px solid var(--line); color: #cbd8e3; font-size: 13px; line-height: 1.5; vertical-align: top; }
                    tbody tr:hover { background: rgba(84, 214, 255, .035); }
                    .current-row { background: rgba(255, 203, 107, .055); }
                    .stage-id, .cap-id { color: var(--cyan); font-family: "SFMono-Regular", Consolas, monospace; font-weight: 800; white-space: nowrap; }
                    .filters { display: flex; flex-wrap: wrap; gap: 10px; }
                    .filters input, .filters select {
                      min-height: 40px;
                      padding: 8px 12px;
                      color: var(--text);
                      border: 1px solid var(--line);
                      border-radius: 11px;
                      outline: none;
                      background: #0a1a2a;
                    }
                    .filters input { width: min(360px, 100%); }
                    .filters input:focus, .filters select:focus { border-color: var(--cyan); box-shadow: 0 0 0 3px rgba(84, 214, 255, .1); }
                    .level { font-weight: 900; }
                    .level-L0 { color: #7f96a9; }
                    .level-L1 { color: var(--blue); }
                    .level-L2 { color: var(--cyan); }
                    .level-L3 { color: var(--green); }
                    .level-L4 { color: var(--amber); }
                    .blockers { display: grid; gap: 10px; padding: 0; list-style: none; counter-reset: blocker; }
                    .blockers li {
                      display: grid;
                      grid-template-columns: 34px 1fr;
                      gap: 12px;
                      align-items: center;
                      padding: 13px;
                      border: 1px solid rgba(255, 123, 139, .2);
                      border-radius: 13px;
                      color: #cfdae4;
                      background: rgba(255, 123, 139, .045);
                      counter-increment: blocker;
                    }
                    .blockers li::before {
                      content: counter(blocker);
                      display: grid;
                      place-items: center;
                      width: 30px;
                      height: 30px;
                      border-radius: 9px;
                      color: var(--red);
                      background: rgba(255, 123, 139, .1);
                      font-weight: 900;
                    }
                    .evidence-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
                    .evidence-item { padding: 15px; border: 1px solid var(--line); border-radius: 14px; background: rgba(4, 15, 25, .42); }
                    .evidence-item dt { color: var(--muted); font-size: 11px; font-weight: 800; letter-spacing: .06em; text-transform: uppercase; }
                    .evidence-item dd { margin: 8px 0 0; color: #d3dee8; font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
                    code { font-family: "SFMono-Regular", Consolas, monospace; color: #bfeaff; }
                    footer { padding: 24px 4px 0; color: #738ca1; font-size: 12px; line-height: 1.7; }
                    .empty { display: none; padding: 28px; color: var(--muted); text-align: center; }
                    @media (max-width: 980px) {
                      .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
                      .evidence-grid { grid-template-columns: 1fr; }
                      .callout { grid-template-columns: 1fr; }
                    }
                    @media (max-width: 620px) {
                      .shell { width: min(100% - 20px, 1480px); padding-top: 10px; }
                      .hero, .panel { padding: 19px; border-radius: 17px; }
                      .summary-grid { grid-template-columns: 1fr; }
                      .panel-head { align-items: stretch; flex-direction: column; }
                    }
                  </style>
                </head>
                <body data-matrix-digest="{{MATRIX_DIGEST}}" data-state-digest="{{STATE_DIGEST}}" data-code-digest="{{CODE_DIGEST}}">
                  <main class="shell">
                    <section class="hero">
                      <p class="eyebrow">Reference-driven Java Agent Runtime</p>
                      <h1>cc-java 项目进度看板</h1>
                      <p class="hero-copy">{{CURRENT_SUMMARY}}</p>
                      <div class="hero-meta">
                        <span class="chip"><span class="dot"></span>当前 {{CURRENT_STAGE}} · {{CURRENT_STAGE_NAME}}</span>
                        <span class="chip">Stage Exit · {{STAGE_EXIT}}</span>
                        <span class="chip">更新 · {{LAST_UPDATED}}</span>
                        <a class="chip" href="feature-parity-matrix.md">打开权威功能矩阵 ↗</a>
                      </div>
                    </section>

                    <section class="summary-grid" aria-label="项目摘要">
                      <article class="metric">
                        <p class="metric-label">Capability Coverage</p>
                        <p class="metric-value">{{COVERAGE}}%</p>
                        <div class="progress-track"><div class="progress-bar" style="width: {{COVERAGE}}%"></div></div>
                        <p class="metric-note">按 {{CAPABILITY_COUNT}} 项等权、默认目标 L3 计算。</p>
                      </article>
                      <article class="metric">
                        <p class="metric-label">Current Stage</p>
                        <p class="metric-value">{{CURRENT_STAGE}}</p>
                        <p class="metric-note">{{CURRENT_STAGE_NAME}} · {{CURRENT_STATUS_LABEL}}</p>
                      </article>
                      <article class="metric">
                        <p class="metric-label">Implemented Skeletons</p>
                        <p class="metric-value">{{L1_COUNT}}</p>
                        <p class="metric-note">L1 学习骨架；L0 仍有 {{L0_COUNT}} 项。</p>
                      </article>
                      <article class="metric">
                        <p class="metric-label">Open Gates</p>
                        <p class="metric-value">{{OPEN_GATE_COUNT}}</p>
                        <p class="metric-note">必须关闭 G4-G6 后才能进入下一 Stage。</p>
                      </article>
                    </section>

                    <section class="panel callout">
                      <strong>Next allowed action</strong>
                      <p>{{NEXT_ACTION}}</p>
                    </section>

                    <section class="panel" id="gates">
                      <div class="panel-head">
                        <div>
                          <h2>当前 Stage Gate</h2>
                          <p class="panel-desc">Capability 可以先达到阶段等级，但 Stage 只有 G0-G6 全部通过才退出。</p>
                        </div>
                      </div>
                      <ol class="gate-grid">{{GATE_ROWS}}</ol>
                    </section>

                    <section class="panel" id="roadmap">
                      <div class="panel-head">
                        <div>
                          <h2>S00-S15 路线</h2>
                          <p class="panel-desc">Stage 状态来自当前状态文件；主题和交付物直接解析自功能矩阵。</p>
                        </div>
                      </div>
                      <div class="table-wrap">
                        <table>
                          <thead><tr><th>Stage</th><th>状态</th><th>主题</th><th>核心交付</th><th>关联能力</th></tr></thead>
                          <tbody>{{STAGE_ROWS}}</tbody>
                        </table>
                      </div>
                    </section>

                    <section class="panel" id="capabilities">
                      <div class="panel-head">
                        <div>
                          <h2>Capability 明细</h2>
                          <p class="panel-desc"><span id="visibleCount">{{CAPABILITY_COUNT}}</span> / {{CAPABILITY_COUNT}} 项可见。支持按 ID、名称、目标、等级或 Stage 筛选。</p>
                        </div>
                        <div class="filters">
                          <input id="capabilityQuery" type="search" placeholder="搜索 Capability…" autocomplete="off">
                          <select id="levelFilter" aria-label="按等级筛选">{{LEVEL_OPTIONS}}</select>
                          <select id="stageFilter" aria-label="按 Stage 筛选">{{STAGE_OPTIONS}}</select>
                        </div>
                      </div>
                      <div class="table-wrap">
                        <table id="capabilityTable">
                          <thead><tr><th>ID</th><th>参考能力</th><th>Java 重实现目标</th><th>当前</th><th>Stage</th><th>参考</th></tr></thead>
                          <tbody>{{CAPABILITY_ROWS}}</tbody>
                        </table>
                        <div class="empty" id="emptyState">没有符合当前筛选条件的 Capability。</div>
                      </div>
                    </section>

                    <section class="panel" id="blockers">
                      <div class="panel-head">
                        <div>
                          <h2>Stage Exit Blockers</h2>
                          <p class="panel-desc">这些项目全部关闭前，不允许宣称 S01 已退出或 S02 已可用。</p>
                        </div>
                      </div>
                      <ol class="blockers">{{BLOCKER_ROWS}}</ol>
                    </section>

                    <section class="panel" id="evidence">
                      <div class="panel-head">
                        <div>
                          <h2>最近变更与验证</h2>
                          <p class="panel-desc">诊断结果与正式 Stage 证据分开显示，避免把“测试跑过”误写成“Stage 已完成”。</p>
                        </div>
                      </div>
                      <dl class="evidence-grid">
                        <div class="evidence-item"><dt>Last change</dt><dd>{{LAST_CHANGE}}</dd></div>
                        <div class="evidence-item"><dt>Evidence class</dt><dd><code>{{EVIDENCE_CLASS}}</code></dd></div>
                        <div class="evidence-item"><dt>Code commit</dt><dd><code>{{EVIDENCE_COMMIT}}</code></dd></div>
                        <div class="evidence-item"><dt>Standard command</dt><dd><code>{{EVIDENCE_COMMAND}}</code></dd></div>
                        <div class="evidence-item"><dt>Result</dt><dd>{{EVIDENCE_SUMMARY}}</dd></div>
                        <div class="evidence-item"><dt>Source digests</dt><dd><code>matrix {{MATRIX_DIGEST}}</code><br><code>state {{STATE_DIGEST}}</code><br><code>code/build {{CODE_DIGEST}}</code></dd></div>
                      </dl>
                    </section>

                    <footer>
                      此页面由 <code>java scripts/ProgressDashboard.java</code> 生成。
                      Capability 与路线以 <a href="feature-parity-matrix.md">feature-parity-matrix.md</a> 为权威，
                      Gate 与证据状态来自 <a href="progress-state.properties">progress-state.properties</a>。
                      请勿手工修改 HTML。
                    </footer>
                  </main>
                  <script>
                    const query = document.getElementById("capabilityQuery");
                    const level = document.getElementById("levelFilter");
                    const stage = document.getElementById("stageFilter");
                    const rows = [...document.querySelectorAll("#capabilityTable tbody tr")];
                    const visibleCount = document.getElementById("visibleCount");
                    const emptyState = document.getElementById("emptyState");

                    function applyFilters() {
                      const term = query.value.trim().toLocaleLowerCase("zh-CN");
                      let visible = 0;
                      for (const row of rows) {
                        const textMatches = !term || row.textContent.toLocaleLowerCase("zh-CN").includes(term);
                        const levelMatches = !level.value || row.dataset.level === level.value;
                        const stageMatches = !stage.value || row.dataset.stages.split("/").includes(stage.value);
                        const show = textMatches && levelMatches && stageMatches;
                        row.hidden = !show;
                        if (show) visible++;
                      }
                      visibleCount.textContent = visible;
                      emptyState.style.display = visible === 0 ? "block" : "none";
                    }

                    query.addEventListener("input", applyFilters);
                    level.addEventListener("change", applyFilters);
                    stage.addEventListener("change", applyFilters);
                  </script>
                </body>
                </html>
                """;

        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("{{MATRIX_DIGEST}}", matrixDigest);
        replacements.put("{{STATE_DIGEST}}", stateDigest);
        replacements.put("{{CODE_DIGEST}}", codeDigest);
        replacements.put("{{CURRENT_SUMMARY}}", html(data.currentSummary()));
        replacements.put("{{CURRENT_STAGE}}", html(data.currentStage().id()));
        replacements.put("{{CURRENT_STAGE_NAME}}", html(data.currentStage().name()));
        replacements.put("{{STAGE_EXIT}}", html(data.currentExit()));
        replacements.put("{{LAST_UPDATED}}", html(data.lastUpdated()));
        replacements.put("{{COVERAGE}}", String.format(Locale.ROOT, "%.2f", data.coverage()));
        replacements.put("{{CAPABILITY_COUNT}}", Integer.toString(data.capabilities().size()));
        replacements.put("{{CURRENT_STATUS_LABEL}}", html(statusLabel(data.currentStatus())));
        replacements.put("{{L1_COUNT}}", Integer.toString(data.levels().get("L1")));
        replacements.put("{{L0_COUNT}}", Integer.toString(data.levels().get("L0")));
        replacements.put("{{OPEN_GATE_COUNT}}", Long.toString(data.gates().stream()
                .filter(gate -> !"PASSED".equals(gate.status()))
                .count()));
        replacements.put("{{NEXT_ACTION}}", html(data.nextAction()));
        replacements.put("{{GATE_ROWS}}", gateRows);
        replacements.put("{{STAGE_ROWS}}", stageRows);
        replacements.put("{{LEVEL_OPTIONS}}", levelOptions);
        replacements.put("{{STAGE_OPTIONS}}", stageOptions);
        replacements.put("{{CAPABILITY_ROWS}}", capabilityRows);
        replacements.put("{{BLOCKER_ROWS}}", blockerRows);
        replacements.put("{{LAST_CHANGE}}", html(data.lastChange()));
        replacements.put("{{EVIDENCE_CLASS}}", html(data.evidenceClassification()));
        replacements.put("{{EVIDENCE_COMMIT}}", html(data.evidenceCommit()));
        replacements.put("{{EVIDENCE_COMMAND}}", html(data.evidenceCommand()));
        replacements.put("{{EVIDENCE_SUMMARY}}", html(data.evidenceSummary()));

        String output = template;
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            output = output.replace(replacement.getKey(), replacement.getValue());
        }
        Matcher unresolved = Pattern.compile("\\{\\{[A-Z0-9_]+}}").matcher(output);
        if (unresolved.find()) {
            throw new IllegalStateException("Unresolved dashboard placeholder: " + unresolved.group());
        }
        return output;
    }

    private static String renderStageRows(DashboardData data) {
        StringBuilder rows = new StringBuilder();
        for (StageRow stage : data.stages()) {
            String status = data.state().getProperty("stage." + stage.id() + ".status");
            if (status == null) {
                status = stage.id().compareTo(data.currentStage().id()) < 0
                        ? "ACCEPTED"
                        : stage.id().equals(data.currentStage().id())
                                ? data.currentStatus()
                                : "PLANNED";
            } else {
                status = validatedStageStatus(status, "stage." + stage.id() + ".status");
            }
            String rowClass = stage.id().equals(data.currentStage().id())
                    ? " class=\"current-row\""
                    : "";
            rows.append("<tr")
                    .append(rowClass)
                    .append("><td class=\"stage-id\">")
                    .append(html(stage.id()))
                    .append("</td><td><span class=\"badge ")
                    .append(statusCss(status))
                    .append("\">")
                    .append(html(statusLabel(status)))
                    .append("</span></td><td>")
                    .append(html(stage.name()))
                    .append("</td><td>")
                    .append(html(stage.deliverable()))
                    .append("</td><td>")
                    .append(data.stageCapabilityCounts().get(stage.id()))
                    .append("</td></tr>");
        }
        return rows.toString();
    }

    private static String renderCapabilityRows(List<CapabilityRow> capabilities) {
        StringBuilder rows = new StringBuilder();
        for (CapabilityRow capability : capabilities) {
            rows.append("<tr data-level=\"")
                    .append(html(capability.level()))
                    .append("\" data-stages=\"")
                    .append(html(capability.stages()))
                    .append("\"><td class=\"cap-id\">")
                    .append(html(capability.id()))
                    .append("</td><td>")
                    .append(html(capability.referenceCapability()))
                    .append("</td><td>")
                    .append(html(capability.javaTarget()))
                    .append("</td><td><span class=\"level level-")
                    .append(html(capability.level()))
                    .append("\">")
                    .append(html(capability.level()))
                    .append("</span></td><td>")
                    .append(html(capability.stages()))
                    .append("</td><td>")
                    .append(html(capability.reference()))
                    .append("</td></tr>");
        }
        return rows.toString();
    }

    private static String renderGateRows(List<GateRow> gates) {
        StringBuilder rows = new StringBuilder();
        for (GateRow gate : gates) {
            rows.append("<li class=\"gate ")
                    .append(statusCss(gate.status()))
                    .append("\"><div class=\"gate-id\"><span>")
                    .append(html(gate.id()))
                    .append("</span><span class=\"badge ")
                    .append(statusCss(gate.status()))
                    .append("\">")
                    .append(html(statusLabel(gate.status())))
                    .append("</span></div><p>")
                    .append(html(gate.summary()))
                    .append("</p></li>");
        }
        return rows.toString();
    }

    private static String renderBlockers(List<String> blockers) {
        StringBuilder rows = new StringBuilder();
        for (String blocker : blockers) {
            rows.append("<li>").append(html(blocker)).append("</li>");
        }
        return rows.toString();
    }

    private static String renderLevelOptions(Map<String, Integer> levels) {
        StringBuilder options = new StringBuilder("<option value=\"\">全部等级</option>");
        for (Map.Entry<String, Integer> level : levels.entrySet()) {
            options.append("<option value=\"")
                    .append(level.getKey())
                    .append("\">")
                    .append(level.getKey())
                    .append(" · ")
                    .append(level.getValue())
                    .append("</option>");
        }
        return options.toString();
    }

    private static String renderStageOptions(List<StageRow> stages) {
        StringBuilder options = new StringBuilder("<option value=\"\">全部 Stage</option>");
        for (StageRow stage : stages) {
            options.append("<option value=\"")
                    .append(html(stage.id()))
                    .append("\">")
                    .append(html(stage.id()))
                    .append(" · ")
                    .append(html(stage.name()))
                    .append("</option>");
        }
        return options.toString();
    }

    private static String plainMarkdown(String value) {
        String result = MARKDOWN_LINK.matcher(value).replaceAll("$1");
        return result.replace("`", "").replace("**", "").trim();
    }

    private static String html(String value) {
        return Objects.requireNonNull(value, "value")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String statusCss(String status) {
        return switch (status) {
            case "PASSED" -> "passed";
            case "ACCEPTED" -> "accepted";
            case "IN_PROGRESS" -> "in-progress";
            case "OPEN" -> "open";
            case "BLOCKED" -> "blocked";
            case "PLANNED" -> "planned";
            default -> throw new IllegalStateException("Unreachable status: " + status);
        };
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "PASSED", "ACCEPTED" -> "已通过";
            case "IN_PROGRESS" -> "进行中";
            case "OPEN" -> "待关闭";
            case "BLOCKED" -> "阻塞";
            case "PLANNED" -> "未开始";
            default -> throw new IllegalStateException("Invalid status label: " + status);
        };
    }

    private static String shortSha256(String value) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        return shortHex(hash);
    }

    private static String repositoryInputDigest(Path root)
            throws IOException, NoSuchAlgorithmException {
        List<Path> inputs;
        try (Stream<Path> stream = Files.walk(root)) {
            inputs = stream.filter(Files::isRegularFile)
                    .filter(path -> isCodeOrBuildInput(root.relativize(path)))
                    .sorted(Comparator.comparing(
                            path -> normalizedRelativePath(root, path),
                            Comparator.naturalOrder()))
                    .toList();
        }
        MessageDigest treeDigest = MessageDigest.getInstance("SHA-256");
        for (Path input : inputs) {
            String relative = normalizedRelativePath(root, input);
            treeDigest.update(relative.getBytes(StandardCharsets.UTF_8));
            treeDigest.update((byte) '\t');
            treeDigest.update(MessageDigest.getInstance("SHA-256").digest(
                    normalizedInputBytes(input)));
            treeDigest.update((byte) '\n');
        }
        return shortHex(treeDigest.digest());
    }

    private static byte[] normalizedInputBytes(Path input) throws IOException {
        String name = input.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean text = name.endsWith(".java")
                || name.endsWith(".xml")
                || name.endsWith(".properties")
                || name.endsWith(".cmd")
                || name.endsWith(".ps1")
                || name.endsWith(".sh")
                || "mvnw".equals(name);
        if (!text) {
            return Files.readAllBytes(input);
        }
        String content = Files.readString(input, StandardCharsets.UTF_8);
        return normalizeLines(content).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isCodeOrBuildInput(Path relativePath) {
        String relative = relativePath.toString().replace('\\', '/');
        String fileName = relativePath.getFileName().toString();
        return "pom.xml".equals(fileName)
                || "mvnw".equals(relative)
                || "mvnw.cmd".equals(relative)
                || relative.startsWith(".mvn/")
                || relative.startsWith("scripts/")
                || (relative.startsWith("cc-java-") && relative.contains("/src/"));
    }

    private static String normalizedRelativePath(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String shortHex(byte[] hash) {
        StringBuilder hex = new StringBuilder();
        for (byte item : hash) {
            hex.append(String.format(Locale.ROOT, "%02x", item));
        }
        return hex.substring(0, 12);
    }

    private static void verifyFresh(Path outputPath, String expected) throws IOException {
        if (!Files.isRegularFile(outputPath)) {
            throw new IllegalStateException(
                    "Progress dashboard is missing. Run: java scripts/ProgressDashboard.java");
        }
        String actual = Files.readString(outputPath, StandardCharsets.UTF_8);
        if (!normalizeLines(actual).equals(normalizeLines(expected))) {
            throw new IllegalStateException(
                    "Progress dashboard is stale. Run: java scripts/ProgressDashboard.java");
        }
    }

    private static String normalizeLines(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private record StageRow(String id, String name, String deliverable) {
    }

    private record CapabilityRow(
            String id,
            String referenceCapability,
            String javaTarget,
            String level,
            String stages,
            String reference) {
    }

    private record GateRow(String id, String status, String summary) {
    }

    private record DashboardData(
            StageRow currentStage,
            String currentStatus,
            String currentExit,
            String currentSummary,
            String nextAction,
            String lastUpdated,
            String lastChange,
            String evidenceCommit,
            String evidenceClassification,
            String evidenceSummary,
            String evidenceCommand,
            List<StageRow> stages,
            List<CapabilityRow> capabilities,
            Map<String, Integer> levels,
            double coverage,
            Map<String, Integer> stageCapabilityCounts,
            List<GateRow> gates,
            List<String> blockers,
            Properties state) {
    }
}
