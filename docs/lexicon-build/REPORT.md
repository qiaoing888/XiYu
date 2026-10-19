# 词库构建与注音验证报告

> 日期：2026-10-20
> 工具：`tools/build_embedded_data.cpp`（已从一次性脚本改为参数化构建工具）
> 词库源：`/home/king/桌面/词库/cn_dicts`（rime-ice，GPL-3.0）
> 语法模型：`/home/king/桌面/词库/wanxiang-lts-zh-hans.gram`

## 一、构建工具改动

- 去掉所有 `/home/king/下载/...` 硬编码，改为命令行参数。
- 支持 `--profile core|standard|full`、`--format emb|dict`、`--out`、`--max-cands`、
  `--word-prior-limit`、`--tencent-limit`、`--accuracy-sample`、`--stats`。
- 合并五库：8105 / 41448 / base / ext / others / tencent。
- tencent 981,543 条无注音词全部自动注音。
- 弱先验：tencent / others 写入权重固定为 1，排序时永远排在真实词频之后。
- 索引：全拼键覆盖全部词；简拼键覆盖全部词（2～8 字母）；混拼键只给真实词频前 10 万；
  每键候选上限默认 16。
- 输出兼容现有运行期格式：`XYEMB001`（内置）与 `XYDICT01`（外置扩展档）。

### 自动注音算法

单纯“每字取最高频读音”在留出集上只有 **90.33%**，错误集中在多音字上下文
（地 de/di、行 hang/xing、乐 le/yue、长 chang/zhang、传 chuan/zhuan…）。

因此增加了一层**多音字上下文模型**：

1. 用 base/ext 自带注音作为训练数据，按词哈希 80/20 划分训练集与留出集；
2. 对每个多音字统计：`字 + 前字 + 后字`、`字 + 前字`、`字 + 后字`、`字` 四种上下文
   与读音的共现次数；
3. 注音时对每个候选读音打分
   `4·log1p(三元) + 1.5·log1p(左) + 1.5·log1p(右) + 0.5·log1p(字频)`；
4. 词长 ≤4 时保留得分前 2 的读音做笛卡尔积，最多 4 个变体；
5. 字表外字符直接丢弃并计数。

留出集（5,000 词，未参与训练）结果：

| 指标 | 结果 |
|---|---:|
| top-1 准确率 | **98.78%** |
| 变体命中率 | **99.72%** |
| 多音字词占比 | 45.98% |
| 字表外无法注音 | 4 / 5000（按错误计入分母） |

> 注：生产模型用全部 base/ext 注音训练，覆盖比留出集模型更全。
> 上下文模型只学习相邻字统计，不记忆整词，对 tencent 新词同样适用。
> 2026-10-20 修正了 8105/41448 合并逻辑，补回 503 个丢失读音（如 那 nei/ne/nuo、转 zhuai），
> 多音字从 4,452 增至 4,854；准确率分母改为包含无法注音词。

## 二、产出与体积

