import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.jar.*;
import java.util.zip.*;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;

public class JarMethodSearcher {

    public static void main(String[] args) {
//        if (args.length != 2) {
//            System.err.println("Usage: java -jar JarMethodFinder.jar <folderPath> <keyword>");
//            return;
//        }
//
//        String folderPath = args[0];
//        String keyword = args[1];
        String folderPath = "F:\\yonyou-modules-2\\modules\\aedsm\\lib";
        String keyword = "loadAttributes";

        try {
            List<Path> jarFiles = findJarsRecursively(folderPath);
            System.out.printf("找到 %d 个 .jar 文件，正在搜索关键字 \"%s\"...\n", jarFiles.size(), keyword);

            // 获取 CPU 核心数作为线程池大小
            int threadCount = Runtime.getRuntime().availableProcessors();
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            // 并行处理每个 JAR
            for (Path jarFile : jarFiles) {
                Path finalJarFile = jarFile;
                executor.submit(() -> {
                    try {
                        System.out.println("\n🔍 正在处理 JAR：" + finalJarFile.getFileName());
                        searchInJar(finalJarFile.toString(), keyword);
                    } catch (Exception e) {
                        System.err.println("⚠️ 处理失败：" + finalJarFile.getFileName());
                    }
                });
            }

            executor.shutdown();
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 递归查找所有 .jar 文件
    private static List<Path> findJarsRecursively(String rootDir) throws IOException {
        List<Path> jars = new ArrayList<>();
        Path rootPath = Paths.get(rootDir);

        if (!Files.exists(rootPath)) {
            System.err.println("路径不存在：" + rootDir);
            return jars;
        }

        Files.walk(rootPath)
                .filter(path -> path.toString().toLowerCase().endsWith(".jar"))
                .forEach(jars::add);

        return jars;
    }

    // 主流程：字节码扫描 + 反编译搜索双模式
    private static void searchInJar(String jarPath, String keyword) throws Exception {
        Pattern pattern = Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);

        Path tempDir = Files.createTempDirectory("decompiled_");

        // Step 1: 字节码扫描，获取命中的类名
        List<String> matchedClassEntries = scanBytecodeForMethods(jarPath, keyword);

        if (matchedClassEntries.isEmpty()) {
            System.out.println("⚠️ 未找到匹配项");
            deleteDirectory(tempDir);
            return;
        }

        // Step 2: 提取命中的 .class 文件
        List<Path> classFiles = extractOnlyMatchedClasses(jarPath, matchedClassEntries, tempDir);

        // Step 3: 只反编译命中的类
        List<Path> javaFiles = decompileWithCFR(classFiles, tempDir);

        // Step 4: 搜索源码上下文
        for (Path javaFile : javaFiles) {
            searchInFile(javaFile, pattern);
        }

        deleteDirectory(tempDir);
    }

    // 新增方法：只提取命中的类文件
    private static List<Path> extractOnlyMatchedClasses(String jarPath, List<String> matchedEntries, Path destDir) throws IOException {
        List<Path> classFiles = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jarPath)) {
            for (String entryName : matchedEntries) {
                JarEntry entry = jarFile.getJarEntry(entryName);
                if (entry != null) {
                    Path target = destDir.resolve(entryName);
                    Files.createDirectories(target.getParent());
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    classFiles.add(target);
                }
            }
        }
        return classFiles;
    }

    // 从字节码中提取方法名和签名（支持接口方法）
    private static List<String> scanBytecodeForMethods(String jarPath, String keyword) throws Exception {
        List<String> matchedClasses = new ArrayList<>();

        try (JarFile jar = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        JavaClass clazz = new ClassParser(is, entry.getName()).parse();

                        for (Method method : clazz.getMethods()) {
                            String methodName = method.getName();
                            String signature = method.getSignature();
                            if (methodName.toLowerCase().contains(keyword.toLowerCase())) {
                                System.out.println("【字节码匹配】" + clazz.getClassName() + "." + methodName + signature);
                                System.out.println("    来自：" + jarPath);
                                matchedClasses.add(entry.getName()); // 记录命中的类路径
                            }
                        }
                    }
                }
            }
        }

        return matchedClasses;
    }

    // 使用 CFR 反编译 .class 文件为 .java
    private static List<Path> decompileWithCFR(List<Path> classFiles, Path outputDir) throws Exception {
        List<Path> javaFiles = new ArrayList<>();

        for (Path classFile : classFiles) {
            String className = classFile.toString().replace(".class", "");
            String cfrPath = "C:\\Users\\13740\\Downloads\\cfr-0.152.jar";
            ProcessBuilder pb = new ProcessBuilder("java", "-jar", cfrPath, classFile.toString(), "--outputdir", outputDir.toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                // 可选打印日志
            }

            Path javaFile = outputDir.resolve(className + ".java");
            if (Files.exists(javaFile)) {
                javaFiles.add(javaFile);
            } else {
                System.err.println("⚠️ 反编译失败：" + classFile.getFileName());
            }
        }

        return javaFiles;
    }

    // 在反编译后的 .java 文件中搜索关键字并显示上下文
    private static void searchInFile(Path filePath, Pattern pattern) throws IOException {
        List<String> lines = Files.readAllLines(filePath);
        boolean found = false;

        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = pattern.matcher(lines.get(i));
            if (matcher.find() && !found) {
                found = true;

                System.out.println("\n【代码匹配】文件：" + filePath.getFileName());
                System.out.println("匹配位置：第 " + (i + 1) + " 行");
                System.out.println("上下文（共6行）：");

                int start = Math.max(0, i - 3);
                int end = Math.min(lines.size() - 1, i + 3);
                for (int j = start; j <= end; j++) {
                    String prefix = (j == i) ? ">>> " : "    ";
                    System.out.printf("%4d: %s%s%n", j + 1, prefix, lines.get(j));
                }

            } else if (matcher.find() && found) {
                System.out.println("    ... 其他匹配项已省略");
                break;
            }
        }
    }

    // 清理临时目录
    private static void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {}
                    });
        }
    }
}