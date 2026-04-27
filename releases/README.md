# Releases Archive

本目录用于保留 `lottery` 插件已经构建过的历史版本文件。

`target/` 是 Maven 构建临时目录，可能被清理；`releases/` 是人工归档目录，历史版本应保留在这里。

## 当前归档

| 目录 | 状态 | 说明 |
|---|---|---|
| `1.0.0/` | 历史初版 | 第一版可运行插件 |
| `1.0.1/` | 生产稳定版 | 当前主分支成熟版 |
| `1.1.0-test/` | 测试版 | 节日公益活动增量测试版 |

## 部署说明

通常部署不带 `original-` 前缀的 jar：

```text
lottery-<version>-SNAPSHOT.jar
```

不要部署：

```text
original-lottery-<version>-SNAPSHOT.jar
```

`original-*` 是 Maven shade 前的原始 jar，不包含运行所需的打包依赖。
