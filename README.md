# ZPartyHot —— 热插件版「政党 = 政体」

> **简介**:纯热插件实现的政党系统 —— 按 `zparty.json`(或游戏 `Parties.json`)生成政党、支持率与政体切换,
> 状态写进国家事件变量、**随存档保存**;不补丁任何游戏类。
>
> **快速开始**:① `build.bat` → ② `build\ZPartyHot.jar` → ③ 放 `hotplugins\jar\`,
> 配置放 `hotplugins\resources\ZPartyHot\`(详见「2. 部署」)。
> **依赖**(不进版本库):JDK 11+、`game.jar`、`HotPluginLoader-0.2.0.jar`。
> **许可**:GPL-3.0,见 `LICENSE.TXT`。
> **隐私**:纯本地运行,不联网、不收集数据;状态只写在游戏存档与国家变量里。

热插件形式（不补丁任何游戏类），功能与 `LoaderSettings\ZPartyLite` 等价，**区别只在事件触发器的实现方式**。

## 1. 与补丁版（ZPartyLite）的对照

| 能力 | ZPartyLite（补丁 dex） | ZPartyHot（热插件） |
|---|---|---|
| 政党 = `Governments.json` 某政体 | ✅ | ✅ 同一套逻辑 |
| 换执政党 = `changeGovernmentType(civ, id, true)` | ✅ | ✅ |
| 支持率持久化 | ✅ 跟随存档 | ✅ **同样跟随存档**（写进文明变量表，见第 3 节） |
| 政体页饼图 + 政党行 | ✅ | ✅ 每 8 帧自愈挂载（默认挂在任务/事件页 `InGame_Missions`，可配置） |
| 事件**效果** `add_variable=zparty:...` | ✅ | ✅ 轮询 `Civilization.eventsDataVariables.v`，执行后从列表移除 |
| 事件**触发器** `has_variable=zparty:...` | ✅ 任意写 | ⚠ 变量镜像：`ruling:*` 任意写；`support:*` 阈值需在配置里声明 |
| 改宗教 / 文化 | ❌ 都不改 | ❌ 都不改 |
| 需要重打 APK | 是 | **否**，丢 jar 进 `hotplugins/` 即可热更 |

## 2. 部署

```
hotplugins/jar/ZPartyHot.jar                        ← 本插件（宿主自动转 dex）
hotplugins/resources/ZPartyHot/zparty.json          ← 主配置（推荐，插件私有）
hotplugins/resources/ZPartyHot/zparty-ai.json       ← AI 配置（可选；缺失则用默认值）
```
也支持（按优先级）：

1. `hotplugins/resources/ZPartyHot/zparty.json`（`ctx.dataDir()` = `resources/<jar 主名>/`，本插件即 `ZPartyHot`）
2. `hotplugins/resources/ZPartyHot/override/rainfall/zparty.json`（走资源覆盖）
3. 游戏内 `game/Parties.json`（原版格式，`Tag/Extra_Tag` + `ExtraTag/Ideology/Ideology_Tag` 都认）

> 安卓设备上上述路径均在 `Android/data/<包名>/files/hotplugins/` 下；暮色包包名 = `age.of.history3.zzz.twx`。

存档副产物：**没有**。状态直接写在该文明的变量表里，跟着游戏存档走（下节）。

## 3. 状态存在哪（为什么能随存档保存）

文明变量表 `Civilization.eventsDataVariables`（`public List<String> v`）是 `Civilization` 的普通字段，
游戏存档里有专门的数据块把它整张表序列化进去：

- 存：`SaveGameManager.Save_Civs_Data_EventsVariables()` / `Save_Civs_Data_EventsVariables2()`
  （遍历全部文明、一半一半写 `eventsDataVariables`）
- 读：`LoadSavedGameManager.loadSave_CivsEventsVariables()` / `loadSave_CivsEventsVariables2()`

所以只要把政党状态编码成变量字符串写进这张表，它就真正属于存档：

| 变量 | 含义 |
|---|---|
| `zparty:r:<党tag>` | 执政党（仅当与当前政体推导值不同时写） |
| `zparty:s:<党tag>:<千分值>` | 支持率千分比（仅当不是默认「单党 100%」时写） |

- 默认状态不写，所以存档体积通常只增加玩家 + 被事件改过的国家几条。
- 每 16 帧把有变化的状态同步进变量表，游戏存档时自然拿到最新值。
- 代价：每国变量表多出 ≤ 党数+1 条字符串（`hasVariable` 是 List 线性查，几条可忽略）。
- 附带好处：未执行的事件命令（`add_variable=zparty:...`）也随存档保留，读档后照常执行。

## 4. 配置

```json
{
  "Enabled": true,
  "ShowInGovernmentPage": true,
  "UiPages": ["government"],
  "MaxRows": 6,

  "MirrorCivs": ["*"],
  "SupportThresholds": [0.25, 0.5, 0.75],

  "CostStat": "legacy",
  "CostAmount": 100,
  "SupportPerDay": 0.02,
  "CampaignDays": 10,
  "CooldownDays": 30,

  "Parties": [
    { "Tag": "mon", "Name": "保皇党", "ExtraTag": "m", "Color": [210, 64, 64] }
  ],
  "Countries": [
    { "Civ": "afg", "Ruling": "mon",
      "Support": [ { "Tag": "mon", "Share": 0.65 } ] }
  ]
}
```

- `ExtraTag` = `game/Governments.json` 里该政体的 `Extra_Tag`（或 `Name`）。暮色包里是单字/符号（`m`、`n`、`c`、`苏`…），不是数字。旧名 `Ideology` 仍兼容。
- `Civ` = `game/civilizations/*.json` 的 `Tag`（小写，如 `afg`）。**写基础标签即可**：运行时 `getCivTag()` 会带政体后缀（`ger_普`），插件会自动去掉 `_` 后缀再匹配。写错只会跳过该国并打一条带示例的日志，不影响其他功能。
- `UiPages`：控件挂到哪个菜单（可多个，按顺序找）：

  | 值 | 菜单类 |
  |---|---|
  | `government`（默认） | `menusInGame.Court.InGame_Court_Government`（政体/意识形态页） |
  | `missions` | `menusInGame.InGame_Missions`（任务树源码里带 `Event.show_in_missions`，但实测控件不显示，仅保留选项） |
  | `missiontree` | `menusInGame.InGame_MissionTree` |
  | `missions_court` | `menusInGame.Court.InGame_Court_Missions` |
  | `civ` | `menusInGame.Civ.InGame_Civ_Government` |
  | 完整类名 | 直接按 `getClass().getName()` 匹配 |

  不知道某个页面叫什么：打开那个页面，看日志里的
  `active menus: 0=InGame_Court 20=InGame_Court_Government …`（每 601 帧输出一次，需宿主开日志），把类名填进 `UiPages`。
- `MirrorCivs`：哪些文明要做触发器变量镜像。空/省略 = 只镜像玩家；`["*"]` = 全部（AI 事件也能用 `has_variable_civ`，但每次刷新开销随文明数增长）。
- `SupportThresholds`：需要镜像的支持率阈值列表（见下）。
- `Support` 是**该国存在的政党清单**（不只是份额）：没写进去的党在该国不存在 —— 不进饼图、不显示政党行、也不能对其开展行动。执政党不受此限制，永远存在。`Parties` 只负责 `Tag → 名称/颜色/ExtraTag` 的索引，不决定哪些党出现在哪个国家。
- `MaxRows` 只限制**该国实际存在的政党**按支持率排序后的显示行数，与 `Parties` 里有几个党无关。
- `Countries` 条目只写 `Ruling`、不写 `Support` 时：政党清单与份额沿用 `DefaultCountry`，只替换执政党。
- 想要**一党独大**：该写成 `"Support": []`（显式空数组）—— 此时除执政党外没有任何党，支持率 100%。
- `Countries[].Support[].Share`：支持率初值。可写固定值 `0.35`，也可写区间 `"0.20~0.30"`：每次**新开局**掷一次（步长 0.01、两位小数），掷出的值随状态写入存档，读档不会重掷。所有份额会自动归一化，加起来不等于 1 也没关系；非执政党合计超过 0.65 时执政党强制保留 35% 后重新归一化。
- `CostStat` / `CostAmount`：开展一次政党行动消耗的点数（`legacy` 传承点 / `gold` 金币 / `diplomacy` 外交点，默认 `legacy`）。
- `SupportPerDay`：行动期间**每个游戏日**给该党增加的支持度（0.02 = +2 个百分点）。
- `CampaignDays`：行动持续天数；结束后该党按钮进入 `CooldownDays` 天冷却（期间点击会被拒绝并 toast）。
- `ActionSuccessChance`：行动成功率（0.75 = 75%）；失败时按 `ActionFailRefund` 退还部分点数、目标党掉 `ActionFailPenalty` 支持度、并进入 `ActionFailCooldownDays` 天冷却（0 = 用 `CooldownDays`）。

### 选举（定期 + 强制提前）

| 键 | 含义 |
|---|---|
| `ElectionsEnabled` | 总开关 |
| `ElectionIntervalDays` | 定期选举周期（天，0 = 关）；到期自动开选并结算 |
| `ElectionCostStat` / `ElectionCostAmount` | “强制提前选举”按钮的点数开销 |
| `ElectionCooldownDays` | 提前选举后的冷却 |
| `ElectionMaxAdvanceDays` | 最多提前天数：可用条件 = `距大选剩余天数 - 本值 <= 0`；`60` = 只在大选前 60 天内可提前选举，`0` = 仅选举当天，`-1` = 不限制 |
| `ElectionWeightExponent` | 权重 = `支持率^指数`；1 = 按支持率线性，>1 放大领先者，<1 拉平 |
| `ElectionIncumbentBonus` | 执政党的权重倍率（1 = 无加成） |
| `ElectionNoise` | 每个党权重的随机扰动幅度（0.35 = ±35%）→ 第二名有真实胜选机会 |
| `ElectionChangeRuling` | 获胜党是否成为执政党（换政体） |
| `ElectionAllCivs` | 是否让 AI 国家也定期选举（默认 false；true 时每个文明会多一条存档变量） |

结算 = 按权重加权随机抽一个党；日志 `election: civ=7 winner=com share=42% (scheduled)`。
定期选举时钟、冷却与结果都存在文明变量表里（`zparty:e:`），随存档。

**换政体不花钱**：原版 `IdeologiesManager.changeGovernmentType(.., free=true)` 仍会在延迟任务里扣 `CHANGE_GOVERNMENT_COST` / `CHANGE_GOVERNMENT_COST_LEGACY`（暮色包 = 100000 金币 + 10200 传承点）。插件在调用前先把这笔钱预支给国家，任务执行时刚好抵消，所以选举/事件/配置换政体都是**零花费**；任务若未执行则自动回滚。日志：`gov switch paid by plugin: civ=7 (gold 100000.0 / legacy 10200.0 neutralised)`。

**按钮置灰**：行动冷却中、已有行动进行中、选举冷却中、超出 `ElectionMaxAdvanceDays`、点数不足
（政党行显示“点数不足”）等情况，对应按钮会变成灰色（与“定期选举”同一套禁用外观）且点击无效；条件恢复后自动变回可用。置灰判定每 15 帧（约 0.25 秒）刷新一次，只在页面可见时执行。

### 点击政党行 = 开展行动（不是换政体）

| 阶段 | 行为 |
|---|---|
| 点击 | 校验冷却/进行中/点数 → 扣 `CostAmount` → 开始 `CampaignDays` 天行动 |
| 每天 | 目标党 `+SupportPerDay`，其他党按当前占比等比例扣减（总和恒为 1），饼图当帧刷新 |
| 结束 | 写入 `CooldownDays` 天冷却，期间所有政党行不可用 |

- 换政体仍然只由事件 `add_variable=zparty:ruling:<tag>` 或配置 `Countries[].Ruling`（仅新局）触发，点行不会改政体。
- 行动/冷却状态写在文明变量表里（`zparty:c:` / `zparty:d:`），随存档保存。
- 饼图**悬停**可看到全部政党、支持度百分比与执政标记。

## 5. 事件接口

**效果（完全可用，任意写）**

| 写法 | 作用 |
|---|---|
| `add_variable=zparty:ruling:aoh2` | 设为执政党（= 换政体） |
| `add_variable=zparty:set:aoh2:0.4` | 支持率 40% |
| `add_variable=zparty:add:aoh2:0.1` | +10%（可负） |
| `add_variable=zparty:transfer:aoh2:aoh3:0.1` | A→B 转移 10% |
| `add_variable=zparty:distribution:aoh2:0.6:aoh3:0.4` | 按比例重排 |

执行后该变量会从文明变量表移除，不会污染原版 `has_variable` 语义。

**触发器（变量镜像，有限制）**

| 写法 | 是否支持 |
|---|---|
| `has_variable=zparty:ruling:aoh2` | ✅ 任意写（全部政党自动镜像） |
| `has_variable_not=zparty:ruling:aoh2` | ✅ 自动成立（条件为假时该变量不存在） |
| `has_variable=zparty:support:aoh2:gt:0.5` | ✅ **仅当 `0.5` 列在 `SupportThresholds` 里** |
| `has_variable_civ=usa=zparty:ruling:aoh2` | ✅ 需把 `usa` 写进 `MirrorCivs`（或 `"*"`） |

限制说明：

- 只镜像 `gt / ge / lt / le`（`eq / ne` 需要浮点精确相等，未镜像）。
- 阈值必须写成**规范小数**：`0.5`、`0.25`、`0.75`；`0.50`、`.5`、`1.0` 与镜像串不一致会永远为假。
- 原理：触发器读的是文明变量表里"字符串是否存在"，热插件无法拦截 `outCondition`，只能把当前为真的字符串写进去、为假的删掉。

### 触发器（实时求值 —— 需要补丁 `LoaderSettings\HotVarApi`）

补丁 dex 里装了 `com.saysky.hotvar.HotVars` 之后，本插件会在启动时自动注册（日志
`event api: live triggers registered (HotVarApi installed)`），触发器改为**实时求值**，上面的限制全部消失：

| 写法 | 说明 |
|---|---|
| `has_variable=zparty:ruling:<党>` | 执政党是否该党 |
| `has_variable_not=zparty:ruling:<党>` | 取反 |
| `has_variable=zparty:support:<党>:<gt\|ge\|lt\|le\|eq\|ne>:<0..1>` | **任意阈值**，实时比较（`eq/ne` 容差 0.005） |
| `has_variable_civ=<国家Tag>=zparty:...` | 指定国家实时求值，不需要 `MirrorCivs` |

- 同时注册了 `zanparty:` 前缀，老事件文件里的 `zanparty:` 写法也能直接用。
- 装了桥接后 `MirrorCivs` / `SupportThresholds` 可以留空（镜像仍保留，只是不再必要）。

## 6. 构建

```bat
ZPartyHot\build.bat
```

> **工具链/路径**:游戏根默认取脚本上一级,可用 `AOH_GAME_ROOT` 覆盖;JDK 取 `JAVA_HOME`,
> 否则自动找 `C:\Program Files\{Microsoft,Java}\jdk-*`。编译依赖
> `<游戏根>\MyFirstPlugin\libs\game.jar`(或自备 `game.jar`)与 `HotPluginLoader-0.2.0.jar`,均不进版本库。

- 纯 `javac + jar`，不需要 gradle；`game.jar` 与宿主 jar 仅作编译依赖，**不打包**（否则 `instanceof HotPlugin` 会失败）。
- 产物 `ZPartyHot\build\ZPartyHot.jar`（含 `plugin.json`，主类带 `@HotPluginInfo`，宿主两条链路都能识别）。

## 7. 运行期日志（宿主「开启日志」后可见）

```
ZParty ready: parties=2 enabled=true
config: dataDir/zparty.json
new world: no party state in save (lazy init)
state persisted into civ variables: 1 civ(s)
state restored from save: nations=7
ui: widget mounted on InGame_Missions (civ=7, elements=42)
```

## 8. AI 适配（独立配置 `zparty-ai.json`）

AI 国家以前有两个毛病：**后期全部变成 `Governments.json` 第一个政体（暮色包 = 临时政府）**、**所有国家同一个党执政**。

根因有两个：

1. 插件旧版用 `IdeologiesManager.getIdeologyID(String)` 解析 `ExtraTag`——这个方法其实是给**文明标签**（`rus_准`）的，
  传入裸 `ExtraTag`（`m`、`准`）时会静默 `return 0`，于是每次换政体都换成第 0 个政体。现已改为直接扫 `Extra_Tag` / `Name`，匹配不上就放弃（`-1`），**永远不会误落到临时政府**。
2. 原版 `AI_Government.updateGovernment()` 会给部落/城邦类政体随机抽一个政体（候选池包含第 0 个“临时政府”），与政党系统无关。

新行为（独立文件 `zparty-ai.json`，与主配置同目录）：

| 键 | 默认 | 作用 |
|---|---|---|
| `Enabled` | `true` | AI 参与政党系统 |
| `Elections` | `false` | AI 也定期选举 |
| `Persist` | `false` | AI 状态写存档（关 = 存档小，读档后按模板+抖动重生成） |
| `Jitter` | `40` | 初始支持率抖动 ±%（**多样性来源**） |
| `DriftDays` / `DriftAmount` / `DriftCivs` / `DriftBias` | `60 / 0.02 / 24 / 0.6` | 支持率随时间漂移（分批处理，控制开销） |
| `FixMax` | `4` | 每次最多修几个“无党籍政体”（每修一个 = 一次原版换政体任务，单独限流） |
| `PollFrames` | `0` | 覆写主配置的命令扫描间隔（0 = 跟主配置；调大可减扫描开销，代价是 `add_variable` 效果延迟） |
| `ElectionIntervalDays` / `ElectionDelayDays` | `0 / 240` | AI 周期与错峰 |
| `FixStrayGovernments` | `true` | 政体不属于任何党（如被原版改成临时政府）→ 按支持率自动纠正 |

效果：每个 AI 的初始局面不同 + 长期漂移 + 可选选举 → 各国执政党分散；被原版 AI 打偏的国家会在下一轮自愈。

## 10. 已知限制

- 政党行文本与饼图会随支持率变化（每次 revision 变化时重画）。
- `eq / ne` 触发器不镜像。
- 每 4 帧扫全部文明的变量表、每 16 帧做一次镜像；后期大局面（1000+ 文明）建议 `MirrorCivs` 保持只玩家。
- 已删除补丁版 ZPartyLite；不再有两者共存的问题。

## 11. 现成预设：暮色黄昏（世界大战 0.25.1）

`ZPartyHot\presets\musedusk-ww1\zparty.json` —— 按该包的 `Governments.json` 与剧本 `TheGreatWar` 生成。

| 党 | Tag | 对应政体（`Governments.json`） | 剧本中的主要国家 |
|---|---|---|---|
| 保皇党 | `royal` | `m` 绝对君主制 | 清（qin）、埃及 |
| 立宪党 | `const` | `n` 君主立宪主义 | 英（uni）、意（ita）、瑞典 |
| 钦定党 | `dual` | `准` 二元立宪主义 | 俄、土、日、西、葡、伊朗、奥匈 |
| 殖民党 | `colonial` | `x` 殖民主义 | 英属印度、自治领、奥（aus） |
| 自由党 | `liberal` | `r` 古典自由主义 | 美（usa） |
| 保守党 | `conservative` | `1` 保守主义 | 巴西、阿根廷、哥伦比亚、智利 |
| 激进党 | `radical` | `j` 激进共和主义 | 法（fra） |
| 军国党 | `militarist` | `普` 普鲁士军国主义 | 德（ger） |

- 这 8 个政体按省份数覆盖了剧本约 88% 的国土，其余国家走 `DefaultCountry` 模板。
- `Countries` 用**基础标签**（`ger`/`rus`/`uni`…），德国/俄国/英国/法国/美国另有单独的支持率分布。
- 同目录还有 `zparty-ai.json`（AI 配置：抖动 40%、漂移 60 天/48 国、AI 选举 4 年、错峰 300 天、政体自愈开、扫描间隔 12 帧）。
- 部署：整个文件夹拷进去（**文件名与插件读取名一致**，不用改名）

  ```
  .../hotplugins/resources/ZPartyHot/zparty.json      ← presets\musedusk-ww1\zparty.json
  .../hotplugins/resources/ZPartyHot/zparty-ai.json   ← presets\musedusk-ww1\zparty-ai.json
  ```

- 日志确认：`config: dataDir/zparty.json`、`config: dataDir/zparty-ai.json`；`party skipped` 不应出现；
  若出现 `config: civ tag 'xxx' not found` 说明该文明没在剧本开局时生成，可删掉对应条目。
