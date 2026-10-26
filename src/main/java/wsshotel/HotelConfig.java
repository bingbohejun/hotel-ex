package wsshotel;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 小旅馆附属的服务端配置。
 */
public final class HotelConfig {
    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue BASE_PRICE;
    public static final ModConfigSpec.IntValue NIGHT_TICKS;
    public static final ModConfigSpec.DoubleValue OVERTIME_RATE;
    public static final ModConfigSpec.IntValue CLEAN_TICKS;
    public static final ModConfigSpec.BooleanValue LYING_POSE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BED_BLOCKS;
    public static final ModConfigSpec.IntValue REGISTER_WORK_TICKS;

    // —— P1 定价与计费 ——
    public static final ModConfigSpec.IntValue PRICE_STANDARD;
    public static final ModConfigSpec.IntValue PRICE_COMFORT;
    public static final ModConfigSpec.IntValue PRICE_DELUXE;
    public static final ModConfigSpec.IntValue PRICE_MAX;
    public static final ModConfigSpec.DoubleValue HOURLY_RATE;
    public static final ModConfigSpec.DoubleValue HOURLY_CHANCE;
    public static final ModConfigSpec.DoubleValue CONTEST_TRAFFIC;
    public static final ModConfigSpec.DoubleValue CONTEST_PRICE_BONUS;
    public static final ModConfigSpec.DoubleValue NIGHT_PRICE_BONUS;

    // —— P1 房型分级 ——
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TIER_COMFORT_BLOCKS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TIER_DELUXE_BLOCKS;

    // —— P1 候位椅 ——
    public static final ModConfigSpec.ConfigValue<List<? extends String>> WAITING_CHAIRS;

    // —— P2 早餐与投诉 ——
    public static final ModConfigSpec.DoubleValue BREAKFAST_CHANCE;
    public static final ModConfigSpec.DoubleValue BREAKFAST_MARKUP;
    public static final ModConfigSpec.DoubleValue COMPLAINT_CHANCE;
    public static final ModConfigSpec.DoubleValue COMPLAINT_FINE_RATIO;

    // —— 二期 F4 等级线 / F5 长租折扣 / F6 服务事件 / F7 节气淡旺季 ——
    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> LEVEL_THRESHOLDS;
    public static final ModConfigSpec.DoubleValue LONG_STAY_DISCOUNT;
    public static final ModConfigSpec.IntValue LONG_STAY_MIN_NIGHTS;
    public static final ModConfigSpec.DoubleValue SERVICE_CHANCE;
    public static final ModConfigSpec.IntValue SERVICE_MAX_PER_STAY;
    public static final ModConfigSpec.IntValue SERVICE_TICKS;
    public static final ModConfigSpec.DoubleValue SERVICE_TIP_CHANCE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SERVICE_DEMANDS;
    public static final ModConfigSpec.ConfigValue<List<? extends Double>> SEASON_TRAFFIC;
    public static final ModConfigSpec.ConfigValue<List<? extends Double>> SEASON_PRICE;
    public static final ModConfigSpec.DoubleValue ELASTICITY_BASE;
    public static final ModConfigSpec.DoubleValue DRINK_CHANCE;
    public static final ModConfigSpec.DoubleValue ROOM_CARD_CHANCE;