测得 APK 影响基于当前 release APK（20.4 MB）与旧 `xy_embedded.bin`（16.2 MB，`.`so` 内 Stored 不压缩）。

| 档位 | 词条 | 拼音键 | 候选 | bin 原始 | gzip | 预估 APK | 构建耗时 |
|---|---:|---:|---:|---:|---:|---:|---:|
| **A core**（字表+base） | 582,756 | 631,052 | 972,316 | 36.6 MB | 21.6 MB | **40.6 MB** | 5.8 s |
| **B standard**（A+ext） | 920,925 | 1,085,087 | 1,586,283 | 58.7 MB | 32.3 MB | **62.7 MB** | 7.3 s |
| **B slim**（候选 12 + 词先验前 30 万） | 920,925 | 1,085,087 | 1,549,794 | 53.8 MB | 29.1 MB | **57.8 MB（实测 release）** | 13.5 s |
| **D**（B+tencent 前 30 万） | 1,220,720 | 1,538,186 | 2,206,938 | 79.4 MB | 41.8 MB | **84.7 MB** | 9.5 s |
| **E full**（B+tencent 全部） | 1,902,166 | 2,533,530 | 3,566,327 | 124.8 MB | 62.5 MB | **128.8 MB** | 14.9 s |

产物位置：

```
docs/lexicon-build/core_A.emb.bin
docs/lexicon-build/standard_B.emb.bin
docs/lexicon-build/standard_B.dict.bin
docs/lexicon-build/standard_D.emb.bin
docs/lexicon-build/full_E.emb.bin
```

### 与原方案估算的差异

- A：36.6 MB，原估算约 33 MB，基本一致。
- B：58.7 MB，原估算约 52.5 MB（+12%）。
- E：124.8 MB，原估算约 96.6 MB（+29%），APK 约 129 MB，**不建议直接内置**。

超出的主要来源：

1. 简拼键从“前 2.5 万双字词”扩到**全部词**；
2. 每键候选 12 → 16；
3. tencent 多音字变体（最多 4 个）同时生成全拼与简拼键；
4. 词先验 Trie 默认保留全部词；B slim 把它限制到前 30 万后省约 6.5 MB。

### 体积调节杠杆

| 参数 | 作用 | 参考收益 |
|---|---|---|
| `--word-prior-limit 300000` | 词先验只保留高频 30 万词 | B 约 -6.5 MB |
| `--max-cands 12` | 候选上限回到 12 | B 约 -0.8 MB |
| `--tencent-limit N` | 只取 tencent 前 N 条 | D 介于 B 与 E 之间 |
| `--profile core` | 不加载 ext/tencent | 最小档 |

> 词先验只用于整句解码重排；长尾词给 0 分是方案里已接受的取舍。

## 三、验证状态

已完成：

- 输出头与偏移全部通过结构校验（magic、对齐、越界检查）；
- 注音准确率留出集验证通过（≥95% 门槛）；
- tencent 自动注音路径冒烟通过（50,000 条：噪声丢弃 70，无法注音 0）；
- **Phase 2 运行期改造完成**并已真机验证。

### Phase 2：双层词典 + 前缀检索（2026-10-20）

`xy_lm.cpp` 的改动：

1. **双层词典**：内置 `core`（`.so` 的 `.rodata`）与可选 `ext`
   （`filesDir/xy_dict.bin`，`XYDICT01`）。查询时先 core 后 ext，
   按权重合并、按词去重；同权重时 core 优先。
2. **外置层真正生效**：旧代码在内置词库已加载时永远不会读 `xy_dict.bin`；
   现在启动时无条件尝试加载扩展层，文件删除/停用时也会卸载。
3. **前缀检索**：`traverse` 定位前缀节点 + 有界 DFS（≤4096 节点 / ≤2000 键），
   仅当输入 ≥2 且精确/模糊候选不足时启用，每键最多取 4 个候选。
4. **词先验**：`query_dict_word_prior` 同时查两层，取较大权重。
5. UI：核心引擎对话框改为显示真实的词条/拼音键数，不再写死 20.7 万。

验证结果：

| 场景 | 结果 |
|---|---|
| 输入 `beij` | 候选栏：北京 背景 北极 悲剧 北街 被禁 |
| `dz` 快捷短语 | 首位「北京市海淀区某某大厦」，上屏后候选栏清空 |
| @上文 | 提交「没问题」后推送「你决定吧，我都可以。」到渲染槽位，点击后清空 |
| 整句解码 | `woaibeijing` → 「我爱北京」入池 |
| 词库状态 | 对话框显示 920,925 词条 / 1,085,087 拼音键 |
| 外置层 | host 验证：加载 D 档 ext 后能命中 tencent-only 词「一代女皇武则天」 |

### 覆盖率实测（每类随机 1000 词，全拼键排名）

| 档位 / 来源 | top-1 | top-3 | 进入候选块 | 未进入 |
|---|---:|---:|---:|---:|
| B slim / ext | 98.4% | **100%** | 100% | 0 |
| D / ext | 98.4% | **100%** | 100% | 0 |
| D / tencent | 96.7% | **99.9%** | 100% | 0 |

目标为 top-3 ≥95%，全部通过。

### 性能（初测）

- **词典查询延迟**（x86 host，B slim，`query_dict_pinyin` 2,400 次，
  含精确/模糊/前缀路径）：
  - P50 **0.26 µs**
  - P95 **310 µs**
  - P99 **329 µs**
  - max 609 µs
  - 远低于 5 ms 目标；真机未单独打点，但同一套代码路径。
- **键盘进程内存**（B slim，真机 `dumpsys meminfo com.samsung.android.honeyboard`）：
  - Native Heap PSS 75 MB
  - `.so` mmap PSS 3.9 MB / RSS 50.9 MB（52 MB 词库按页加载）
  - 进程总 PSS 246 MB / RSS 396 MB
  - 相对旧 16 MB bin，文件页增量约 35 MB，低于 60 MB 目标；
    未做旧版本同进程对照，数字为当前值。

### 已决定不做 / 暂缓

- **Phase 3 扩展词库导入 UI：不做。** 公开发布时整个项目 GPL 开源并内置
  B slim，扩展导入不是必需功能；运行期已保留 ext 层支持，以后要做随时可接。
- **真机单键延迟打点：不做。** 目标机 SM-S9380 为旗舰平台，B slim 下
  host P95 310 µs，即使按 ARM + 冷缺页放大一个数量级仍低于 5 ms；
  若后续实际使用感到卡顿，再临时加计时日志定位即可。

## 三之二、2026-10-20 审计修复

全量只读审计后已修复的主要项：

- **安全**：OTP 直推接收器改为 fail-closed（令牌缺失即拒绝，宿主启动时生成令牌；
  仅额外放行 Android 14+ 可验证的三星建议/键盘发送包）；
  安全门禁改为按模块 ClassLoader + 模块签名判定，未知进程一律拒绝；
  所有敏感 JNI 入口统一加门禁；签名校验所有反射失败改为失败；
  增加 `TracerPid` 检测；release 签名改为从 `keystore.properties`/环境变量读取，
  缺失时构建失败（16KB 对齐任务复用同一证书）。正式签名后必须运行
  `python3 tools/update_expected_cert.py <keystore> <alias> <storepass>`
  更新 native 内置的证书哈希；否则 release 包模块 App 内的 native 初始化会被门禁拒绝。
- **数据**：覆盖导入先校验至少一条有效记录，坏/空 TSV 不再清空词库；
  清空任务在 `lmInit` 之后执行，并跳过比清空时间戳旧的词库快照；
  计算器替换前校验光标前文本；8105/41448 读音合并；构建工具检查写入/seek/close
  失败；`.gram` 头校验；`max_cands`/词长上限；LM 种子按词频取前 6 万。
- **运行期**：修正进制转换后缀/中文前缀解析；删除未启用的模糊音死代码；
  限制整句输入长度；用户词库导入/新增加 key/word/桶/总 key 上限；
  `XLog.e` release 关闭；`allowBackup=false`；release 禁止从可写目录回退加载 `.so`；
  外置词库校验 header version；词库查询启动竞态收窄。
- **UI/状态**：输入框切换清理计算器状态；融化动效异常复位；导入/导出移到后台线程；
  同步状态文件加锁；合成候选 hook 校验返回类型；构造器预热全成功才置位；
  隐藏/省略联想点击不再学习错误编码；设置页 `commit` 改 `apply`。
- **构建**：CMake 声明 C++17、Release 才 `-O3`、`.incbin` 声明 `OBJECT_DEPENDS`；
  release lint 开启；16KB 任务缺文件时失败；删除未用字段/资源/导入与不可达分支；
  README 词库数字更新；新增 `THIRD_PARTY_NOTICES.md` 与 `keystore.properties.example`。

验证：debug/release 构建、单测、lint、C++ 严格警告、math/OTP 的 ASan/UBSan
20 万组随机输入、真机注入/拼音候选/dz/@上文/沙盒计算均通过。
release APK 实测 57.8 MB，使用临时测试证书验证签名流程；正式发布需换成自有 keystore。

仍需宿主信息/后续处理的项：

- Keys Cafe 的泛化字符串集合清理：需要宿主真实字段名，暂未收窄以免破坏绕过；
- 语音 `startedBySpace` 的 800ms 时间窗推断：需要手势级验证后再改；
- 外置词库 Darts 内部结构校验、`getView` 复用：优先级较低，暂未改；
- GPL 开源/`LICENSE` 更换按计划在正式发布前执行。

## 四点五、公开发布与 GPL（已决定，发布前执行）

**决定（2026-10-20）：整个项目在正式公开发布时按 GPL-3.0 开源并提供源码。**

- 当前阶段按私有开发推进，可以继续内置 rime-ice 数据；
- 发布前需要补齐：仓库根目录 `LICENSE`（GPL-3.0）、源码公开地址、
  构建说明，以及对应版本的完整源码；
- 当前 B slim / A / D / E 都包含 rime-ice 的 base/8105 数据，均属于
  GPL-3.0 衍生数据，与上述决定一致；
- 注意：旧方案里“只内置 A 档”并不能规避 GPL，A 档同样用了 base/8105。

## 四、复现命令

```bash
cd /home/king/桌面/king
g++ -O2 -std=c++17 tools/build_embedded_data.cpp -o /tmp/build_embedded_data

