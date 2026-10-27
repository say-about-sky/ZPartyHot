# zparty.json 配置说明

放置路径（**目录名 = jar 文件名主名**，通常是 `ZPartyHot`）：

```
hotplugins/jar/ZPartyHot.jar
hotplugins/resources/ZPartyHot/zparty.json      ← 主配置（政党/国家/选举）
hotplugins/resources/ZPartyHot/zparty-ai.json   ← AI 配置（可选，缺失则用默认值）
```

改完配置重启游戏即可；改 jar 时要删掉同名的 `ZPartyHot.jar.done` 让宿主重新转 dex。

文件布局（**文件名 = 插件读取名**，直接拷进去就能用）：

```
ZPartyHot\
├─ example\                 ← 通用示例（可直接当模板）
│  ├─ zparty.json
│  └─ zparty-ai.json
└─ presets\
   └─ musedusk-ww1\        ← 暮色黄昏专用（文件夹区分）
      ├─ zparty.json
      └─ zparty-ai.json
```

---

## 通用

| 键 | 默认 | 说明 |
|---|---|---|
| `Enabled` | `true` | 插件总开关 |
| `ShowInGovernmentPage` | `true` | 是否挂 UI（关掉后只剩事件接口） |
| `UiPages` | `["government"]` | 挂到哪些菜单：`government`（政体/意识形态页，可用）、`missions`、`missiontree`、`missions_court`、`civ`，或直接写完整类名。可写多个 |
| `MaxRows` | `6` | 政体页最多显示几个政党行（只按**该国实际存在**的政党排序截断，与 `Parties` 数量无关） |
| `MirrorCivs` | `[]` | 为哪些文明镜像触发器变量（仅在没有 HotVarApi 的旧环境下需要）；空 = 只玩家，`["*"]` = 全部 |
| `SupportThresholds` | `[0.5]` | 镜像方案下的支持率阈值（只有 `gt/ge/lt/le`，x 必须写成 `0.5` 这种形式）。装子 `LoaderSettings\HotVarApi` 后不需要再配 |

## 政党数据

```json
"Parties": [
  { "Tag": "mon", "Name": "保皇党", "ExtraTag": "m", "Desc": "绝对君主制", "Color": [200, 60, 60] }
]
```

| 键 | 说明 |
|---|---|
| `Tag` | 政党唯一标识（事件里用 `zparty:ruling:<Tag>`），建议纯英文 |
| `Name` | 显示名 |
| `ExtraTag` | 对应 `game/Governments.json` 里该政体的 **`Extra_Tag`**（暮色包是单字/符号：`m`、`n`、`c`、`苏`…；**不要写中文名**，因为存在同名政体） |
| `Color` | `[R, G, B]`（0–255），饼图与 tips 用；省略按 Tag 哈希生成 |
| `Desc` | 保留字段 |

也兼容旧格式：`Extra_Tag` 当 `Tag`；旧名 `Ideology` / `Ideology_Tag` 仍可当 `ExtraTag`（推荐用新名 `ExtraTag`）。

## 开局设定（可选）

```json
"Countries": [
  { "Civ": "afg", "Ruling": "lib",
    "Support": [ { "Tag": "mon", "Share": 0.35 }, { "Tag": "lib", "Share": 0.45 } ] }
]
```

| 键 | 说明 |
|---|---|
| `Civ` | 文明 `Tag`（`game/civilizations/*.json`，小写，如 `afg`）。写基础标签即可 —— 运行时标签带政体后缀（`ger_普`），插件会自动去掉 `_` 后缀再匹配。写错只会跳过并输出一条带示例的日志 |
| `Ruling` | 新开局时**真的切换**成该党对应政体（跳过花费/科技） |
| `Support` | **该国存在的政党清单 + 初始支持率**：没写进 `Support` 的政党在该国**不存在**（不进饼图/不显示行/不能发展）；执政党总是存在。份额自动归一化到总和 1，加起来不等于 1 也没关系 |
| `Support: []` | **一党独大**：除了执政党没有任何其他党（100%） |

`Support` 的三种状态（靠“字段是否出现”区分）：

