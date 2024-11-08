<h1 align="center">
  <b>CodeStringSearcher</b>
  <br>
</h1>
<center>
代码审计辅助工具
</center>

# 工具介绍
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
指定配置文件执行jar文件后，在输出文件夹中对每个方法输出结果文件
![image](https://img.picui.cn/free/2024/11/08/672d7a91a5abc.png)
![image](https://img.picui.cn/free/2024/11/08/672d7c4359d33.png)

每个文件中记录了方法名称和方法中的利用链，还会展示上下文5行代码，更加直观的显示
![image](https://img.picui.cn/free/2024/11/08/672d7b3f2aea5.png)
