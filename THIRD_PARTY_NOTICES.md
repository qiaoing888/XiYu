# 第三方组件与许可

本项目包含以下第三方代码/数据。正式发布时请在分发包中保留本文件及对应许可文本。

| 组件 | 位置 | 许可 | 说明 |
|---|---|---|---|
| Darts-clone | `app/src/main/cpp/darts.h` | BSD-2-Clause | 双数组 Trie 实现，作者 Susumu Yata；许可文本已保留在源文件头部 |
| libxposed API / Service | `app/libs/libxposed-*.jar` | Apache-2.0 | Xposed 模块接口 |
| AndroidX / Material Components | Gradle 依赖 | Apache-2.0 | Android UI 基础库 |
| Kotlin / kotlinx-coroutines | Gradle 依赖 | Apache-2.0 | Kotlin 运行时 |
| rime-ice 词库（base / ext / 8105 / 41448 / tencent / others） | 构建期输入，见 `tools/build_embedded_data.cpp` | GPL-3.0 | 基础/扩展词库与多音字数据；https://github.com/iDvel/rime-ice |
| rime-wanxiang 词库 | 构建期输入，见 `tools/build_embedded_data.cpp` | CC BY 4.0 | 补充词库数据，已做格式转换与裁剪；https://github.com/amzxyz/rime-wanxiang |
| chinese-ime-lm | 构建期输入，见 `tools/build_embedded_data.cpp` | GPL-3.0 | N-gram 语言模型训练数据，已转换为本地二进制格式；https://github.com/metasequoiaime/chinese-ime-lm |

## 数据来源署名

本项目内置词库与语言模型由上述项目的数据构建而来：

- **rime-ice** — Copyright (c) iDvel 及贡献者，GPL-3.0；
- **rime-wanxiang** — Copyright (c) amzxyz 及贡献者，CC BY 4.0（署名要求：保留本文件中的项目名称、链接与许可说明）；
- **chinese-ime-lm** — Copyright (c) metasequoiaime 及贡献者，GPL-3.0。

数据在构建阶段经过字段裁剪、格式转换与二进制打包，未改变原始词条的语义内容。分发本项目的二进制或源码时，请一并保留本文件及 `LICENSE`。

> 发布前请补齐各组件完整许可文本，并确认上述数据与项目整体 GPL-3.0 的兼容性。
