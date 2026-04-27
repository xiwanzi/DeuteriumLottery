# lottery

`lottery` 是一个面向 Minecraft 1.20.1 Bukkit/Spigot 服务端的彩票插件，作者 `xiwanzi`。插件提供每日彩票、每周彩票、节日公益活动、箱子式 GUI、配置热重载、Vault 经济对接、SQLite 存储、PlaceholderAPI 支持和 SMTP 中奖邮件通知。

本插件以轻量、可配置、可运营为目标，适合中小型服务器做公益性活动、赛事奖金池或节日玩法。

## 功能

- 每日彩票、每周彩票与节日公益活动
- 每人每期限购
- 可配置单注价格、最低开奖奖池、奖金池比例、奖项人数和奖金比例
- 固定每日、固定每周、间隔开奖三种调度模式
- 箱子式 GUI 菜单
- 管理员可为指定玩家打开菜单，方便接入 TRMenu 等菜单插件
- Vault 经济接口，兼容 XConomy 等经济插件
- SQLite 本地数据库
- 中奖自动派奖，奖池不足自动退款
- 全服开奖广播
- SMTP 邮件中奖通知
- QQ 号自动识别为 QQ 邮箱
- PlaceholderAPI 变量支持
- 插件内 `/lottery reload` 热重载配置

## 环境要求

- Java 17
- Minecraft 1.20.1 Bukkit/Spigot/Paper 兼容服务端
- Vault
- 任意 Vault 兼容经济插件，例如 XConomy
- PlaceholderAPI 可选

## 构建

```bash
mvn clean package
```

构建完成后使用：

```text
target/lottery-1.1.0-SNAPSHOT.jar
```

不要使用 `original-*.jar`，它不包含运行所需的 shaded 依赖。

## 安装

1. 将 jar 放入服务器 `plugins` 目录。
2. 确保服务器已安装 Vault 和经济插件。
3. 启动服务器生成配置文件。
4. 修改 `plugins/lottery/config.yml`。
5. 使用 `/lottery reload` 重载配置，或重启服务器。

## 常用指令

主命令：

```text
/lottery
/lot
```

玩家指令：

| 指令 | 功能 | 权限 |
|---|---|---|
| `/lottery` | 打开自己的彩票菜单 | `lottery.use` |
| `/lottery bind <邮箱或QQ号>` | 绑定中奖通知邮箱 | `lottery.email.bind` |
| `/lottery info` | 查看自己的通知邮箱 | `lottery.email.info` |
| `/lottery unbind` | 解绑通知邮箱 | `lottery.email.unbind` |

管理员指令：

| 指令 | 功能 | 权限 |
|---|---|---|
| `/lottery open <玩家>` | 为指定玩家打开菜单 | `lottery.admin.open` |
| `/lottery reload` | 重载配置 | `lottery.admin.reload` |
| `/lottery info <玩家>` | 查询玩家通知邮箱 | `lottery.admin.email` |
| `/lottery edit <玩家> <邮箱或QQ号>` | 修改玩家通知邮箱 | `lottery.admin.email` |
| `/lottery edit <玩家> clear` | 清除玩家通知邮箱 | `lottery.admin.email` |
| `/lottery draw <daily\|weekly\|holiday>` | 手动开奖 | `lottery.admin.draw` |
| `/lottery preview <daily\|weekly\|holiday>` | 预览当前期状态 | `lottery.admin.preview` |
| `/lottery period <daily\|weekly\|holiday>` | 查看当前期信息 | `lottery.admin.period` |
| `/lottery history [daily\|weekly\|holiday]` | 查看上一期开奖结果 | 当前无权限检查 |
| `/lottery ledger <玩家>` | 查看玩家最近彩票流水 | `lottery.admin.ledger` |
| `/lottery pool add <daily\|weekly\|holiday> <金额>` | 给当前期增加额外奖池 | `lottery.admin.pool` |
| `/lottery mailtest <玩家>` | 发送测试邮件 | `lottery.admin.mailtest` |
| `/lottery help` | 查看管理员帮助 | `lottery.admin.help` |

## 配置说明

核心经济账户：

```yaml
config-version: 2

settings:
  system-account: "lottery"
```

`config-version` 用于后续增量升级。旧配置升级时，插件只追加缺失的新配置段落，不会整份覆盖已有生产配置。

玩家购票后，资金会进入该账户。派奖和退款也会从该账户扣除。

彩票价格和奖池比例：

```yaml
daily:
  price: 100
  max-purchases-per-player: 2
  min-total-pool: 1200
  reward-pool-percent: 90
  house-pool-percent: 10
```

说明：

- `price`：每注价格。
- `max-purchases-per-player`：每人每期限购。
- `min-total-pool`：最低显示奖池，达到后才开奖。
- `reward-pool-percent`：购票金额进入显示奖池和派奖池的比例。
- `house-pool-percent`：保留比例。实际会按 `100 - reward-pool-percent` 归一化。

奖项配置：

```yaml
rewards:
  first:
    winners: 1
    pool-percent: 50
  second:
    winners: 1
    pool-percent: 25
  third:
    winners: 2
    pool-percent: 25
```

`pool-percent` 是奖金池占比，不是中奖概率。如果某个奖项有多名中奖者，该档总奖金会由这些中奖者平分。

## 开奖机制

插件按彩票票据抽奖：

- 每买 1 注，获得 1 次抽奖机会。
- 买 2 注的玩家，基础中奖机会约为买 1 注玩家的 2 倍。
- 默认同一玩家在同一期同种彩票中最多中一个奖。
- 开奖顺序为一等奖、二等奖、三等奖。
- 如果参与人数不足，缺少的奖项不会强行补齐，对应奖金会滚入下一期。

## 邮件通知

SMTP 示例：

```yaml
mail:
  enabled: true
  smtp:
    host: "smtp.qq.com"
    port: 465
    ssl: true
    starttls: false
    username: "your@qq.com"
    password: "smtp-auth-code"
  sender:
    from: "your@qq.com"
    name: "建筑彩票通知"
```

玩家可以绑定 QQ 号或邮箱：

```text
/lottery bind 123456789
/lottery bind player@example.com
```

纯数字会自动识别为 QQ 邮箱。

## 数据库

插件使用 SQLite，本地数据库位于：

```text
plugins/lottery/lottery.db
```

主要数据表：

- `periods`：期数和下次开奖时间
- `tickets`：购票记录
- `awards`：中奖记录
- `ledger`：彩票流水
- `emails`：玩家邮箱绑定

## 许可证

本项目使用 MIT License 开源。