| 写法 | 含义 |
|---|---|
| 不写 `Support` | 该条只覆盖执政党，政党清单与份额**沿用 `DefaultCountry`** |
| `"Support": []` | 只有执政党（一党独大） |
| `"Support": [ … ]` | 列出的党存在，未列出的不存在 |
| `Support[].Share` | 固定值 `0.35`，或**随机区间** `"0.20~0.30"`（见下） |

### `Share` 写法

| 写法 | 行为 |
|---|---|
| `"Share": 0.35` | 固定 35% |
| `"Share": "0.20~0.30"` | 每次**新开局**（该文明第一次建档）在区间内随机，**步长 0.01、只保留两位小数**，取完就写进存档 |
| `"Share": "0.04~0.04"` | 等价于 `0.04`（区间为 0 步） |

- 随机只在创建该国状态时掷一次，之后存档/读档都用同一个值，**不会与存档冲突**；重开新局才会重新掷。
- 自动规避浮点误差：值一律 `四舍五入到 0.01`，所以 UI 与触发器镜像里不会出现 `0.30000001`。
- 所有 `Share` 加起来不等于 1 时自动归一化；若非执政党合计超过 0.65，则执政党强制保留 0.35 后再整体归一化。
- 旧写法（纯数字）完全兼容，字段类型已改为字符串但数字会自动转换。

> 删掉整个 `Countries` 段也能正常工作：开局按各国当前政体反推执政党。

### `DefaultCountry` 模板（顶层，与 `Countries` 同级）

```json
{
  "DefaultCountry": {
    "Support": [ { "Tag": "soc", "Share": 0.14 }, { "Tag": "com", "Share": 0.08 } ]
  },
  "Countries": [
    { "Civ": "afg", "Ruling": "lib", "Support": [ ... ] }
  ]
}
```

- 作为**所有没有单独配置的文明**的模板；`Countries` 里只放具体文明，不再接受 `"Civ": "default"`。
- `DefaultCountry.Support` 列出的党 = 未单独配置的国家**存在的政党**；没列出的党（例如专属于某国的 `germilitarist`）在这些国家不存在。
- `DefaultCountry` **不会改执政党**，各国仍用地图里原本的 `Governments`（政体）对应的那个党（所以这里写 `Ruling` 也无效）。
- `Support` 只用来给**其他党**铺基础支持率；**执政党自动拿剩余部分，且至少 35%**，所以地图政体始终是高支持率那一方。
- 例：某国政体是 `n`（立宪党），其余党合计 0.32 → 立宪党 = 0.68。
- 单独配置的文明优先；单国配置里的 `Support` 也遵循同样的“执政党拿剩余”规则。
- 旧写法 `{ "Civ": "default", ... }` 放在 `Countries` 里会被忽略，并在日志提示改用顶层 `DefaultCountry`。

## 政党组织行动（点政党行）

| 键 | 默认 | 说明 |
|---|---|---|
| `CostStat` | `legacy` | 消耗哪种点数：`legacy`（传承/文明点）、`gold`（金币）、`diplomacy`（外交点） |
| `CostAmount` | `100` | 每次行动消耗量；`0` = 免费 |
| `SupportPerDay` | `0.02` | 行动期间每天给该党 **+2 个百分点**（其他党按占比等比例让位，总和恒为 1） |
| `CampaignDays` | `10` | 行动持续天数 |
| `CooldownDays` | `30` | 行动结束后的冷却天数（期间点行被拒绝并 toast） |
| `ActionSuccessChance` | `0.75` | 行动成功率（0–1） |
| `ActionFailRefund` | `0.5` | 失败时返还的点数比例 |
| `ActionFailPenalty` | `0.01` | 失败时目标党损失的支持度（分给其他党） |
| `ActionFailCooldownDays` | `15` | 失败后的冷却（`0` = 用 `CooldownDays`） |

## 选举

