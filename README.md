# 汐语 (Xiyu)

<p align="center">
  <strong>基于 LSPosed (libxposed API 100+) 的三星键盘与 Keys Cafe 增强模块</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%209.0%2B%20(One%20UI)-0381FE?logo=android&logoColor=white" alt="Platform" />
  <img src="https://img.shields.io/badge/Xposed-API%20100%2B%20(LSPosed)-4CAF50" alt="LSPosed" />
  <img src="https://img.shields.io/badge/Language-Kotlin%20%2F%20C%2B%2B-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin / C++" />
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License" />
</p>

---

## 项目简介

汐语是一款专为三星 One UI 系统键盘（HoneyBoard）与 Keys Cafe 定制的增强模块。在不修改系统 APK 与官方签名的前提下，通过运行时注入优化键盘交互限制，补充高频实用功能。

---

## 主要功能

### 1. 语音输入解除导航条绑定
- 原生三星键盘在关闭「在导航条上显示输入法按钮」时，会长按禁用空格与侧键语音。
- 本模块解除该限制，无需保留底部导航条小白条即可长按触发语音输入。

### 2. 语音长按即说（松手即停）
- 支持对讲机式交互：长按空格说话，松手立即结束录音并提交文本上屏。
- 优化长按录音界面的视觉波形，复用 Canvas 绘图对象避免频繁分配引起主线程掉帧。

### 3. 验证码识别与候选直推
- 解除系统对第三方应用的识别限制，自动从短信与应用通知提取验证码。
- 绕过键盘内部门控，直接将提取到的验证码展示在候选栏首位，点击即可填入。
- 纯本地规则解析与安全过滤（自动排除手机号、卡号、金额等干扰），通过 InputConnection 上屏，不经由剪贴板。

### 4. 打字即算（算式 / 大写金额 / 进制换算）
- **数学算式**：输入算式以等号结尾（如 `128*34=`、`(12+8)/4=`），候选栏即时展示计算结果。
- **财务大写**：追加 `dx=` 或 `大写=`（如 `3500dx=`、`128大写=`），自动转换为大写汉字（如 `叁仟伍佰元整`）。
- **程序员进制**：支持前缀（`0xFF=`、`0b1010=`）与后缀（`255hex=`、`10bin=`）在十进制与二进制/八进制/十六进制之间互相转换。
- **精准上屏**：点击结果候选直接填入输入框，底层由 C++ 原生计算求值，并自动过滤日期、IP 与普通纯数字。

### 5. 本地中文词库与统计语言模型
- 内置 92 万词条与 N-gram 统计语言模型，下沉至 C++ 原生动态库（`libxycore.so`）。
- 采用 Darts 双数组字典树（Double-Array Trie）实现快速检索，支持全拼精确匹配与拼音简拼映射。
- 采用只读内存映射（mmap）按需调页，不占用 JVM 堆内存。
- 支持基于本地输入习惯的自学习记忆，可在管理面板中随时重置。

### 6. Keys Cafe 布局必填按键解锁
- 解决 Keys Cafe 在自定义键盘布局时强制保留空格、退格、回车等星标按键的限制。
- 允许自由删除或替换任意按键，设计极简与个性化排布。

### 7. 三星键盘隐藏开发者选项
- 直接开启三星键盘内置的 Developer Options 调试面板，方便查看按键落点、防误触边界与内部状态。

---

## 技术架构

- **现代化 Hook 架构**：基于 libxposed API 100+ 规范开发，兼容主流 LSPosed 运行环境。
- **16 KB 页面对齐**：Native 动态库与构建产物适配 Android 15/16 严格 16 KB 边界对齐规范。
- **输入热路径低开销**：非中文输入（如英文键盘）或功能关闭时采用快速路径短路，直接跳过拦截逻辑，不拖慢按键输入响应速度。
- **C++ Native 核心下沉**：算式求值、高频字符正则过滤与 Trie 词库检索下沉至 Native 层，减少 JNI 开销与避免反编译直接泄露业务逻辑。

---

## 运行与安装

### 系统要求
1. 已获取 Root 权限的三星设备（KernelSU / Magisk / APatch）；
2. One UI（Android 9.0+ / API 28+）；
3. 已安装并激活 **LSPosed 框架**。

### 启用步骤
1. 安装 `汐语.apk`；
2. 打开 LSPosed 管理器，启用「汐语」模块；
3. 勾选作用域：
   - `com.samsung.android.honeyboard`（三星键盘）
   - `com.samsung.android.keyscafe`（Keys Cafe，使用自定义布局功能时勾选）
   - `com.samsung.android.smartsuggestions`（系统智能建议，使用验证码提取时勾选）
4. 重启三星键盘生效。

---

## 编译构建

### 1. 构建 Release APK
```bash
./gradlew assembleRelease
# 产物位置: app/build/outputs/apk/release/app-release.apk
```

### 2. 词库与模型构建说明
仓库代码不包含编译生成的二进制词库文件 `app/src/main/cpp/xy_embedded.bin`（约 54 MB）。如需自行构建内置数据：

```bash
# 编译构建工具
g++ -O2 -std=c++17 tools/build_embedded_data.cpp -o build_embedded_data

# 生成内置词库
./build_embedded_data \
  --dict-dir /path/to/cn_dicts \
  --gram /path/to/wanxiang-lts-zh-hans.gram \
  --profile standard --max-cands 12 --word-prior-limit 300000 \
  --out app/src/main/cpp/xy_embedded.bin --stats
```

> **注意**：Native 层包含发布证书指纹校验逻辑。若使用自定义签名证书，请运行 `python3 tools/update_expected_cert.py <keystore> <alias> <storepass>` 更新指纹常量后再行打包。

---

## 免责声明

1. 本项目仅供技术学习与个人交流使用；
2. 所有功能均基于运行时内存注入实现，未篡改任何系统应用原始安装包；
3. 请合理使用实验性功能，作者不对配置丢失或输入异常承担连带责任。

---

## 许可证与致谢

本项目遵循 [GNU General Public License v3.0](LICENSE) 开源协议。

词库与语言模型数据源致谢：
- rime-ice（GPL-3.0）：基础与扩展拼音词库
- rime-wanxiang（CC BY 4.0）：补充词库与语言模型转移权重
- chinese-ime-lm（GPL-3.0）：语言模型训练参考
