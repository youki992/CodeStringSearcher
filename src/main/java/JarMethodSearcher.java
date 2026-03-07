import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JarMethodSearcher {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static void main(String[] args) {
        SearchConfig config;
        try {
            config = parseConfig(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage();
            return;
        }

        if (config.jarDirectory == null || config.jarDirectory.trim().isEmpty()) {
            System.err.println("jarDirectory 不能为空");
            return;
        }
        if (config.keywords.isEmpty()) {
            System.err.println("keywords 不能为空，支持逗号分隔多个关键字");
            return;
        }

        Path rootPath = Paths.get(config.jarDirectory);
        if (!Files.exists(rootPath)) {
            System.err.println("路径不存在: " + rootPath);
            return;
        }

        try {
            if (config.outputDir != null && !config.outputDir.trim().isEmpty()) {
                Files.createDirectories(Paths.get(config.outputDir));
            }

            List<Path> jarFiles = findJarsRecursively(config.jarDirectory);
            System.out.printf("找到 %d 个 .jar 文件，关键字：%s%n", jarFiles.size(), config.keywords);

            int threadCount = Math.max(2, Runtime.getRuntime().availableProcessors());
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Future<JarScanResult>> futures = new ArrayList<>();

            for (Path jarFile : jarFiles) {
                futures.add(executor.submit(() -> scanSingleJar(jarFile, config)));
            }

            executor.shutdown();
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

            List<JarScanResult> allResults = new ArrayList<>();
            for (Future<JarScanResult> future : futures) {
                JarScanResult result = future.get();
                if (result != null) {
                    allResults.add(result);
                }
            }

            printSummary(allResults);
            exportIfNeeded(allResults, config);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static SearchConfig parseConfig(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("参数不能为空");
        }

        if ("--config".equals(args[0])) {
            if (args.length < 2) {
                throw new IllegalArgumentException("--config 需要指定配置文件路径");
            }
            return loadConfigFromFile(args[1]);
        }

        if (args.length == 1 && args[0].endsWith(".properties")) {
            return loadConfigFromFile(args[0]);
        }

        if (args.length < 2) {
            throw new IllegalArgumentException("命令行模式至少需要 <jarDirectory> <keywords>");
        }

        SearchConfig cfg = new SearchConfig();
        cfg.jarDirectory = args[0];
        cfg.keywords = parseKeywords(args[1]);
        cfg.cfrJarPath = resolveCfrPath(args.length >= 3 ? args[2] : null);
        cfg.outputDir = args.length >= 4 ? args[3] : null;
        cfg.outputFormat = "all";
        cfg.caseSensitive = false;
        cfg.contextLines = 3;
        return cfg;
    }

    private static SearchConfig loadConfigFromFile(String configPath) {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(configPath)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取配置文件失败: " + configPath + " -> " + e.getMessage());
        }

        SearchConfig cfg = new SearchConfig();
        cfg.jarDirectory = trimToNull(props.getProperty("jarDirectory"));
        cfg.keywords = parseKeywords(props.getProperty("keywords", ""));
        cfg.cfrJarPath = resolveCfrPath(trimToNull(props.getProperty("cfrJarPath")));
        cfg.outputDir = trimToNull(props.getProperty("outputDir"));
        cfg.outputFormat = normalizeOutputFormat(props.getProperty("outputFormat", "all"));
        cfg.caseSensitive = Boolean.parseBoolean(props.getProperty("caseSensitive", "false"));
        cfg.contextLines = parseIntOrDefault(props.getProperty("contextLines"), 3);

        return cfg;
    }

    private static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(1, parsed);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static String normalizeOutputFormat(String value) {
        if (value == null) {
            return "all";
        }
        String normalized = value.trim().toLowerCase();
        if ("txt".equals(normalized) || "json".equals(normalized) || "all".equals(normalized)) {
            return normalized;
        }
        return "all";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String resolveCfrPath(String argPath) {
        if (argPath != null && !argPath.trim().isEmpty()) {
            return argPath.trim();
        }
        String env = System.getenv("CFR_JAR_PATH");
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        return "cfr.jar";
    }

    private static List<String> parseKeywords(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> uniq = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String keyword = part.trim();
            if (!keyword.isEmpty()) {
                uniq.add(keyword);
            }
        }
        return new ArrayList<>(uniq);
    }

    private static List<Path> findJarsRecursively(String rootDir) throws IOException {
        List<Path> jars = new ArrayList<>();
        Files.walk(Paths.get(rootDir))
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().toLowerCase().endsWith(".jar"))
                .forEach(jars::add);
        return jars;
    }

    private static JarScanResult scanSingleJar(Path jarPath, SearchConfig config) {
        Path tempDir = null;
        JarScanResult result = new JarScanResult();
        result.jarPath = jarPath.toString();

        try {
            tempDir = Files.createTempDirectory("decompiled_");
            result.bytecodeMatches = scanBytecodeForMethods(jarPath, config.keywords, config.caseSensitive);

            if (!result.bytecodeMatches.isEmpty()) {
                List<Path> classFiles = extractOnlyMatchedClasses(jarPath, result.bytecodeMatches, tempDir);
                List<Path> javaFiles = decompileWithCFR(classFiles, tempDir, config.cfrJarPath);
                result.codeMatches = searchInDecompiledFiles(javaFiles, config.keywords, config.caseSensitive, config.contextLines);
            }
        } catch (Exception e) {
            result.error = e.getMessage();
        } finally {
            if (tempDir != null) {
                try {
                    deleteDirectory(tempDir);
                } catch (IOException ignored) {
                }
            }
        }

        return result;
    }

    private static List<String> scanBytecodeForMethods(Path jarPath, List<String> keywords, boolean caseSensitive) throws Exception {
        Set<String> matchedClasses = new HashSet<>();
        List<String> bytecodeLines = new ArrayList<>();

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream is = jar.getInputStream(entry)) {
                    JavaClass clazz = new ClassParser(is, entry.getName()).parse();
                    for (Method method : clazz.getMethods()) {
                        String methodName = method.getName();
                        if (containsAnyKeyword(methodName, keywords, caseSensitive)) {
                            matchedClasses.add(entry.getName());
                            bytecodeLines.add(clazz.getClassName() + "." + methodName + method.getSignature());
                        }
                    }
                }
            }
        }

        bytecodeLines.sort(String::compareTo);
        return bytecodeLines;
    }

    private static boolean containsAnyKeyword(String source, List<String> keywords, boolean caseSensitive) {
        if (source == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (contains(source, keyword, caseSensitive)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String text, String keyword, boolean caseSensitive) {
        if (caseSensitive) {
            return text.contains(keyword);
        }
        return text.toLowerCase().contains(keyword.toLowerCase());
    }

    private static List<Path> extractOnlyMatchedClasses(Path jarPath, List<String> matchedBytecodeLines, Path destDir) throws IOException {
        Set<String> classEntries = new HashSet<>();
        for (String line : matchedBytecodeLines) {
            int idx = line.indexOf('.');
            if (idx <= 0) {
                continue;
            }
        }

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }

                String className = entry.getName()
                        .replace('/', '.')
                        .replace(".class", "");

                for (String line : matchedBytecodeLines) {
                    if (line.startsWith(className + ".")) {
                        classEntries.add(entry.getName());
                        break;
                    }
                }
            }

            List<Path> classFiles = new ArrayList<>();
            for (String entryName : classEntries) {
                JarEntry entry = jar.getJarEntry(entryName);
                if (entry == null) {
                    continue;
                }
                Path target = destDir.resolve(entryName);
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                try (InputStream is = jar.getInputStream(entry)) {
                    Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                }
                classFiles.add(target);
            }
            return classFiles;
        }
    }

    private static List<Path> decompileWithCFR(List<Path> classFiles, Path outputDir, String cfrJarPath) throws Exception {
        List<Path> javaFiles = new ArrayList<>();
        Path cfr = Paths.get(cfrJarPath);
        if (!Files.exists(cfr)) {
            System.err.println("⚠️ 未找到 CFR 反编译器: " + cfrJarPath);
            return javaFiles;
        }

        for (Path classFile : classFiles) {
            ProcessBuilder pb = new ProcessBuilder(
                    "java", "-jar", cfr.toString(), classFile.toString(), "--outputdir", outputDir.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                }
            }

            int code = process.waitFor();
            if (code != 0) {
                continue;
            }
        }

        Files.walk(outputDir)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(javaFiles::add);

        return javaFiles;
    }

    private static List<CodeMatch> searchInDecompiledFiles(List<Path> javaFiles, List<String> keywords, boolean caseSensitive, int contextLines) throws IOException {
        List<CodeMatch> matches = new ArrayList<>();
        for (Path javaFile : javaFiles) {
            List<String> lines = Files.readAllLines(javaFile);
            for (int i = 0; i < lines.size(); i++) {
                List<String> hitKeywords = findHitKeywords(lines.get(i), keywords, caseSensitive);
                if (hitKeywords.isEmpty()) {
                    continue;
                }

                CodeMatch match = new CodeMatch();
                match.fileName = javaFile.getFileName().toString();
                match.lineNumber = i + 1;
                match.hitKeywords = hitKeywords;
                match.context = extractContext(lines, i, contextLines);
                matches.add(match);
            }
        }
        return matches;
    }

    private static List<String> findHitKeywords(String line, List<String> keywords, boolean caseSensitive) {
        List<String> hits = new ArrayList<>();
        for (String keyword : keywords) {
            if (contains(line, keyword, caseSensitive)) {
                hits.add(keyword);
            }
        }
        return hits;
    }

    private static List<String> extractContext(List<String> lines, int index, int contextLines) {
        int start = Math.max(0, index - contextLines);
        int end = Math.min(lines.size() - 1, index + contextLines);
        List<String> context = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            context.add((i + 1) + ": " + lines.get(i));
        }
        return context;
    }

    private static void printSummary(List<JarScanResult> results) {
        int bytecodeTotal = 0;
        int codeTotal = 0;
        int okCount = 0;

        for (JarScanResult result : results) {
            if (result.error == null) {
                okCount++;
            }
            bytecodeTotal += result.bytecodeMatches.size();
            codeTotal += result.codeMatches.size();
        }

        System.out.println("\n================ 扫描完成 ================");
        System.out.println("JAR总数: " + results.size());
        System.out.println("处理成功: " + okCount);
        System.out.println("字节码匹配总数: " + bytecodeTotal);
        System.out.println("代码匹配总数: " + codeTotal);
        System.out.println("==========================================");
    }

    private static void exportIfNeeded(List<JarScanResult> results, SearchConfig config) throws IOException {
        if (config.outputDir == null || config.outputDir.trim().isEmpty()) {
            return;
        }
        String ts = LocalDateTime.now().format(TS_FMT);

        if ("txt".equals(config.outputFormat) || "all".equals(config.outputFormat)) {
            Path txtPath = Paths.get(config.outputDir, "search-result-" + ts + ".txt");
            writeTxt(results, txtPath);
            System.out.println("TXT结果已导出: " + txtPath);
        }

        if ("json".equals(config.outputFormat) || "all".equals(config.outputFormat)) {
            Path jsonPath = Paths.get(config.outputDir, "search-result-" + ts + ".json");
            writeJson(results, jsonPath);
            System.out.println("JSON结果已导出: " + jsonPath);
        }
    }

    private static void writeTxt(List<JarScanResult> results, Path path) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (JarScanResult result : results) {
                writer.write("JAR: " + result.jarPath + "\n");
                if (result.error != null) {
                    writer.write("ERROR: " + result.error + "\n\n");
                    continue;
                }

                writer.write("BytecodeMatches: " + result.bytecodeMatches.size() + "\n");
                for (String line : result.bytecodeMatches) {
                    writer.write("  - " + line + "\n");
                }

                writer.write("CodeMatches: " + result.codeMatches.size() + "\n");
                for (CodeMatch match : result.codeMatches) {
                    writer.write("  * File=" + match.fileName + ", line=" + match.lineNumber + ", keywords=" + match.hitKeywords + "\n");
                    for (String ctx : match.context) {
                        writer.write("      " + ctx + "\n");
                    }
                }
                writer.write("\n");
            }
        }
    }

    private static void writeJson(List<JarScanResult> results, Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"generatedAt\": \"").append(escape(LocalDateTime.now().toString())).append("\",\n");
        sb.append("  \"results\": [\n");

        for (int i = 0; i < results.size(); i++) {
            JarScanResult result = results.get(i);
            sb.append("    {\n");
            sb.append("      \"jarPath\": \"").append(escape(result.jarPath)).append("\",\n");
            sb.append("      \"error\": ").append(result.error == null ? "null" : "\"" + escape(result.error) + "\"").append(",\n");

            sb.append("      \"bytecodeMatches\": [");
            for (int j = 0; j < result.bytecodeMatches.size(); j++) {
                if (j > 0) {
                    sb.append(", ");
                }
                sb.append("\"").append(escape(result.bytecodeMatches.get(j))).append("\"");
            }
            sb.append("],\n");

            sb.append("      \"codeMatches\": [\n");
            for (int j = 0; j < result.codeMatches.size(); j++) {
                CodeMatch match = result.codeMatches.get(j);
                sb.append("        {\n");
                sb.append("          \"fileName\": \"").append(escape(match.fileName)).append("\",\n");
                sb.append("          \"lineNumber\": ").append(match.lineNumber).append(",\n");

                sb.append("          \"hitKeywords\": [");
                for (int k = 0; k < match.hitKeywords.size(); k++) {
                    if (k > 0) {
                        sb.append(", ");
                    }
                    sb.append("\"").append(escape(match.hitKeywords.get(k))).append("\"");
                }
                sb.append("],\n");

                sb.append("          \"context\": [");
                for (int k = 0; k < match.context.size(); k++) {
                    if (k > 0) {
                        sb.append(", ");
                    }
                    sb.append("\"").append(escape(match.context.get(k))).append("\"");
                }
                sb.append("]\n");
                sb.append("        }");
                if (j < result.codeMatches.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("      ]\n");
            sb.append("    }");
            if (i < results.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}\n");

        Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  1) CLI模式:");
        System.out.println("     java -jar JarMethodFinder-1.0-SNAPSHOT.jar <jarDirectory> <keywords> [cfrJarPath] [outputDir]");
        System.out.println("     关键字支持逗号分隔，例如: exec,Runtime.getRuntime,ProcessBuilder");
        System.out.println();
        System.out.println("  2) 配置文件模式:");
        System.out.println("     java -jar JarMethodFinder-1.0-SNAPSHOT.jar --config config.properties");
        System.out.println("     或 java -jar JarMethodFinder-1.0-SNAPSHOT.jar config.properties");
        System.out.println();
        System.out.println("Config keys:");
        System.out.println("  jarDirectory=/path/to/jars");
        System.out.println("  keywords=exec,Runtime.getRuntime");
        System.out.println("  cfrJarPath=/path/to/cfr.jar");
        System.out.println("  outputDir=/path/to/output");
        System.out.println("  outputFormat=txt|json|all");
        System.out.println("  caseSensitive=false");
        System.out.println("  contextLines=3");
    }

    private static class SearchConfig {
        String jarDirectory;
        List<String> keywords = new ArrayList<>();
        String cfrJarPath;
        String outputDir;
        String outputFormat;
        boolean caseSensitive;
        int contextLines;
    }

    private static class JarScanResult {
        String jarPath;
        List<String> bytecodeMatches = new ArrayList<>();
        List<CodeMatch> codeMatches = new ArrayList<>();
        String error;
    }

    private static class CodeMatch {
        String fileName;
        int lineNumber;
        List<String> hitKeywords = new ArrayList<>();
        List<String> context = new ArrayList<>();
    }
}
