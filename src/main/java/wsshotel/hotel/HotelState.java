package wsshotel.hotel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 小旅馆的店铺挂载数据（Gson 序列化，随店铺存档保存）。
 * 只允许使用数字、字符串、列表与映射等简单结构。
 *
 * schema 2：新增定价、早餐、投诉、旅游脉冲、房间收入等字段；
 * 旧档缺失字段依赖 Gson 的无参构造默认值，{@link #migrate()} 负责版本推进与清整。
 */
public class HotelState {

    public int schemaVersion = 4;

    /** 数据版本号（客户端据此丢弃过期台账）。 */
    public transient long rev;

    // —— 服务端运行缓存（不落盘），由业务 tick 每 20 刻刷新 ——
    /** 房态缓存：{x, y, z, tier, status}；status 0 空闲 / 1 入住中 / 2 待清洁 / 3 保洁中。 */
    public transient List<int[]> roomCache = new ArrayList<>();
    public transient boolean hasDeluxe;
    /** 当前业务日（rollDay 后更新，供客流权重查询，避免依赖 server 引用）。 */
    public transient long currentDay = Long.MIN_VALUE;
    /** 当前节气序号（0=立春；-1=未同步）。业务 tick 刷新。 */
    public transient int currentTerm = -1;

    // —— 二期 F4 等级线 ——
    /** 旅馆等级 1~5，由累计间夜推导（settle 后刷新）。 */
    public int level = 1;

    // —— 二期 F6 房客服务事件 ——
    public long serviceIncome;
    public long slowCount;
    /** 进行中的上门服务任务，键为任务 UUID。 */
    public final Map<String, HotelState.ServiceJob> serviceJobs = new LinkedHashMap<>();
    /** 常客简表（房客 UUID → 入住次数，为后续会员体系预留；上限 256 条）。 */
    public final Map<String, Long> guestHistory = new LinkedHashMap<>();
    /** 持久化房间数（连锁台账用；业务 tick 刷新）。 */
    public int roomCount;

    // —— 二期 F1 门牌绑定 / F2 饮品台 ——
    /** 门牌绑定：床头 pos（asLong 字符串）→ 门牌 pos（asLong 字符串）。 */
    public final Map<String, String> signByRoom = new LinkedHashMap<>();
    /** 饮品单价（台账页可改）。 */
    public int drinkPrice = 200;
    /** 饮品累计收入。 */
    public long drinkIncome;
    /** 饮品台站位缓存（业务 tick 刷新，不落盘）。 */
    public transient java.util.List<net.minecraft.core.BlockPos> drinkCounters = new java.util.ArrayList<>();

    public java.util.List<net.minecraft.core.BlockPos> drinkCountersList() {
        return drinkCounters;
    }

    // 性能：货架渲染签名缓存（键=货架 pos 字符串，值=上次推送的 products+counts 摘要；不落盘）。
    public transient final Map<String, String> shelfDisplaySig = new LinkedHashMap<>();

    /** 计算货架三层显示签名，内容变化时签名随之变化。 */
    public String displaySignature(org.merlin204.wss.shop.RetailState.Shelf shelf) {
        StringBuilder signature = new StringBuilder(64);
        for (int i = 0; i < 3; i++) {
            signature.append(shelf.products[i] == null ? "" : shelf.products[i]).append("|").append(shelf.count[i]).append("|");
        }
        return signature.toString();
    }

    // —— P1 定价（玩家可在台账/客房升级页修改；各档位有独立上下限，migrate 统一钳制） ——
    public int standardPrice = 10000;
    public int comfortPrice = 14000;
    public int deluxePrice = 19000;
    /** 钟点房单次结算价（固定价，不随每晚价浮动）。 */
    public int hourlyPrice = 1200;
    public boolean hourlyEnabled = true;

    // —— P3 客房升级：床位 pos（asLong 字符串）→ 档位覆盖（0 标准 / 1 舒适 / 2 豪华） ——
    public final Map<String, Integer> roomTiers = new LinkedHashMap<>();

    // —— P2 早餐（点单加购制） ——
    public boolean breakfastEnabled;
    public final List<String> breakfastDishes = new ArrayList<>();
    public long breakfastIncome;

    // —— P2 旅游脉冲 ——
    public long journeyPulseDay = Long.MIN_VALUE;
    public long journeyPulseGuests;

    // —— P2 投诉 ——
    public long totalComplaints;
    public int complaintsToday;

    // —— 收益统计 ——
    public long totalCheckins;
    public long totalNights;
    public long totalIncome;
    public long totalCleans;
    public long todayGameDay = Long.MIN_VALUE;
    public long todayIncome;
    public long todayCheckins;
    public double peakOccupancy;
    /** 按游戏日保存的历史房费收入（保留最近 14 天）。 */
    public final TreeMap<String, Long> dailyIncome = new TreeMap<>();
    /** 按游戏日保存的历史入住率（保留最近 14 天）。 */
    public final TreeMap<String, Double> dailyOccupancy = new TreeMap<>();
    /** 房间累计收入（床头坐标字符串 → 金额）。 */
    public final Map<String, Long> roomIncome = new LinkedHashMap<>();

    /** 进行中的住宿，键为订单 UUID 字符串。 */
    public final Map<String, HotelState.Stay> stays = new LinkedHashMap<>();

    /** 待清洁房间，键为床铺锚点（BlockPos.asLong 的字符串）。 */
    public final Map<String, Boolean> dirty = new LinkedHashMap<>();

    /** 脏房标记时的业务日（投诉计时用）。 */
    public final Map<String, Long> dirtySince = new LinkedHashMap<>();

    /** 进行中的保洁任务，键为保洁员工 UUID 字符串。 */
    public final Map<String, HotelState.CleanJob> cleans = new LinkedHashMap<>();

    /** 候位分配：订单 UUID 字符串 → 候位椅坐标（asLong 字符串）。 */
    public final Map<String, Long> waitingSeats = new LinkedHashMap<>();

    // —— 前台服务位互斥（防拥堵，瞬态：重启后自然重选） ——
    /** 当前占用收银台办理业务的订单 UUID 字符串；同一时刻只放一名客人使用前台站位。 */
    public transient String deskUser;
    /** 占用开始时的 order.age（仅与占用者自身的 age 比较，判断"久走不至"轮换）。 */
    public transient long deskClaimedAt;
    /** 占用者是否已走到前台站位。 */
    public transient boolean deskArrived;
    /** 前台排队开始时刻（各订单自身的 order.age），用于排队超时——退房订单 age 很大，不能用绝对值比较。 */
    public final transient Map<String, Long> deskWaitSince = new LinkedHashMap<>();

    // —— P3 货物架零售 ——
    // 1.4.1 起货架配置直接存本体 RetailState（r.retail.shelves），与便利店同款备货 UI 原生打通；
    // 下面 shelves 表仅作为旧档迁移来源（migrate 后清空）。
    /** 旧档货架配置（1.4.0 及之前）：键为货架下半块 pos（asLong 字符串）。 */
    public final Map<String, Shelf> shelves = new LinkedHashMap<>();
    /** 旧档是否已迁移到本体 RetailState。 */
    public transient boolean retailMigrated;
    /** 顾客货物需求（住客随机产生，服务员按单送货）。 */
    public final List<Demand> demands = new ArrayList<>();
    /** 服务员任务队列（送货/补货）。 */
    public final List<WaiterTask> tasks = new ArrayList<>();
    /** 商品零售累计收入。 */
    public long goodsIncome;

    /**
     * 单个货架：三层，每层陈列一种商品，数量 0..该商品的单层容量（本体 RetailShelfDisplays）。
     * 层状态与货架方块实体（RetailShelfBlockEntity）通过 display() 同步——
     * 这里是权威数据，BE 仅承载渲染。
     * 注意：旧档 JSON 的 goods(字符串)/count(int) 键名必须由同名旧字段吸收，
     * 否则 Gson 解析 String[] 会直接炸档——迁移逻辑在 migrate()。
     */
    public static class Shelf {
        /** 每层陈列的商品 id（空串 = 未配置）。 */
        public String[] layers = new String[]{"", "", ""};
        /** 每层现存数量。 */
        public int[] counts = new int[3];

        // —— 旧档（单商品版 v2）字段：migrate() 搬入第 1 层后清空 ——
        public String goods;
        public int count;

        public String goods(int layer) {
            return layer >= 0 && layer < 3 ? layers[layer] : "";
        }

        public int count(int layer) {
            return layer >= 0 && layer < 3 ? counts[layer] : 0;
        }

        public boolean configured(int layer) {
            String g = goods(layer);
            return g != null && !g.isEmpty();
        }

        /** 该货架任一层有现货。 */
        public boolean hasStock() {
            for (int i = 0; i < 3; i++) {
                if (configured(i) && counts[i] > 0) {
                    return true;
                }
            }
            return false;
        }

        /** 该货架任一层配置了指定商品且有货。 */
        public boolean hasStock(String id) {
            for (int i = 0; i < 3; i++) {
                if (configured(i) && id.equals(layers[i]) && counts[i] > 0) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 一条顾客货物需求。 */
    public static class Demand {
        public String orderId;
        public String customer;
        public String goods;
        public long createdAt;
    }

    /** 服务员任务：DELIVER（取货送达）/ RESTOCK（补满货架各层，免费）。 */
    public static class WaiterTask {
        public String type;
        public String waiter;
        public String shelf;
        public int layer = -1;
        public String orderId;
        public String customer;
        public String goods;
        public int phase;
        public long createdAt;
        public long stuckSince; // 当前阶段开始时间（卡死检测）
        public int resets; // 卡死重置次数（≥2 放弃任务）
    }


    /** 单次住宿记录。 */
    public static class Stay {
        public String orderId;
        public String customer;
        public long head;          // 床头 BlockPos.asLong
        public long approach;      // 床边站位 BlockPos.asLong
        public long checkinGameTime;
        public long checkinDay;    // 入住时的游戏日
        public long plannedNights; // 计划住宿晚数
        public long quarterNights; // 已住晚数（0.25 晚为粒度）
        public boolean lying;      // 是否正躺在床上
        public int citizenType;
        public int tier;           // 房型 0 标准 / 1 舒适 / 2 豪华
        public boolean hourly;     // 钟点房
        public boolean breakfastOrdered;
        public String breakfastDish = "";
        public boolean visitCounted;
        public String receptionist = ""; // 登记时的接待员 UUID
        // —— F6 服务事件 ——
        public int servicesCreated; // 已产生的需求数
        public int servicesDone;    // 已完成的服务数
        public boolean slowed;      // 有需求超时未满足（结算满意度 -2）
        // —— F2 退房饮品 ——
        public boolean drinkPending; // 决定顺路买饮品
        public boolean drinkDone;    // 饮品环节结束（买了或跳过）
        public double drinkWork;     // 柜台停留进度
        public int drinkCharge;      // 锁定的饮品金额（结算并入总额）
    }

    /** F6 单次上门服务任务（毛巾/热水等，区别于货物架的商品送货）。 */
    public static class ServiceJob {
        public String id;
        public String orderId;     // 所属住宿订单
        public String worker = ""; // 执行员工 UUID（空=待派）
        public long head;          // 床头锚点
        public long approach;      // 床边站位
        public String demand = ""; // 需求文案
        public double progress;    // 工作进度
        public long createdDay;    // 提出日（超时怠慢判定）
    }

    /** 单次保洁任务。 */
    public static class CleanJob {
        public String id;
        public String worker;
        public long head;
        public long approach;
        public double progress;
    }

    /** 旧档迁移与常规清整。幂等，可在每次取用时调用。 */
    public void migrate() {
        if (schemaVersion < 2) {
            // 1→2：新增字段全部依赖 Gson 缺省值；旧档脏房无时间戳，投诉从升级后新标记的脏房开始计。
            dirtySince.clear();
            schemaVersion = 2;
        }
        if (schemaVersion < 3) {
            // 2→3：货架改三层结构（对接本体便利店货架）；旧单商品数据迁入第 1 层。
            for (Shelf s : shelves.values()) {
                if (s.goods != null && !s.goods.isEmpty()) {
                    s.layers[0] = s.goods;
                    s.counts[0] = Math.min(s.count, Math.max(1, HotelGoods.perLayer(s.goods)));
                    s.goods = null;
                    s.count = 0;
                }
            }
            schemaVersion = 3;
        }
        if (schemaVersion < 4) {
            // 3→4：等级/服务事件字段全部依赖 Gson 缺省值，无重命名；仅推进版本。
            schemaVersion = 4;
        }
        // 各档位独立钳制（老档低价自动升到新区间下限）。
        standardPrice = java.lang.Math.clamp(standardPrice, 10000, 15000);
        comfortPrice = java.lang.Math.clamp(comfortPrice, 14000, 21000);
        deluxePrice = java.lang.Math.clamp(deluxePrice, 19000, 28000);
        hourlyPrice = java.lang.Math.clamp(hourlyPrice, 1200, 2000);
    }

    /** 数据变更后调用：版本号自增，使台账客户端丢弃缓存。 */
    public void bump() {
        rev++;
    }

    public int priceOf(int tier) {
        // F4 等级线：豪华档定价 2 级解锁（未达标按舒适档计价；已摆放的豪华床不清除）。
        int effective = tier == 2 && level < 2 ? 1 : tier;
        return switch (effective) {
            case 2 -> deluxePrice;
            case 1 -> comfortPrice;
            default -> standardPrice;
        };
    }

    public int[] prices() {
        return new int[]{standardPrice, comfortPrice, deluxePrice};
    }

    /** F4：由累计间夜推导等级（1~5），并返回是否发生升级。 */
    public boolean updateLevel() {
        int next = 1;
        for (Integer threshold : wsshotel.HotelConfig.LEVEL_THRESHOLDS.get()) {
            if (totalNights >= threshold) {
                next++;
            }
        }
        int capped = Math.min(5, next);
        if (capped > level) {
            level = capped;
            bump();
            return true;
        }
        return false;
    }

    /** F4：等级称号。 */
    public String title() {
        return switch (level) {
            case 5 -> "五星旅馆";
            case 4 -> "知名旅馆";
            case 3 -> "精品旅馆";
            case 2 -> "舒适旅馆";
            default -> "新开旅馆";
        };
    }

    /** F4：当前等级允许的早餐菜位数（3 → 4 → 5）。 */
    public int breakfastCap() {
        return level >= 3 ? 5 : level >= 2 ? 4 : 3;
    }

    /** 跨游戏日时滚动日结数据，并裁剪历史。 */
    public void rollDay(long gameDay, int roomCount) {
        if (todayGameDay != Long.MIN_VALUE && todayGameDay != gameDay) {
            dailyIncome.put(Long.toString(todayGameDay), todayIncome);
            dailyOccupancy.put(Long.toString(todayGameDay),
                    roomCount > 0 ? Math.min(1.0, stays.size() / (double) roomCount) : 0.0);
            while (dailyIncome.size() > 14) {
                dailyIncome.pollFirstEntry();
            }
            while (dailyOccupancy.size() > 14) {
                dailyOccupancy.pollFirstEntry();
            }
        todayIncome = 0;
        todayCheckins = 0;
        complaintsToday = 0;
        // 零售补货免费（对齐便利店货架“补货员免费补满卖空的货架”），无库房概念。
    }
        currentDay = gameDay;
        todayGameDay = gameDay;
    }

    /** 是否有进行中的住宿占用该床。 */
    public boolean stayingAt(long head) {
        for (Stay stay : stays.values()) {
            if (stay.head == head) {
                return true;
            }
        }
        return false;
    }

    /** 该床是否被保洁任务占用。 */
    public boolean cleaningAt(long head) {
        for (CleanJob job : cleans.values()) {
            if (job.head == head) {
                return true;
            }
        }
        return false;
    }

    /** 房间是否空闲可入住：无住客、不脏、无保洁占用。 */
    public boolean roomFree(long head) {
        return !stayingAt(head) && !Boolean.TRUE.equals(dirty.get(Long.toString(head))) && !cleaningAt(head);
    }

    /** 生成并登记一次新的住宿。 */
    public Stay beginStay(String orderId, String customer, HotelRooms.Room room, long gameTime, long gameDay, int citizenType) {
        Stay stay = new Stay();
        stay.orderId = orderId;
        stay.customer = customer;
        stay.head = room.head().asLong();
        stay.approach = room.approach().asLong();
        stay.checkinGameTime = gameTime;
        stay.checkinDay = gameDay;
        stay.citizenType = citizenType;
        stay.tier = room.tier();
        stay.plannedNights = 1 + Math.floorMod(Long.hashCode(orderId == null ? 0L : orderId.hashCode()), 3);
        stays.put(orderId, stay);
        bump();
        return stay;
    }

    /** 结束住宿：移除记录并把房间标记为待清洁；未完成的上门服务任务一并撤销。 */
    public Stay endStay(String orderId, boolean markDirty, long gameDay) {
        Stay stay = stays.remove(orderId);
        if (stay != null) {
            if (markDirty) {
                String key = Long.toString(stay.head);
                dirty.put(key, Boolean.TRUE);
                dirtySince.putIfAbsent(key, gameDay);
            }
            waitingSeats.remove(orderId);
            serviceJobs.values().removeIf(job -> orderId.equals(job.orderId));
            bump();
        }
        return stay;
    }
}
