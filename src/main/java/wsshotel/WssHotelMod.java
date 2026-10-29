package wsshotel;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.merlin204.wss.api.RegisterWssContentEvent;
import org.merlin204.wss.api.WssEvents;
import org.merlin204.wss.shop.FurnitureCatalog;
import org.merlin204.wss.shop.ShopCapability;
import org.merlin204.wss.shop.ShopData;
import org.merlin204.wss.shop.ShopDialogue;
import org.merlin204.wss.shop.ShopFacilities;
import org.merlin204.wss.shop.ShopJob;
import org.merlin204.wss.shop.SpecialCustomers;
import wsshotel.hotel.HotelPlans;
import wsshotel.hotel.HotelRooms;
import wsshotel.hotel.HotelShopType;
import wsshotel.hotel.HotelState;
import wsshotel.net.HotelPackets;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 《奇妙商店物语》小旅馆附属：新增“小旅馆”店型。
 * 入住登记 → 客房住宿（按时长计费）→ 前台退房结算 → 客房保洁 → 收益台账。
 */
@Mod(WssHotelMod.MOD_ID)
public final class WssHotelMod {

    public static final String MOD_ID = "wss_hotel";
    public static final ResourceLocation ATTACHMENT = id("hotel_state");

    /** 特殊房客 ID（评价奖励核对用）。 */
    public static final String CUSTOMER_SALESMAN = MOD_ID + ":salesman_lao_zhou";
    public static final String CUSTOMER_WRITER = MOD_ID + ":writer_lin";

    /** 新岗位：客房保洁。 */
    public static ShopJob MAID;