| 键 | 默认 | 说明 |
|---|---|---|
| `ElectionsEnabled` | `true` | 选举总开关 |
| `ElectionIntervalDays` | `365` | 定期选举周期（天）；`0` = 关闭定期选举 |
| `ElectionAllCivs` | `false` | 是否让 AI 国家也定期选举（每个文明会多一条存档变量） |
| `ElectionCostStat` | `diplomacy` | “强制提前选举”消耗的点数类型 |
| `ElectionCostAmount` | `50` | 提前选举消耗量；`0` = 免费 |
| `ElectionCooldownDays` | `90` | 提前选举后的冷却 |
| `ElectionMaxAdvanceDays` | `-1` | **最多提前天数**：按钮可用条件 = `距大选剩余天数 - 本值 <= 0`。`60` = 只在大选前 60 天内可提前选举；`0` = 仅选举当天；`-1` = 不限制 |
| `ElectionWeightExponent` | `1.0` | 权重 = `支持率^指数`。`1` = 线性；`2` 领先者更稳；`0.5` 更平均 |
| `ElectionIncumbentBonus` | `1.0` | 执政党的权重倍率（`1.5` = 连任优势） |
| `ElectionNoise` | `0.35` | 每党权重的随机扰动（`0.35` = ±35%），**决定“第二名翻盘”的概率** |
| `ElectionChangeRuling` | `true` | 获胜党是否成为执政党（= 换政体） |

## AI 配置（`zparty-ai.json`，与 `zparty.json` 同目录、独立文件）

| 键 | 默认 | 说明 |
|---|---|---|
| `Enabled` | `true` | AI 是否参与政党系统（抖动 / 漂移 / 自愈 / 选举） |
| `Elections` | `false` | AI 是否也定期选举（false = 只漂移 + 自愈，不投票） |
| `Persist` | `false` | 把 AI 政党状态写进存档变量（关 = 存档更小，AI 状态每次读档按模板+抖动重生成） |
| `Jitter` | `40` | AI 初始支持率抖动百分比（±40 = ±40%）——**多样性主要来源** |
| `MinShare` | `0.01` | 任意党最低份额，抖动/漂移不会把它压到 0 |
| `DriftDays` | `60` | 支持率漂移间隔（游戏日）；`0` = 关闭漂移 |
| `DriftAmount` | `0.02` | 每次漂移转移的份额（0.02 = 2 个百分点） |
| `DriftCivs` | `24` | 每次最多处理多少个 AI 国家（开销上限；341 国的整合包建议 48~64） |
| `FixMax` | `4` | 每次最多修几个“无党籍政体”。每修一个 = 排一次原版换政体任务（重算加成 + 重载君主），所以单独限流 |
| `DriftBias` | `0.6` | 漂移方向偏向“次强党”的概率（0.5 = 完全随机，1 = 总是造一个对手） |
| `ElectionIntervalDays` | `0` | AI 自己的选举周期；`0` = 用主配置的 `ElectionIntervalDays` |
| `ElectionDelayDays` | `240` | AI 首次大选的随机错峰上限（天），避免全世界同一天投票 |
| `FixStrayGovernments` | `true` | AI 政体不属于任何政党（例如被原版 AI 改成“临时政府”）时，按支持率自动纠正 |
| `PollFrames` | `0` | **覆写主配置的 `PollFrames`**（事件命令扫描间隔）：`0` = 跟随主配置；调大 = 扫描更省，代价是 `add_variable` 效果最多延迟这么多帧。上限 600 |

工作原理：

1. AI 国家第一次建档时，非执政党的份额按 `Jitter` 抖动 → 每个国家的初始局面不同；
2. 每 `DriftDays` 天处理 `DriftCivs` 个国家：从最强的非执政党向另一个党（按 `DriftBias` 偏次强）转移 `DriftAmount`；
3. 同样这一轮里，若该国政体不属于任何政党（原版 AI 会随机把部落/城邦改成 `Governments.json` 第一个政体，例如“临时政府”），直接把支持率最高的党推上去修复（每次最多 `FixMax` 个）；
4. `Elections=true` 时 AI 也会按自己的周期与错峰开选，胜者换政体。

### 帧开销（为什么 AI 不会拖帧）

