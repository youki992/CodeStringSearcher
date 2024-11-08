<h1 align="center">
  <b>CodeStringSearcher</b>
  <br>
</h1>
# 介绍
基于Java开发的代码字符串搜索工具，用于辅助快速代码审计，筛选危险方法名称搜索代码中可能存在的漏洞

# 使用说明
在config.properties中配置源码所在文件夹、源码输出文件夹、搜索字符串
分别对应配置字段：csDirectory、outputDirectory、searchText
```
csDirectory=C:\\Users\\xxxx
outputDirectory=C:\\Users\\xxxx\\output
searchText=where,Concat,...
```
searchText可以配置多个字符串关键字，并用,号进行分割