    public WssHotelMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, HotelConfig.SPEC);
        HotelBlocks.register(modBus);
        modBus.addListener(this::registerContent);
        modBus.addListener(HotelPlans::register);
        modBus.addListener(HotelPackets::register);
        // 人流量排查打点：独立于本体业务 tick 的服务器侧监听（见 HotelDiagnostics）。
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(wsshotel.hotel.HotelDiagnostics::onServerTick);
        // 到店漏斗打点：Mixin 暴露的 CityResidents.arrivalObserver（只记小旅馆）。
        wsshotel.hotel.HotelDiagnostics.installArrivalObserver();
        // F1：门牌放置时自动绑定最近的客房（低频玩家操作，允许一次设施扫描）。
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(WssHotelMod::onEntityPlace);
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(wsshotel.client.HotelClientUi::register);
        }
    }

    /** F1：门牌放置 → 绑定同店内最近的客房床头。 */
    private static void onEntityPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (!(event.getPlacedBlock().getBlock() instanceof wsshotel.hotel.RoomSignBlock)) {
            return;
        }
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)
                || !(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        net.minecraft.core.BlockPos pos = event.getPos();
        for (ShopData.Shop shop : ShopData.get(player.server).shops()) {
            if (shop.kind != HotelShopType.kind() || !shop.dimension.equals(level.dimension().location().toString())) {
                continue;
            }
            if (!inBounds(shop.bounds, pos)) {
                continue;
            }
            HotelState hotel = attachment(shop);
            ShopFacilities facilities = org.merlin204.wss.shop.ShopFacilities.scan(level, shop);
            HotelRooms rooms = facilities == null ? wsshotel.hotel.HotelCrowdPolicy.EMPTY
                    : facilities.extension(HotelRooms.class, wsshotel.hotel.HotelCrowdPolicy.EMPTY);
            net.minecraft.core.BlockPos best = null;
            double bestSqr = Double.MAX_VALUE;
            for (var room : rooms.rooms()) {
                double sqr = room.head().distSqr(pos);
                if (sqr < bestSqr) {
                    bestSqr = sqr;
                    best = room.head();
                }
            }
            if (best != null) {
                hotel.signByRoom.put(Long.toString(best.asLong()), Long.toString(pos.asLong()));
                hotel.bump();
                org.merlin204.wss.shop.ShopSessions.feedback(player, shop, "门牌已绑定最近的客房，房态会实时更新。");
            } else {
                org.merlin204.wss.shop.ShopSessions.feedback(player, shop, "店内还没有客房，先摆放床位再挂门牌。");
            }
            return;
        }
    }

    private static boolean inBounds(int[] bounds, net.minecraft.core.BlockPos pos) {
        return bounds != null && bounds.length >= 6
                && pos.getX() >= bounds[0] && pos.getX() <= bounds[3]
                && pos.getY() >= bounds[1] && pos.getY() <= bounds[4]
                && pos.getZ() >= bounds[2] && pos.getZ() <= bounds[5];
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    /** 获取（或惰性创建）某家小旅馆的挂载数据。仅服务器线程调用；旧档自动迁移。 */
    public static HotelState attachment(ShopData.Shop shop) {
        HotelState state = shop.attachments.get(ATTACHMENT, HotelState.class, HotelState::new);
        state.migrate();
        return state;
    }

    private void registerContent(RegisterWssContentEvent event) {
        event.attachment(ATTACHMENT, HotelState.class);

        MAID = event.job(id("maid"), "客房保洁", ShopJob.Ability.EFFICIENCY);

        HotelShopType.kind(event.shop(id("hotel"), "小旅馆", 3,
                org.merlin204.wss.shop.ShopKind.RESTAURANT, HotelShopType::definition));

        registerBedFurniture(event);
        // F2 旅馆饮料机：注册为"工作"类家具（仅旅馆店型可购），描述显示在家具市场。
        event.furniture(WssHotelMod.id("drink_machine"), org.merlin204.wss.shop.FurnitureCatalog.entry(
                "旅馆饮料机", "work", 2, 3, 2, 350,
                "wss_hotel:drink_machine",
                "大堂的立式饮料机。房客退房前会顺路购买饮品。",
                "", java.util.Map.of(), "inside"),
                java.util.Set.of(HotelShopType.kind()));
        registerCustomers(event);
        registerDialogue(event);

        event.journey(id("hot_spring"), wsshotel.hotel.HotelDestinations.hotSpring());

        // 特殊房客评价：只对自己注册的角色发奖（本体对附属 ID 无内置奖励）。
        event.listen(WssEvents.Type.SPECIAL_REVIEWED, id("special_reward"), notice -> {
            var context = notice.context();
            if (context.shop().kind != HotelShopType.kind() || notice.value() < 60.0) {
                return;
            }
            for (org.merlin204.wss.shop.RestaurantData.Order order : context.state().orders) {
                if (!order.id.toString().equals(notice.subject())) {
                    continue;
                }
                String special = order.specialCustomer;
                if (CUSTOMER_SALESMAN.equals(special) || CUSTOMER_WRITER.equals(special)) {
                    String name = SpecialCustomers.get(special) == null ? special : SpecialCustomers.get(special).name();
                    context.changeBalance(120, "房客「" + name + "」的答谢");
                }
                break;
            }
        });

        // 旅游返程：从本旅馆派往温泉的员工归来，次日产生包房脉冲。
        event.listen(WssEvents.Type.JOURNEY_RETURNED, id("journey_pulse"), notice -> {
            var context = notice.context();
            if (context.shop().kind != HotelShopType.kind()
                    || !wsshotel.hotel.HotelDestinations.ID.equals(notice.subject())) {
                return;
            }
            HotelState hotel = attachment(context.shop());
            hotel.journeyPulseDay = HotelApi.businessDay(context.server()) + 1;
            hotel.journeyPulseGuests = Math.max(1, notice.amount());
            hotel.bump();
        });
    }

    private static void registerBedFurniture(RegisterWssContentEvent event) {
        List<String> names = List.of("白色", "橙色", "品红色", "淡蓝色", "黄色", "黄绿色", "粉色", "灰色",
                "淡灰色", "青色", "紫色", "蓝色", "棕色", "绿色", "红色", "黑色");
        List<String> colors = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
        for (int i = 0; i < colors.size(); i++) {
            String item = "minecraft:" + colors.get(i) + "_bed";
            event.furniture(ResourceLocation.parse(item),
                    FurnitureCatalog.entry(names.get(i) + "床", "work", 6, 3, 2, 90,
                            "rest", "供客房使用的床铺。", "", Map.of(), "inside"),
                    Set.of(HotelShopType.kind()));
        }
        // 货架不再自建：直接使用本体便利店货架 wonder_shop_story_core:retail_shelf，
        // 由 HotelSetup.furniture() 白名单放进“工作”页，零售玩法见 HotelBusiness.retail。
    }

    /**
     * P2-C：附属特殊房客。requires 仅 CUSTOMER_PAYMENTS（本体按此判断店型是否接纳），
     * 条件里再显式限定 kind == 小旅馆——否则本体的 recurringVisits 会把他们派到任何支持
     * CUSTOMER_PAYMENTS 的店（便利店、餐厅…），而附属的答谢监听器只对小旅馆生效，
     * 结果是在别的店里满意后空手而归。
     */
    private static void registerCustomers(RegisterWssContentEvent event) {
        ResourceLocation salesman = id("salesman_lao_zhou");
        event.customer(salesman, new org.merlin204.wss.api.AddonCustomer(
                new SpecialCustomers.Character(
                        salesman.toString(), 1, "推销员老周", "行商", "", false,
                        "跑遍各地推销床上用品，最懂一张好床的价值。",
                        "路过城镇时，他会找家旅馆住上一晚，顺便看看同行的床位。",
                        "他更在意床铺是否干净、被褥是否新换。",
                        "住得满意，下次会带来一批新被褥作为谢礼。",
                        7, 1600, 40, 0, Set.of(),
                        "临走时留下了两床新被褥作为谢礼。",
                        "他觉得这里还比不上他见过的那些客栈。"),
                Set.of(ShopCapability.CUSTOMER_PAYMENTS),
                context -> context.shop().kind == HotelShopType.kind(),
                "any"));

        ResourceLocation writer = id("writer_lin");
        event.customer(writer, new org.merlin204.wss.api.AddonCustomer(
                new SpecialCustomers.Character(
                        writer.toString(), 1, "旅行作家林小姐", "旅行作家", "", true,
                        "为写旅行专栏住遍各地旅馆，笔杆子比行李还沉。",
                        "写稿写累了，就会挑个安静的地方小住几日。",
                        "看重房间的安静与格调，只住有豪华床位的旅馆。",
                        "住得满意，她的专栏会提到这家旅馆。",
                        21, 1200, 55, 0, Set.of(),
                        "她在专栏里写下了这家旅馆的好评。",
                        "她合上笔记本，摇头离开了。"),
                Set.of(ShopCapability.CUSTOMER_PAYMENTS),
                context -> context.shop().kind == HotelShopType.kind()
                        && WssHotelMod.attachment(context.shop()).hasDeluxe,
                "afternoon"));
    }

    private static void registerDialogue(RegisterWssContentEvent event) {
        // 家族场景结构必须与本体一致：每一行都带选项（中间行 id=next 推进下一行，
        // 末行 id=finish 结束场景）。全空 options 会让“下一句”被判定为选项不匹配
        // 而重发当前行，表现为对话卡死无法推进。
        java.util.List<org.merlin204.wss.shop.DialoguePackets.Option> NEXT =
                java.util.List.of(new org.merlin204.wss.shop.DialoguePackets.Option("next", ""));
        event.dialogue(id("dialogue"), HotelShopType.kind(), new ShopDialogue.Definition(
                // 主线场景：1/6/99/100 行数需为 4/2/2/3。
                Map.of(
                        "1", List.of(
                                new ShopDialogue.StoryLine("这排床位擦得发亮，被褥叠得整整齐齐。", "OK", NEXT),
                                new ShopDialogue.StoryLine("路过的旅人在门口张望：“这里……能住吗？”", "QUESTION",
                                        java.util.List.of(new org.merlin204.wss.shop.DialoguePackets.Option("next", "当然，请进。"))),
                                new ShopDialogue.StoryLine("“当然可以，”我翻开登记簿，“一间干净房，一晚好梦。”", "YES",
                                        java.util.List.of(new org.merlin204.wss.shop.DialoguePackets.Option("next", "递上房牌。"))),
                                new ShopDialogue.StoryLine("第一晚的房费落进钱箱，小旅馆正式开张了。", "LOVE",
                                        java.util.List.of(new org.merlin204.wss.shop.DialoguePackets.Option("finish", "开业大吉。")))),
                        "6", List.of(
                                new ShopDialogue.StoryLine("客房保洁把最后一间房收拾妥当，床位重新变得雪白。", "OK",
                                        java.util.List.of(new org.merlin204.wss.shop.DialoguePackets.Option("next", "干净房间，客人住得安心。"))),
                                new ShopDialogue.StoryLine("“房间干净，客人住得安心，也会更愿意再来。”", "THINKING",
                                        java.util.List.of(new org.merlin204.wss.shop.DialoguePackets.Option("finish", "继续努力。")))),
                        "99", List.of(
                                new ShopDialogue.StoryLine("深夜，房客的鼾声此起彼伏。", "SLEEPY",
                                        java.util.List.of(new org.merlin204.wss.shop.DialoguePackets.Option("next", "轻手轻脚，别吵醒他们。"))),
                                new ShopDialogue.StoryLine("前台接待守着收银电脑，等最后一位客人退房。", "SILENCE",
                                        java.util.List.of(new org.merlin204.wss.shop.DialoguePackets.Option("finish", "辛苦了。")))),
                        "100", List.of(
                                new ShopDialogue.StoryLine("台账上的房费收入一天天涨了起来。", "EAGER",
                                        java.util.List.of(new org.merlin204.wss.shop.DialoguePackets.Option("next", "每一笔都是客人睡得好。"))),
                                new ShopDialogue.StoryLine("有房客留言：“下次出差还住你家。”", "LOVE",
                                        java.util.List.of(new org.merlin204.wss.shop.DialoguePackets.Option("next", "这是最好的评价。"))),
                                new ShopDialogue.StoryLine("小旅馆的灯，每晚都为赶路的人亮着。", "OK",
                                        java.util.List.of(new org.merlin204.wss.shop.DialoguePackets.Option("finish", "继续亮下去。"))))),
                // 顾客对白
                Map.of(
                        "enter", List.of("请问还有空房吗？", "赶了一天路，就想找个落脚的地方。"),
                        "waiting", List.of("前面还有人在登记啊……", "不急，我先等等。"),
                        "served", List.of("这床睡得真舒服！", "前台手续办得真快。"),
                        "leave", List.of("退房，谢谢款待。", "下次路过再来住。")),
                // 员工对白
                Map.of(
                        "busy", List.of("您好，请出示一下房牌，马上为您办理。", "客房那边的账我来核对。"),
                        "idle", List.of("前台擦干净了，等客人来。", "今天的房态还不错。")),
                // 对手对白
                Map.of(
                        "visit", List.of("听说这条街上新开了家小旅馆？", "床位倒是擦得挺干净。"),
                        "lose", List.of("他们的房客比我们多……", "下次把服务提上去。"))));
    }
}
