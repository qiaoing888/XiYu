# 汐语 (Xiyu)

<p align="center">
  <strong>专为三星 One UI / 三星键盘打造的高级体验增强模块</strong>
  <br>
  <em>基于现代化 libxposed API 101/102 架构构建</em>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%209.0%2B%20(One%20UI)-0381FE?logo=android&logoColor=white" alt="Platform" />
  <img src="https://img.shields.io/badge/Xposed-API%20101%2F102%20(LSPosed)-4CAF50?logo=github&logoColor=white" alt="LSPosed" />
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License" />
</p>

---

## 简介

**汐语（Xiyu）** 是一款轻量、优雅且高性能的三星键盘（HoneyBoard）与 Keys Cafe 增强模块。

在不修改系统 APK、不破坏官方平台签名、不牺牲系统稳定性的前提下，利用现代 LSPosed 运行时注入技术，打破官方在按键布局、导航条强绑定、语音交互与隐藏功能上的诸多限制，带来更纯粹、随心所欲的输入体验。

---

## 核心特性

### 1. 自由语音解锁（彻底摆脱小白条）
- **痛点**：三星官方键盘要求必须开启「在导航条上显示输入法按钮」，才允许长按空格或侧键调用语音输入，导致导航栏被迫常驻多余的小白条/切换按键。
- **解锁**：即使完全关闭导航条按钮，长按空格键或侧键照样直接触发语音输入，保持屏幕底部极简美感。

### 2. 灵动丝绸动画 & 空格松手即停
- **水面与极光丝绸**：长按说话时，键盘平滑化开成通透水幕，三道根据麦克风音量实时起伏的丝绸流体在中央交织绽放。
- **日夜独立光学调校**：
  - *浅色模式*：清透粉彩与日光暖橙，轻盈呼吸；
  - *深色模式*：午夜蓝黑幕底配以饱和星云紫、冷光月白与暮光玫粉，告别黑底泛灰或杂色光圈。
- **松手即停（Push-to-Talk）**：重塑三星原版“长按开、再按停”的繁琐交互，支持像对讲机一样长按说话、松手立即停止识别并上屏。
- **极致性能**：Canvas 绘制层实现 **100% 零内存分配（Zero GC）**，高刷新率下丝滑流畅，绝不引起掉帧。

### 3. 验证码自动识别与键盘候选直推
- **全应用支持**：解除系统智慧建议（SmartSuggestions）对浏览器应用白名单的限制，在任意第三方 App 的登录与输入界面均可自动提取验证码。
- **键盘候选直推**：绕过三星键盘内部门控逻辑，将验证码直接推送到输入法候选栏，点击即可直接填入输入框。
- **保护隐私**：全流程基于内存传递与输入法 InputConnection 提交，不写入系统剪贴板，避免敏感验证码被后台读取。
- **本地规则引擎**：内置本地启发式解析规则与负向过滤（自动过滤手机号、卡号、金额、时间、快递单号与客服热线等干扰），无后台常驻保活；仅在打开设置页时访问 GitHub Release 检查版本更新，不涉及任何输入内容。

### 4. 极简打字即算（数学算式 / 财务大写 / 程序员进制换算）
- **微信键盘同款无感体验**：打字时直接输入数学算式并以等号结尾（如 `128*34=`、`200+50-10=`、`(12+8)/4=`），候选栏首位即时计算并呈现结果胶囊（如 `= 4352`）。
- **财务大写金额转换**：支持追加 `dx=` 或 `大写=` 触发财务大写（如 `3500dx=`、`128大写=`），直接秒变 `叁仟伍佰元整`。
- **程序员进制即时换算**：支持前缀输入（`0xFF=`、`0b1010=`、`0o77=` -> 转为十进制）与后缀输入（`255hex=`、`10bin=`、`63oct=` -> 十进制转目标进制）。
- **一键极速上屏**：点击结果候选直接上屏；支持在设置中自由切换「替换原算式」或「追加到等号后」。
- **高精度与智能防误触**：C++ 原生高精度求值，彻底告别浮点精度误差；自动排除日期、IP 地址、时间、手机号与单数字，全程零干扰。

### 5. 汐语专属智能语言模型与核心引擎（Darts 双数组字典树 + 本地自学习记忆）
- **痛点**：系统键盘本地词库老旧闭塞、缺乏上下文记忆，离线或弱网下特定短语与简拼极难选词。
- **纯原生内联核心引擎**：92 万词条与统计语言模型完全编译内联进 C++ 原生动态库（`libxycore.so`），开箱即用。
- **Darts 高紧凑双数组 Trie 树**：收录 920,925 词条及 1,085,087 拼音/缩写简拼映射（支持 `srkl` -> `生日快乐` 等高频简拼直推）。
- **零物理内存占用（Zero-RAM mmap）**：核心模型与词库采用 Linux 内核级页面对齐只读内存映射，按需调页，不常驻物理运存，无 OOM 风险。
- **动态就绪与状态透视**：管理面板直观呈现 `● 已就绪` 状态胶囊；点击即可调出全景技术指标诊断卡片。
- **本地自学习与一键清空**：深度记忆个人输入习惯，越用越准；支持管理面板一键安全清空自学模型与词库重置。

### 6. Keys Cafe 星标按键自由布局
- **痛点**：Keys Cafe 的“设计属于自己的键盘”功能强制要求某些按键必须保留（带有星标符号，如空格、退格、回车等），否则无法保存自定义布局。
- **解锁**：一键绕过必填按键校验，允许任意删除或替换任何星标按键，随心打造真正极致的极简、单手或个性化按键排布。（*即开即用：改布局保存时开启几秒，改完即可关闭*）