| 频率 | 工作 | 量级 |
|---|---|---|
| **每帧** | `tick()`：几个 int 比较；`settleGovCredit()` 仅在一次换政体在途时有工作 | 几 ns，无可分配内存 |
| 每 `PollFrames`（8 帧） | `pollCommands()`：遍历所有文明，每个空的变量表直接 `continue`（AI 配置的 `PollFrames > 0` 时以此为准） | 341 国 ≈ 0.05 ms/s |
| 每 `MirrorFrames`（16 帧） | `mirrorAll()`：`MirrorCivs` 为空时只处理玩家 | 可忽略（装了 HotVarApi 可完全关掉镜像） |
| 每 30 帧且状态真变 | `persistAll()`：`Persist:false` 时跳过所有 AI，只写玩家 | 可忽略 |
| **每个游戏日** | `tickAi()` 先过 `DriftDays` 间隔闸门，不到期直接 return | 1 次比较 |
| 每 `DriftDays`（默认 60 游戏日） | 处理 `DriftCivs` 个国家：每人 9 个党的小循环 + 一次归一化 | 微秒级，且分批 |
| 每 `DriftDays` | 政体修复最多 `FixMax` 个（原版换政体任务） | 每国一次任务，已限流 |
| 选举日 | 每个到期国家一次加权随机 + 一次换政体任务（`ElectionDelayDays` 错峰） | 341 国 ÷ 周期，平均 <1 次/天 |

## 性能 / 刷新频率

| 键 | 默认 | 说明 |
|---|---|---|
| `PollFrames` | `8` | 每 N 帧扫一次文明变量表（事件命令） |
| `MirrorFrames` | `16` | 每 N 帧刷新一次触发器镜像变量 |
| `UiCheckFrames` | `8` | 每 N 帧检查一次 UI 控件是否还挂在菜单上 |

这三个只影响后台轮询。**界面不可见时没有任何绘制/文本刷新开销**：`Menu.draw` 不会遍历元素，`PartyRow`/`PartyPie` 的 `draw` 根本不会被调用。
冷却天数、选举倒计时属于"每游戏日刷新一次"（`revision++`），而且只有菜单打开并绘制时才会真正重写文字。
后期大局面可以把 `PollFrames` 调到 `15`、`UiCheckFrames` 调到 `30`。

权重与抽取：

$$w_i = \text{share}_i^{\,e}\times(\text{ruling}_i?\,b:1)\times(1+n\,(2u-1)),\quad u\sim U(0,1)$$

按 $w_i$ 加权随机选一个党。结果会 toast（玩家国）并写日志
`election: civ=7 winner=com share=42% (scheduled) prev=lib`。

---

## 常见调参配方

| 想要的效果 | 怎么改 |
|---|---|
| 第二名经常翻盘 | `ElectionNoise` → `0.7`，或 `ElectionWeightExponent` → `0.5` |
| 执政党几乎必连任 | `ElectionIncumbentBonus` → `2.0`，`ElectionNoise` → `0.1` |
| 行动太贵/太快 | `CostAmount` ↔、`CampaignDays` ↓、`SupportPerDay` ↓ |
| 行动经常失败 | `ActionSuccessChance` → `0.5`，`ActionFailPenalty` → `0.03` |
| 不想要选举 | `ElectionsEnabled: false`（只留政党行动） |
| 只想加政党显示、不要点数 | `CostAmount: 0`、`ElectionCostAmount: 0` |

## 事件接口速查

```text
add_variable=zparty:ruling:com          # 直接设为执政党（换政体）
add_variable=zparty:set:com:0.4         # 支持率设为 40%
add_variable=zparty:add:com:0.1         # +10%
add_variable=zparty:transfer:com:lib:0.1
add_variable=zparty:distribution:com:0.6:lib:0.4

has_variable=zparty:ruling:com
has_variable=zparty:support:com:gt:0.5  # 阈值必须出现在 SupportThresholds（无 HotVarApi 时）
```

## 排错

| 日志 | 含义 |
|---|---|
| `config: dataDir/zparty.json` | 配置读到了 ✅ |
| `config: game/Parties.json (fallback)` | 你的 `zparty.json` 没被找到 → 检查 `resources/zpartyhot/` 目录名 |
| `config: civ tag 'xxx' not found (examples: …)` | `Countries[].Civ` 写错，改或删掉该段 |
| `setRuling: ideology not found for xxx (yyy)` | `ExtraTag` 不是该包真实 `Extra_Tag` |
| `ui: widget mounted on InGame_Court_Government (civ=7, elements=42)` | UI 挂上了 ✅ |
| `active menus: …`（每 601 帧） | 想知道某页面类名时看这行 |
