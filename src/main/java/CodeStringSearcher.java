import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CodeStringSearcher {

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java CodeStringSearcher <configFilePath>");
            return;
        }

        String configFilePath = args[0];
        Properties props = new Properties();
        try (InputStream input = new FileInputStream(configFilePath)) {
            props.load(input);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        String csDirectory = props.getProperty("csDirectory");
        String outputDirectory = props.getProperty("outputDirectory");
        String searchText = props.getProperty("searchText");

        if (csDirectory == null || outputDirectory == null || searchText == null) {
            System.err.println("请在配置文件中提供有效的参数。");
            return;
        }

        try {
            Files.createDirectories(Paths.get(outputDirectory)); // 创建输出目录

            // 获取目录下的所有C#文件
            Path dirPath = Paths.get(csDirectory);
            List<Path> csFiles = Files.list(dirPath)
                    .filter(path -> path.toString().endsWith(".cs") || path.toString().endsWith(".java"))
                    .collect(Collectors.toList()); // 使用 collect(Collectors.toList())

            for (Path csFile : csFiles) {
                analyzeCode(csFile, searchText, outputDirectory);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void analyzeCode(Path csFile, String searchText, String outputDirectory) {
        try (BufferedReader br = new BufferedReader(new FileReader(csFile.toFile()))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }

            Map<String, List<String>> methodCallGraph = new HashMap<>();
            Map<String, Integer> methodLineNumbers = new HashMap<>();
            Set<String> methodsWithKeyword = new HashSet<>();
            Map<String, List<String>> methodContexts = new HashMap<>();

            // 将 searchText 分割成多个关键字
            String[] keywords = searchText.split(",");

            buildMethodCallGraph(lines, keywords, methodCallGraph, methodLineNumbers, methodsWithKeyword, methodContexts);

            if (!methodsWithKeyword.isEmpty()) {
                String outputFilePath = generateOutputFileName(csFile, searchText, outputDirectory);
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputFilePath))) {
                    for (String method : methodsWithKeyword) {
                        List<String> callChain = new ArrayList<>();
                        findCallChain(method, methodCallGraph, callChain, new HashSet<>());

                        bw.write("方法名: " + method + "\n");
                        bw.write("行号: " + methodLineNumbers.get(method) + "\n");
                        bw.write("调用链: " + String.join(" -> ", callChain) + "\n");
                        bw.write("上下文代码:\n" + String.join("\n", methodContexts.get(method)) + "\n");
                        bw.write("\n");
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void buildMethodCallGraph(List<String> lines, String[] keywords, Map<String, List<String>> methodCallGraph, Map<String, Integer> methodLineNumbers, Set<String> methodsWithKeyword, Map<String, List<String>> methodContexts) {
        boolean inMethod = false;
        String currentMethodName = null;
        List<String> methodLines = new ArrayList<>();

        // 正则表达式匹配方法定义
        Pattern methodPattern = Pattern.compile("(public|private|protected|internal)\\s+(static|async|virtual|override)?\\s*[^\\(]+\\([^)]*\\)\\s*\\{?");
        Pattern callPattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_\\.]*)\\s*\\(");

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            Matcher matcher = methodPattern.matcher(line);
            if (matcher.find()) {
                // 检测方法定义
                String methodSignature = matcher.group();
                int methodNameStart = methodSignature.indexOf(" ") + 1;
                int methodNameEnd = methodSignature.indexOf("(");
                if (methodNameStart != -1 && methodNameEnd != -1) {
                    currentMethodName = methodSignature.substring(methodNameStart, methodNameEnd);
                    inMethod = true;
                    methodLines.clear();
                    methodLineNumbers.put(currentMethodName, i + 1);
                }
            }

            if (inMethod) {
                methodLines.add(line);

                // 检查是否包含任何一个关键字
                for (String keyword : keywords) {
                    if (line.contains(keyword.trim())) {
                        methodsWithKeyword.add(currentMethodName);
                        methodContexts.put(currentMethodName, getMethodContext(lines, i));
                        break;
                    }
                }

                Matcher callMatcher = callPattern.matcher(line);
                while (callMatcher.find()) {
                    String calledMethod = callMatcher.group(1);
                    if (!calledMethod.contains(".")) {
                        methodCallGraph.computeIfAbsent(currentMethodName, k -> new ArrayList<>()).add(calledMethod);
                    }
                }
            }

            if (line.equals("}")) {
                inMethod = false;
            }
        }
    }

    private static List<String> getMethodContext(List<String> lines, int index) {
        int startIndex = Math.max(0, index - 5);
        int endIndex = Math.min(index + 5, lines.size());
        return lines.subList(startIndex, endIndex);
    }

    private static void findCallChain(String method, Map<String, List<String>> methodCallGraph, List<String> callChain, Set<String> visited) {
        if (visited.contains(method)) {
            return;
        }
        visited.add(method);
        callChain.add(method);

        List<String> calledMethods = methodCallGraph.getOrDefault(method, Collections.emptyList());
        for (String calledMethod : calledMethods) {
            findCallChain(calledMethod, methodCallGraph, callChain, visited);
        }
    }

    private static String generateOutputFileName(Path csFile, String searchText, String outputDirectory) {
        String baseName = csFile.getFileName().toString().replace(".cs", "");
        String keywordPrefix = searchText.split("\\s+")[0]; // 使用第一个词作为前缀
        return outputDirectory + File.separator + baseName + "_" + keywordPrefix + "_result.txt";
    }
}