### 7. 三星键盘实验室（Developer Options）
- **解锁**：直接解锁三星键盘内部隐藏的开发者选项面板，无需通过关于页面连续点击触发。
- **包含 33 项内部调试参数**：按键模型落点可视化、防误触区域调试、动态词库导出、语音识别服务模式切换等。

### 8. Apple HIG 极简双模设计美学
- **双模自适应**：深度适配浅色与深色（午夜黑）主题，语义化色彩 Token 严格遵循 Apple HIG 设计指南；
- **全代码矢量化**：全套微图标采用纯手写 VectorDrawable 贝塞尔曲线，告别位图模糊与缩放失真；
- **内置体验沙盒**：主界面直载打字即算、财务大写与进制换算交互沙盒，无需切出应用即可实时体验。

---

## 架构与技术亮点

- **现代 libxposed API 101/102 架构**：
  告别已废弃的旧版 Legacy 钩子体系，消除 LSPosed 管理器中的黄色废弃警告与角标。
- **全面适配 Android 15/16 16 KB 页面对齐**：
  Native C++ 动态库配置 `-Wl,-z,max-page-size=16384`，构建流水线自动执行严格 16 KB 边界 `zipalign` 对齐，彻底消除系统兼容性警告。
- **零打字延迟（Zero-Overhead Fast Path）**：
  打字高频路径采用只读内存镜像短路机制（O(1) 无锁读取），功能关闭时直接跳过拦截，绝不拖慢日常敲击与打字跟手度。
- **关键数据 JNI 加固**：
  核心算式解析、验证码正则、词库索引与包名拦截均下沉至 C++ 原生层，实现零 GC 与防逆向加固。

---

## 安装与使用指南

### 前置环境要求
1. 已 Root 的三星手机（支持 KernelSU、Magisk 或 APatch）；
2. 运行环境：One UI（Android 9.0+ / API 28+，已在最新的 Android 15 / 17 系统及 S25 系列实测兼容）；
3. 已激活 **LSPosed 框架**（推荐 v2.0.1+ / API 101+ 版本）。

### 使用步骤
1. 下载并安装最新版 `汐语.apk`；
2. 打开 LSPosed 管理器，在模块列表中找到并勾选**「汐语」**；
3. 确认作用域已勾选：
   - `com.samsung.android.honeyboard`（三星键盘）
   - `com.samsung.android.keyscafe`（Keys Cafe）
   - `com.samsung.android.smartsuggestions`（三星智能建议）
4. 在 LSPosed 中重新加载模块，或重启输入法生效；
5. 打开「汐语」App，根据个人需求开启对应功能开关。

---

## 本地编译构建

本项目使用标准 Gradle 构建系统：

```bash
# 1. 克隆本仓库
git clone https://github.com/qiaoing888/XiYu.git
cd XiYu

# 2. 编译 Release APK
./gradlew assembleRelease

# 3. 产物路径
# app/build/outputs/apk/release/app-release.apk
```

---

## 从源码构建

仓库只包含源代码，不包含 `app/src/main/cpp/xy_embedded.bin`（内置词库与语言模型，约 54 MB 的构建产物）。首次构建前需要自行生成：

1. 准备数据（许可与署名见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)）：
   - [rime-ice](https://github.com/iDvel/rime-ice)（GPL-3.0）— 基础/扩展词库
   - [rime-wanxiang](https://github.com/amzxyz/rime-wanxiang)（CC BY 4.0）— 补充词库与 `.gram` 模型
   - [chinese-ime-lm](https://github.com/metasequoiaime/chinese-ime-lm)（GPL-3.0）— 语言模型数据

2. 生成内置数据（当前发布档为 `standard` slim）：

```bash
g++ -O2 -std=c++17 tools/build_embedded_data.cpp -o /tmp/build_embedded_data

/tmp/build_embedded_data \
  --dict-dir /path/to/cn_dicts \
  --gram /path/to/wanxiang-lts-zh-hans.gram \
  --profile standard --max-cands 12 --word-prior-limit 300000 \
  --out app/src/main/cpp/xy_embedded.bin --stats
```

3. 构建 APK（JDK 17 + Android SDK/NDK）：

```bash
cp keystore.properties.example keystore.properties   # 填入自己的签名信息
JAVA_HOME=/path/to/jdk-17 ./gradlew :app:assembleRelease
```

> **签名说明**：native 层内置了发布证书指纹。使用自己的 keystore 构建时，需要通过
> `python3 tools/update_expected_cert.py <keystore> <alias> <storepass>` 同步指纹，
> 否则应用完整性校验会拒绝运行。源码按 GPL-3.0 提供，可自行修改与重新构建。

---

## 免责声明

1. 本项目仅供 Android 逆向工程、Hook 机制探究及个人审美交流学习使用；
2. 本项目未篡改任何官方二进制安装包，所有功能均通过运行时内存挂钩实现；
3. 请合理使用实验性功能，作者不对由于不当使用造成的键盘配置丢失或输入法异常承担责任。

---

## 开源许可证

本项目基于 [GNU General Public License v3.0](LICENSE) 协议开源。

词库与语言模型数据来自以下开源项目，在此致谢：

| 项目 | 许可 | 用途 |
|---|---|---|
| [rime-ice](https://github.com/iDvel/rime-ice) | GPL-3.0 | 基础词库、扩展词库与简繁/多音字数据 |
| [rime-wanxiang](https://github.com/amzxyz/rime-wanxiang) | CC BY 4.0 | 补充词库数据（已做格式转换与裁剪） |
| [chinese-ime-lm](https://github.com/metasequoiaime/chinese-ime-lm) | GPL-3.0 | N-gram 语言模型训练数据（已转换为本地二进制格式） |

以上数据仅用于构建内置只读词库与语言模型，构建产物随本项目一同按 GPL-3.0 分发；CC BY 4.0 数据的署名见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