DICT=/home/king/桌面/词库/cn_dicts
GRAM=/home/king/桌面/词库/wanxiang-lts-zh-hans.gram

# A 档（内置）
/tmp/build_embedded_data --dict-dir "$DICT" --gram "$GRAM" \
  --profile core --out docs/lexicon-build/core_A.emb.bin --stats

# B 档（推荐，可加 --max-cands 12 --word-prior-limit 300000 得到 slim）
/tmp/build_embedded_data --dict-dir "$DICT" --gram "$GRAM" \
  --profile standard --out docs/lexicon-build/standard_B.emb.bin --stats

# B 档外置扩展格式
/tmp/build_embedded_data --dict-dir "$DICT" --gram "$GRAM" \
  --profile standard --format dict --out docs/lexicon-build/standard_B.dict.bin --stats

# D / E 档
/tmp/build_embedded_data --dict-dir "$DICT" --gram "$GRAM" \
  --profile full --tencent-limit 300000 --out docs/lexicon-build/standard_D.emb.bin --stats
/tmp/build_embedded_data --dict-dir "$DICT" --gram "$GRAM" \
  --profile full --out docs/lexicon-build/full_E.emb.bin --stats
```

## 五、待决策

1. **体积档**：A / B / B slim / D / E。建议默认 **B slim**（约 56 MB APK），
   需要长尾覆盖再上 D。
2. **是否公开发布**：rime-ice 为 GPL-3.0。内置其词库再分发需要按 GPL-3 提供源码；
   私有自用无此问题。若要规避，可只内置 A，扩展档由用户自行导入。
3. 神经重排：仍不建议现在接入。