    static {
        BUILDER.push("hotel");
        BASE_PRICE = BUILDER.comment("每晚基础房价（金币），作为需求弹性的基准价")
            .defineInRange("basePrice", 40, 1, 100000);
        NIGHT_TICKS = BUILDER.comment("住宿 1 晚对应的游戏刻（20 刻 = 1 秒）")
            .defineInRange("nightTicks", 10000, 1200, 24000 * 3);
        OVERTIME_RATE = BUILDER.comment("超过预定晚数后，每一晚的加收倍率")
            .defineInRange("overtimeRate", 1.5, 1.0, 10.0);
        CLEAN_TICKS = BUILDER.comment("打扫一间客房需要的工作刻")
            .defineInRange("cleanTicks", 200, 20, 24000);
        LYING_POSE = BUILDER.comment("顾客就寝时是否在床上躺卧（若渲染异常可关闭，改为床边休息）")
            .define("lyingPose", true);
        BED_BLOCKS = BUILDER.comment("识别为床位的方块 ID（默认原版 16 色床）")
            .defineListAllowEmpty("bedBlocks",
                List.of(
                    "minecraft:white_bed", "minecraft:orange_bed", "minecraft:magenta_bed",
                    "minecraft:light_blue_bed", "minecraft:yellow_bed", "minecraft:lime_bed",
                    "minecraft:pink_bed", "minecraft:gray_bed", "minecraft:light_gray_bed",
                    "minecraft:cyan_bed", "minecraft:purple_bed", "minecraft:blue_bed",
                    "minecraft:brown_bed", "minecraft:green_bed", "minecraft:red_bed",
                    "minecraft:black_bed"
                ),
                () -> "minecraft:red_bed", o -> o instanceof String s && !s.isBlank());
        REGISTER_WORK_TICKS = BUILDER.comment("前台办理入住/退房一次所需的基础工作刻（按接待员服务属性加速）")
            .defineInRange("registerWorkTicks", 60, 10, 1200);

        BUILDER.comment("定价（玩家可在台账页按店修改，上限 priceMax）");
        PRICE_STANDARD = BUILDER.defineInRange("priceStandard", 40, 1, 3000);
        PRICE_COMFORT = BUILDER.defineInRange("priceComfort", 55, 1, 3000);
        PRICE_DELUXE = BUILDER.defineInRange("priceDeluxe", 75, 1, 3000);
        PRICE_MAX = BUILDER.comment("游戏内可设置的单晚房价上限")
            .defineInRange("priceMax", 3000, 1, 100000);
        HOURLY_RATE = BUILDER.comment("钟点房按标准价的该比例收费（0.5 = 半晚价）")
            .defineInRange("hourlyRate", 0.5, 0.1, 1.0);
        HOURLY_CHANCE = BUILDER.comment("房客选择钟点房的概率")
            .defineInRange("hourlyChance", 0.2, 0.0, 1.0);
        CONTEST_TRAFFIC = BUILDER.comment("本体比赛日（旺日）的客流倍率")
            .defineInRange("contestTraffic", 1.6, 1.0, 5.0);
        CONTEST_PRICE_BONUS = BUILDER.comment("比赛日房价上浮比例")
            .defineInRange("contestPriceBonus", 0.2, 0.0, 2.0);
        NIGHT_PRICE_BONUS = BUILDER.comment("夜间时段（游戏时间 13000 之后）入住的加价比例")
            .defineInRange("nightPriceBonus", 0.2, 0.0, 2.0);

        BUILDER.comment("房型分级：床方块 ID → 舒适/豪华档（未列出的为标准档）");
        TIER_COMFORT_BLOCKS = BUILDER.defineListAllowEmpty("tierComfortBlocks",
            List.of("minecraft:red_bed", "minecraft:orange_bed", "minecraft:yellow_bed", "minecraft:lime_bed",
                    "minecraft:pink_bed", "minecraft:cyan_bed", "minecraft:light_blue_bed", "minecraft:green_bed"),
            () -> "minecraft:red_bed", o -> o instanceof String s && !s.isBlank());
        TIER_DELUXE_BLOCKS = BUILDER.defineListAllowEmpty("tierDeluxeBlocks",
            List.of("minecraft:blue_bed", "minecraft:purple_bed", "minecraft:magenta_bed", "minecraft:black_bed"),
            () -> "minecraft:purple_bed", o -> o instanceof String s && !s.isBlank());

        WAITING_CHAIRS = BUILDER.comment("候位椅方块 ID（留空则自动识别 ID 含 chair 的方块）")
            .defineListAllowEmpty("waitingChairs", List.of(), () -> "minecraft:oak_stairs",
                o -> o instanceof String s && !s.isBlank());

        BUILDER.comment("早餐与投诉");
        BREAKFAST_CHANCE = BUILDER.comment("开启早餐后，房客入住时点单早餐的概率")
            .defineInRange("breakfastChance", 0.3, 0.0, 1.0);
        BREAKFAST_MARKUP = BUILDER.comment("早餐加购价 = 菜品基础价 × 该系数")
            .defineInRange("breakfastMarkup", 1.2, 1.0, 5.0);
        COMPLAINT_CHANCE = BUILDER.comment("每间脏房每天引发投诉的概率")
            .defineInRange("complaintChance", 0.2, 0.0, 1.0);
        COMPLAINT_FINE_RATIO = BUILDER.comment("投诉赔款 = 标准房价 × 该系数（负向流水）")
            .defineInRange("complaintFineRatio", 0.5, 0.0, 5.0);

        BUILDER.comment("二期：等级线 / 长租折扣 / 服务事件 / 节气淡旺季");
        LEVEL_THRESHOLDS = BUILDER.comment("旅馆等级 2~5 级所需的累计间夜阈值（1 级为起始级）")
            .defineListAllowEmpty("levelThresholds", List.of(10, 30, 80, 200, 500),
                () -> 10, o -> o instanceof Integer i && i > 0);
        LONG_STAY_DISCOUNT = BUILDER.comment("长租折扣（计划晚数达到门槛时对基础房费生效）")
            .defineInRange("longStayDiscount", 0.9, 0.5, 1.0);
        LONG_STAY_MIN_NIGHTS = BUILDER.comment("长租折扣的计划晚数门槛")
            .defineInRange("longStayMinNights", 3, 2, 30);
        SERVICE_CHANCE = BUILDER.comment("房客每半晚提出服务需求的概率")
            .defineInRange("serviceChance", 0.4, 0.0, 1.0);
        SERVICE_MAX_PER_STAY = BUILDER.comment("单次住宿最多产生的服务需求数")
            .defineInRange("serviceMaxPerStay", 2, 0, 10);
        SERVICE_TICKS = BUILDER.comment("员工完成一次上门服务的工作刻")
            .defineInRange("serviceTicks", 60, 20, 24000);
        SERVICE_TIP_CHANCE = BUILDER.comment("服务完成后获得小费的概率（5~15 金）")
            .defineInRange("serviceTipChance", 0.4, 0.0, 1.0);
        SERVICE_DEMANDS = BUILDER.comment("服务需求文案池")
            .defineListAllowEmpty("serviceDemands",
                List.of("想要一条干净的毛巾", "想要一杯热水", "麻烦明早叫醒", "想多要一个枕头", "问一下附近有什么好玩的"),
                () -> "想要一条干净的毛巾", o -> o instanceof String s && !s.isBlank());
        SEASON_TRAFFIC = BUILDER.comment("四季客流倍率（春/夏/秋/冬，每季 6 个节气）")
            .defineListAllowEmpty("seasonTraffic", List.of(1.2, 0.85, 1.0, 1.25),
                () -> 1.0, o -> o instanceof Double d && d > 0.0);
        SEASON_PRICE = BUILDER.comment("四季房价系数（春/夏/秋/冬）")
            .defineListAllowEmpty("seasonPrice", List.of(1.1, 0.95, 1.0, 1.15),
                () -> 1.0, o -> o instanceof Double d && d > 0.0);
        ELASTICITY_BASE = BUILDER.comment("需求弹性基准价：三档均价与该值相当时客流不受抑制（改物价体系时同步调整）")
            .defineInRange("elasticityBase", 14000.0, 1.0, 1000000.0);
        BUILDER.comment("二期内容项：门牌 / 饮品台");
        DRINK_CHANCE = BUILDER.comment("房客退房前顺路购买饮品的概率（无可达饮品台时不触发）")
            .defineInRange("drinkChance", 0.25, 0.0, 1.0);
        ROOM_CARD_CHANCE = BUILDER.comment("退房后房间内留下房卡纪念品的概率")
            .defineInRange("roomCardChance", 0.1, 0.0, 1.0);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    /** 房价上限（配置项热读取）。 */
    public static int priceMax() {
        return PRICE_MAX.get();
    }

    private HotelConfig() {
    }
}
