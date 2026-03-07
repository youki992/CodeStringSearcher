<h1 align="center">
  <b>CodeStringSearcher</b>
  <br>
</h1>
<p align="center">
<a href="https://github.com/youki992/CodeStringSearcher/issues"><img src="https://img.shields.io/badge/contributions-welcome-brightgreen.svg?style=flat"></a>
<a href="https://github.com/youki992/CodeStringSearcher"><img alt="Release" src="https://img.shields.io/badge/LICENSE-BSD-important"></a>
<a href="https://github.com/youki992/CodeStringSearcher/releases"><img src="https://img.shields.io/github/release/youki992/CodeStringSearcher"></a>
<a href="https://github.com/youki992/CodeStringSearcher/releases"><img src="https://img.shields.io/github/downloads/youki992/CodeStringSearcher/total?color=blueviolet"></a>
</p>

# 工具介绍
基于Java开发，用于辅助快速代码审计，筛选危险方法名称搜索代码中可能存在的漏洞

~~（支持审计.cs后缀和.java后缀的源码）~~

新版本砍掉了原先的功能，只支持批量搜索文件夹下的jar文件反编译分析

# 使用说明

## 1) 命令行模式

```bash
java -jar JarMethodFinder-1.0-SNAPSHOT.jar <jarDirectory> <keywords> [cfrJarPath] [outputDir]
```

- `jarDirectory`: 待审计 JAR 根目录（会递归扫描）
- `keywords`: 搜索关键字，支持逗号分隔多个，例如：`exec,Runtime.getRuntime,ProcessBuilder`
- `cfrJarPath`（可选）: CFR 反编译器路径，不传则读取环境变量 `CFR_JAR_PATH`，再兜底 `./cfr.jar`
- `outputDir`（可选）: 导出目录（用于 JSON/TXT 结果导出）

示例：

```bash
java -jar JarMethodFinder-1.0-SNAPSHOT.jar /data/jars "exec,Runtime.getRuntime" /tools/cfr.jar /tmp/search-output
```

## 2) config.properties 模式（推荐）

```bash
java -jar JarMethodFinder-1.0-SNAPSHOT.jar --config config.properties
# 或
java -jar JarMethodFinder-1.0-SNAPSHOT.jar config.properties
```

配置示例：

```properties
jarDirectory=/data/jars
keywords=exec,Runtime.getRuntime,ProcessBuilder
cfrJarPath=/tools/cfr.jar
outputDir=/tmp/search-output
outputFormat=all
caseSensitive=false
contextLines=3
```

字段说明：

- `jarDirectory`: JAR 扫描目录
- `keywords`: 关键字列表（逗号分隔）
- `cfrJarPath`: CFR 路径（可选）
- `outputDir`: 导出目录（可选）
- `outputFormat`: `txt` / `json` / `all`
- `caseSensitive`: 是否大小写敏感，默认 `false`
- `contextLines`: 匹配上下文行数（上下各 N 行），默认 `3`


# 更新日志
- 2024-11-15 新增文件夹下子文件夹遍历功能
- 2025-10-10 新增方法上下文侧写功能
- 2026-03-06 优化JAR审计流程：
  - 支持跨平台CFR路径配置（第三参数或环境变量 `CFR_JAR_PATH`）
  - 修复反编译输出路径处理，减少误报“反编译失败”
  - 新增 `config.properties` 模式
  - 新增结果导出（`JSON/TXT/all`）
  - 新增多关键字逗号分隔搜索
  - 优化匹配输出逻辑与去重，提升稳定性

**本工具由Code4th安全团队开发维护**

![image](https://ice.frostsky.com/2024/08/18/5559fc7abc47065e9e5e53a7dba2142b.jpeg)

**团队公开群**
- QQ群一群（772375860）

