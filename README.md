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
原使用说明可以参考公众号文章：https://mp.weixin.qq.com/s/fRAnIqa8OieuTt2iLuKdCg?token=329619276&lang=zh_CN

注意！更新后请参考公众号新文章使用，原方法已不适用

在config.properties中配置源码所在文件夹、源码输出文件夹、搜索字符串
分别对应配置字段：csDirectory、outputDirectory、searchText
```
csDirectory=C:\\Users\\xxxx
outputDirectory=C:\\Users\\xxxx\\output
searchText=where,Concat,...
```
searchText可以配置多个字符串关键字，并用,号进行分割

指定配置文件执行jar文件后，在输出文件夹中对每个方法输出结果文件

每个文件中记录了方法名称和方法中的利用链，还会展示上下文5行代码，更加直观的显示

如下指定关键字为MapFilePath，搜索出相关代码的效果


# 更新日志
- 2024-11-15 新增文件夹下子文件夹遍历功能
- 2025-10-10 新增方法上下文侧写功能

**本工具由Code4th安全团队开发维护**

![image](https://ice.frostsky.com/2024/08/18/5559fc7abc47065e9e5e53a7dba2142b.jpeg)

**团队公开群**
- QQ群一群（772375860）

