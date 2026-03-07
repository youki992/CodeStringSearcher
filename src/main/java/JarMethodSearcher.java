import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JarMethodSearcher {

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: java -jar JarMethodFinder.jar <folderPath> <keyword> [cfrJarPath]");
            System.err.println("Tips: if cfrJarPath is omitted, it reads env CFR_JAR_PATH, then ./cfr.jar");
            return;
        }

        String folderPath = args[0];
        String keyword = args[1];
        String cfrJarPath = resolveCfrPath(args.length == 3 ? args[2] : null);

        try {
            List<Path> jarFiles = findJarsRecursively(folderPath);
            System.out.printf("找到 %d 个 .jar 文件，正在搜索关键字 \"%s\"...%n", jarFiles.size(), keyword);

            int threadCount = Math.max(2, Runtime.getRuntime().availableProcessors());
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (Path jarFile : jarFiles) {
                executor.submit(() -> {
                    try {
                        System.out.println("\n🔍 正在处理 JAR：" + jarFile.getFileName());
                        searchInJar(jarFile, keyword, cfrJarPath);
                    } catch (Exception e) {
                        System.err.println("⚠️ 处理失败：" + jarFile.getFileName() + "，错误：" + e.getMessage());
                    }
                });
            }

            executor.shutdown();
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

        } catch (Exception e) {
            e.printStackTrace();
        }
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

    private static List<Path> findJarsRecursively(String rootDir) throws IOException {
        List<Path> jars = new ArrayList<>();
        Path rootPath = Paths.get(rootDir);

        if (!Files.exists(rootPath)) {
            System.err.println("路径不存在：" + rootDir);
            return jars;
        }

        Files.walk(rootPath)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().toLowerCase().endsWith(".jar"))
                .forEach(jars::add);

        return jars;
    }

    private static void searchInJar(Path jarPath, String keyword, String cfrJarPath) throws Exception {
        Pattern pattern = Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);
        Path tempDir = Files.createTempDirectory("decompiled_");

        try {
            List<String> matchedClassEntries = scanBytecodeForMethods(jarPath, keyword);
            if (matchedClassEntries.isEmpty()) {
                System.out.println("⚠️ 未找到匹配项");
                return;
            }

            List<Path> classFiles = extractOnlyMatchedClasses(jarPath, matchedClassEntries, tempDir);
            List<Path> javaFiles = decompileWithCFR(classFiles, tempDir, cfrJarPath);

            for (Path javaFile : javaFiles) {
                searchInFile(javaFile, pattern);
            }
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private static List<Path> extractOnlyMatchedClasses(Path jarPath, List<String> matchedEntries, Path destDir) throws IOException {
        List<Path> classFiles = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            for (String entryName : matchedEntries) {
                JarEntry entry = jarFile.getJarEntry(entryName);
                if (entry == null) {
                    continue;
                }
                Path target = destDir.resolve(entryName);
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                try (InputStream is = jarFile.getInputStream(entry)) {
                    Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                }
                classFiles.add(target);
            }
        }
        return classFiles;
    }

    private static List<String> scanBytecodeForMethods(Path jarPath, String keyword) throws Exception {
        Set<String> matchedClasses = new HashSet<>();
        String loweredKeyword = keyword.toLowerCase();

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
                        if (methodName != null && methodName.toLowerCase().contains(loweredKeyword)) {
                            System.out.println("【字节码匹配】" + clazz.getClassName() + "." + methodName + method.getSignature());
                            System.out.println("    来自：" + jarPath);
                            matchedClasses.add(entry.getName());
                        }
                    }
                }
            }
        }

        return new ArrayList<>(matchedClasses);
    }

    private static List<Path> decompileWithCFR(List<Path> classFiles, Path outputDir, String cfrJarPath) throws Exception {
        List<Path> javaFiles = new ArrayList<>();

        Path cfr = Paths.get(cfrJarPath);
        if (!Files.exists(cfr)) {
            System.err.println("⚠️ 未找到 CFR 反编译器：" + cfrJarPath + "，请通过第三个参数或环境变量 CFR_JAR_PATH 指定");
            return javaFiles;
        }

        for (Path classFile : classFiles) {
            ProcessBuilder pb = new ProcessBuilder(
                    "java", "-jar", cfr.toString(), classFile.toString(), "--outputdir", outputDir.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                }
            }

            int code = process.waitFor();
            if (code != 0) {
                System.err.println("⚠️ 反编译失败：" + classFile.getFileName());
                continue;
            }

            String rel = classFile.toString();
            if (rel.endsWith(".class")) {
                rel = rel.substring(0, rel.length() - ".class".length()) + ".java";
            }
            Path candidate = Paths.get(rel);
            Path javaFile = outputDir.resolve(outputDir.relativize(candidate).toString());

            if (Files.exists(javaFile)) {
                javaFiles.add(javaFile);
            }
        }

        return javaFiles;
    }

    private static void searchInFile(Path filePath, Pattern pattern) throws IOException {
        List<String> lines = Files.readAllLines(filePath);
        boolean foundAny = false;

        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = pattern.matcher(lines.get(i));
            if (!matcher.find()) {
                continue;
            }

            if (!foundAny) {
                foundAny = true;
                System.out.println("\n【代码匹配】文件：" + filePath.getFileName());
                System.out.println("匹配位置：第 " + (i + 1) + " 行");
                System.out.println("上下文（共7行）：");

                int start = Math.max(0, i - 3);
                int end = Math.min(lines.size() - 1, i + 3);
                for (int j = start; j <= end; j++) {
                    String prefix = (j == i) ? ">>> " : "    ";
                    System.out.printf("%4d: %s%s%n", j + 1, prefix, lines.get(j));
                }
            } else {
                System.out.println("    ... 其他匹配项已省略");
                break;
            }
        }
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
}
