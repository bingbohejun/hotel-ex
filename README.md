# 小旅馆（hotel-ex）

《奇妙商店物语》附属 Mod——新增**小旅馆**店型：把店面改成一家温馨的旅馆，前台接待为房客办理入住与退房，客房保洁打扫床位，房费按住宿晚数结算。

- Minecraft **1.21.1** / NeoForge **21.1.251** / Java **21**
- 依赖本体：`wonder_shop_story_core` **[1.6.7, 1.6.8)**
- 协议：AGPL-3.0

## 玩法

| 机制 | 说明 |
| --- | --- |
| 新店型 | 选店列表新增“小旅馆”，签约后自动摆放前台收银台与初始床位 |
| 入住登记 | 房客到前台排队，前台接待按服务属性计时办理入住 |
| 房间管理 | 房间 = 任意颜色的床 + 床边站位，自动扫描；干净空房数即接待容量 |
| 定价玩法 | 三档房型分档定价，价格影响客流；比赛日与夜间自动上浮，长住自动折扣 |
| 按时长计费 | 房客上床就寝按游戏时间累计晚数，钟点房半价引流 |
| 退房结算 | 复用本体账本与事件；早餐/饮品加购并入账单 |
| 评价兼容 | 普通房客走本体粉丝/人气体系；特殊房客走剧情与答谢 |
| 清洁维护 | 退房后房间待清洁，客房保洁打扫后才可再入住；脏房隔夜可能引发投诉赔款 |
| 货架零售 | 用本体便利店货架摆货物架，服务员为点单房客送货，补货员补满货架 |
| 经营计划 | 旅行团包房（5 天客流提升）、深度大扫除（立即清空脏房） |
| 旅游联动 | 派员工前往温泉疗养地，归来次日产生包房客流脉冲 |
| 收益台账 | 房态一览、收入统计、14 日收入图、连锁多店聚合、价目编辑 |
| 服务上门 | 房客住宿中随机提出小需求，员工上门服务获得小费 |
| 旅馆扩展 | 创造模式物品栏页签：客房门牌（四态房态显示）、房卡（退房纪念品）、大堂饮品台 |

## 配置文件（`config/wss_hotel-server.toml`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `hotel.basePrice` | 40 | 需求弹性基准价 |
| `hotel.nightTicks` | 10000 | 1 晚对应游戏刻 |
| `hotel.overtimeRate` | 1.5 | 超时每晚加收倍率 |
| `hotel.cleanTicks` | 200 | 单房打扫耗时 |
| `hotel.lyingPose` | true | 就寝姿态（渲染异常时关闭） |
| `hotel.bedBlocks` | 原版 16 色床 | 床位识别方块列表 |
| `hotel.registerWorkTicks` | 60 | 前台单次办理基础工作刻 |
| `hotel.priceStandard/Comfort/Deluxe` | 40/55/75 | 新店默认价目（可在台账页按店修改） |
| `hotel.priceMax` | 3000 | 游戏内定价上限 |
| `hotel.hourlyRate / hourlyChance` | 0.5 / 0.2 | 钟点房价格比例与选择概率 |
| `hotel.contestTraffic / contestPriceBonus` | 1.6 / 0.2 | 比赛日客流倍率与房价上浮 |
| `hotel.nightPriceBonus` | 0.2 | 夜间入住加价 |
| `hotel.tierComfortBlocks / tierDeluxeBlocks` | 见文件 | 舒适/豪华档床色映射 |
| `hotel.waitingChairs` | 空 | 候位椅方块列表（留空自动识别） |
| `hotel.breakfastChance / breakfastMarkup` | 0.3 / 1.2 | 早餐点单概率与加价系数 |
| `hotel.complaintChance / complaintFineRatio` | 0.2 / 0.5 | 脏房投诉概率与赔款比例 |
| `hotel.levelThresholds` | 10/30/80/200/500 | 旅馆等级 2~5 所需累计间夜 |
| `hotel.longStayDiscount / longStayMinNights` | 0.9 / 3 | 长租折扣与门槛晚数 |
| `hotel.serviceChance / serviceMaxPerStay / serviceTicks` | 0.4 / 2 / 60 | 上门服务频率、上限与耗时 |
| `hotel.serviceTipChance` | 0.4 | 服务小费概率 |
| `hotel.serviceDemands` | 见文件 | 服务需求文案池 |
| `hotel.seasonTraffic / seasonPrice` | 见文件 | 四季（春/夏/秋/冬）客流倍率与房价系数 |
| `hotel.elasticityBase` | 14000 | 需求弹性基准价 |
| `hotel.drinkChance / roomCardChance` | 0.25 / 0.1 | 退房饮品购买概率与房卡掉落概率 |

## 安装

1. 安装《奇妙商店物语》1.6.7 整合包。
2. 把 Mod jar 放入 `mods`（客户端与服务端都要装，版本一致）。
3. 编译：将本体 jar 与 `ldlib2` jar 放入 `libs/` 后执行 `gradlew.bat build`。

## 协议

本项目以 **GNU Affero General Public License v3.0（AGPL-3.0）** 发布，详见 `LICENSE`。
