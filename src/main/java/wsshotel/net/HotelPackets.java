package wsshotel.net;

import com.google.gson.Gson;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.merlin204.wss.shop.DishCatalog;
import org.merlin204.wss.shop.ShopData;
import org.merlin204.wss.shop.ShopFacilities;
import org.merlin204.wss.shop.ShopSessions;
import wsshotel.HotelApi;
import wsshotel.HotelConfig;
import wsshotel.WssHotelMod;
import wsshotel.hotel.HotelCrowdPolicy;
import wsshotel.hotel.HotelGoods;
import wsshotel.hotel.HotelRooms;
import wsshotel.hotel.HotelShelves;
import wsshotel.hotel.HotelShopType;
import wsshotel.hotel.HotelState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 客房台账网络：
 * 台账查询走服务端缓存（P0-E，禁止每次请求全店扫描）；
 * 价目与早餐配置（P1-A / P2-A）经独立请求包下发，服务端校验会话与范围。
 * 客户端接收逻辑在 {@link wsshotel.client.HotelClientUi}（仅物理客户端加载）。
 */
public final class HotelPackets {

    public record LedgerRequest(String shopId) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<LedgerRequest> TYPE =
                new CustomPacketPayload.Type<>(WssHotelMod.id("ledger_request"));
        public static final StreamCodec<ByteBuf, LedgerRequest> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, LedgerRequest::shopId, LedgerRequest::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** F3 连锁台账查询：以会话店铺为入口，聚合本人全部小旅馆。 */
    public record ChainRequest(String shopId) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ChainRequest> TYPE =
                new CustomPacketPayload.Type<>(WssHotelMod.id("chain_request"));
        public static final StreamCodec<ByteBuf, ChainRequest> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ChainRequest::shopId, ChainRequest::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 价目设置：三档每晚价（各档位独立上下限）+ 钟点房固定价与开关。 */
    public record PriceUpdate(String shopId, int standardPrice, int comfortPrice, int deluxePrice, int hourlyPrice, boolean hourly) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PriceUpdate> TYPE =
                new CustomPacketPayload.Type<>(WssHotelMod.id("price_update"));
        public static final StreamCodec<ByteBuf, PriceUpdate> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, PriceUpdate::shopId,
                ByteBufCodecs.VAR_INT, PriceUpdate::standardPrice,
                ByteBufCodecs.VAR_INT, PriceUpdate::comfortPrice,
                ByteBufCodecs.VAR_INT, PriceUpdate::deluxePrice,
                ByteBufCodecs.VAR_INT, PriceUpdate::hourlyPrice,
                ByteBufCodecs.BOOL, PriceUpdate::hourly,
                PriceUpdate::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客房升级：床位 pos → 档位 +1（费用按当前档位结算）。 */
    public record UpgradeRoom(String shopId, long head) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<UpgradeRoom> TYPE =
                new CustomPacketPayload.Type<>(WssHotelMod.id("upgrade_room"));
        public static final StreamCodec<ByteBuf, UpgradeRoom> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, UpgradeRoom::shopId,
                ByteBufCodecs.VAR_LONG, UpgradeRoom::head,
                UpgradeRoom::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }


    /** 早餐设置：开关与早餐菜单（≤3 个菜品 ID，分号连接）。 */
    public record BreakfastUpdate(String shopId, boolean enabled, String dishesJoined) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BreakfastUpdate> TYPE =
                new CustomPacketPayload.Type<>(WssHotelMod.id("breakfast_update"));
        public static final StreamCodec<ByteBuf, BreakfastUpdate> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, BreakfastUpdate::shopId,
                ByteBufCodecs.BOOL, BreakfastUpdate::enabled,
                ByteBufCodecs.STRING_UTF8, BreakfastUpdate::dishesJoined,
                BreakfastUpdate::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** F2 饮品价设置（退房顺路购买的固定单价）。 */
    public record DrinkPriceUpdate(String shopId, int drinkPrice) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<DrinkPriceUpdate> TYPE =
                new CustomPacketPayload.Type<>(WssHotelMod.id("drink_price_update"));
        public static final StreamCodec<ByteBuf, DrinkPriceUpdate> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, DrinkPriceUpdate::shopId,
                ByteBufCodecs.VAR_INT, DrinkPriceUpdate::drinkPrice,
                DrinkPriceUpdate::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record LedgerReply(String json) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<LedgerReply> TYPE =
                new CustomPacketPayload.Type<>(WssHotelMod.id("ledger_reply"));
        public static final StreamCodec<ByteBuf, LedgerReply> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, LedgerReply::json, LedgerReply::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static final Gson GSON = new Gson();
    private static final int MAX_DISH_CHOICES = 40;

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("2");
        registrar.playToServer(LedgerRequest.TYPE, LedgerRequest.CODEC, (payload, context) ->
                context.enqueueWork(() -> handleRequest(context.player() instanceof ServerPlayer player ? player : null, payload)));
        registrar.playToServer(ChainRequest.TYPE, ChainRequest.CODEC, (payload, context) ->
                context.enqueueWork(() -> handleChain(context.player() instanceof ServerPlayer player ? player : null, payload)));
        registrar.playToServer(PriceUpdate.TYPE, PriceUpdate.CODEC, (payload, context) ->
                context.enqueueWork(() -> handlePrice(context.player() instanceof ServerPlayer player ? player : null, payload)));
        registrar.playToServer(BreakfastUpdate.TYPE, BreakfastUpdate.CODEC, (payload, context) ->
                context.enqueueWork(() -> handleBreakfast(context.player() instanceof ServerPlayer player ? player : null, payload)));
        registrar.playToServer(DrinkPriceUpdate.TYPE, DrinkPriceUpdate.CODEC, (payload, context) ->
                context.enqueueWork(() -> handleDrinkPrice(context.player() instanceof ServerPlayer player ? player : null, payload)));
        registrar.playToServer(UpgradeRoom.TYPE, UpgradeRoom.CODEC, (payload, context) ->
                context.enqueueWork(() -> handleUpgrade(context.player() instanceof ServerPlayer player ? player : null, payload)));
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            registrar.playToClient(LedgerReply.TYPE, LedgerReply.CODEC,
                    (payload, context) -> context.enqueueWork(() -> wsshotel.client.HotelClientUi.receive(payload.json())));
        }
    }

    private static boolean managed(ServerPlayer player, String shopId) {
        ShopData.Shop shop = ShopSessions.current(player);
        if (shop == null && shopId != null) {
            shop = ShopData.get(player.server).find(shopId);
        }
        return shop != null && ShopSessions.mayManage(player, shop) && shop.kind == HotelShopType.kind();
    }

    private static void handlePrice(ServerPlayer player, PriceUpdate payload) {
        if (player == null || !managed(player, payload.shopId())) {
            return;
        }
        ShopData.Shop shop = ShopSessions.current(player);
        if (shop == null) {
            shop = ShopData.get(player.server).find(payload.shopId());
        }
        if (shop == null) {
            return;
        }
        HotelState hotel = WssHotelMod.attachment(shop);
        hotel.standardPrice = Math.clamp(payload.standardPrice(), 10000, 15000);
        hotel.comfortPrice = Math.clamp(payload.comfortPrice(), 14000, 21000);
        hotel.deluxePrice = Math.clamp(payload.deluxePrice(), 19000, 28000);
        hotel.hourlyPrice = Math.clamp(payload.hourlyPrice(), 1200, 2000);
        hotel.hourlyEnabled = payload.hourly();
        hotel.bump();
        HotelApi.LOG.info("价目更新（{}）：标准 {} / 舒适 {} / 豪华 {} / 钟点 {} 金（{}）",
                shop.id, hotel.standardPrice, hotel.comfortPrice, hotel.deluxePrice,
                hotel.hourlyPrice, hotel.hourlyEnabled ? "开" : "关");
        reply(player, shop);
    }

    private static void handleBreakfast(ServerPlayer player, BreakfastUpdate payload) {
        if (player == null || !managed(player, payload.shopId())) {
            return;
        }
        ShopData.Shop shop = ShopSessions.current(player);
        if (shop == null) {
            shop = ShopData.get(player.server).find(payload.shopId());
        }
        if (shop == null) {
            return;
        }
        HotelState hotel = WssHotelMod.attachment(shop);
        hotel.breakfastEnabled = payload.enabled();
        hotel.breakfastDishes.clear();
        int cap = hotel.breakfastCap();
        if (payload.dishesJoined() != null && !payload.dishesJoined().isBlank()) {
            for (String dish : payload.dishesJoined().split(";")) {
                if (!dish.isBlank() && DishCatalog.get(dish.trim()) != null && hotel.breakfastDishes.size() < cap) {
                    hotel.breakfastDishes.add(dish.trim());
                }
            }
        }
        hotel.bump();
        reply(player, shop);
    }

    /** F2 饮品价更新。 */
    private static void handleDrinkPrice(ServerPlayer player, DrinkPriceUpdate payload) {
        if (player == null || !managed(player, payload.shopId())) {
            return;
        }
        ShopData.Shop shop = ShopSessions.current(player);
        if (shop == null) {
            shop = ShopData.get(player.server).find(payload.shopId());
        }
        if (shop == null) {
            return;
        }
        HotelState hotel = WssHotelMod.attachment(shop);
        hotel.drinkPrice = Math.clamp(payload.drinkPrice(), 50, 2000);
        hotel.bump();
        HotelApi.LOG.info("饮品价更新（{}）：{} 金", shop.id, hotel.drinkPrice);
        reply(player, shop);
    }

    private static void handleRequest(ServerPlayer player, LedgerRequest payload) {
        if (player == null || !managed(player, payload.shopId())) {
            return;
        }
        ShopData.Shop shop = ShopSessions.current(player);
        if (shop == null) {
            shop = ShopData.get(player.server).find(payload.shopId());
        }
        if (shop == null) {
            return;
        }
        reply(player, shop);
    }

    private static void reply(ServerPlayer player, ShopData.Shop shop) {
        HotelState hotel = WssHotelMod.attachment(shop);
        ServerLevel level = player.serverLevel();
        // P0-E：台账读业务 tick 维护的房态缓存；仅首次（缓存为空）回退一次共享扫描。
        List<int[]> cache = hotel.roomCache;
        if (cache.isEmpty()) {
            ShopFacilities facilities = ShopFacilities.scan(level, shop);
            HotelRooms rooms = facilities == null ? HotelCrowdPolicy.EMPTY : facilities.extension(HotelRooms.class, HotelCrowdPolicy.EMPTY);
            List<int[]> fresh = new ArrayList<>();
            for (HotelRooms.Room room : rooms.rooms()) {
                long head = room.head().asLong();
                int status = hotel.stayingAt(head) ? 1 : hotel.cleaningAt(head) ? 3
                        : Boolean.TRUE.equals(hotel.dirty.get(Long.toString(head))) ? 2 : 0;
                fresh.add(new int[]{room.head().getX(), room.head().getY(), room.head().getZ(), room.tier(), status});
            }
            cache = fresh;
        }

        List<Map<String, Object>> roomViews = new ArrayList<>();
        for (int[] room : cache) {
            String status;
            String detail;
            switch (room[4]) {
                case 1 -> {
                    status = "occupied";
                    HotelState.Stay stay = stayAt(hotel, new net.minecraft.core.BlockPos(room[0], room[1], room[2]).asLong());
                    long quarters = stay == null ? 0 : stay.quarterNights;
                    long target = stay == null ? 4 : (stay.hourly ? 2 : stay.plannedNights * 4);
                    detail = (stay != null && stay.hourly ? "钟点 · " : "")
                            + "剩余约 " + Math.max(1, (target - quarters + 3) / 4) + " 晚"
                            + (stay != null && stay.breakfastOrdered ? " · 含早" : "");
                }
                case 3 -> {
                    status = "cleaning";
                    detail = "保洁进行中";
                }
                case 2 -> {
                    status = "dirty";
                    detail = "待清洁";
                }
                default -> {
                    status = "free";
                    detail = "可入住";
                }
            }
            roomViews.add(Map.of(
                    "x", room[0], "y", room[1], "z", room[2],
                    "head", new net.minecraft.core.BlockPos(room[0], room[1], room[2]).asLong(),
                    "tier", room[3], "status", status, "detail", detail));
        }

        int roomCount = cache.size();
        // 早餐可选菜单：按 ID 排序取样。
        List<String> available = new ArrayList<>(DishCatalog.all().keySet());
        available.sort(Comparator.naturalOrder());
        if (available.size() > MAX_DISH_CHOICES) {
            available = new ArrayList<>(available.subList(0, MAX_DISH_CHOICES));
        }

        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("schema", 2);
        data.put("rev", hotel.rev);
        data.put("name", shop.name == null ? "" : shop.name);
        data.put("rooms", roomViews);
        data.put("totalRooms", roomCount);
        data.put("occupied", hotel.stays.size());
        data.put("occupancy", roomCount == 0 ? 0 : Math.round(100.0 * hotel.stays.size() / roomCount) / 100.0);
        data.put("peakOccupancy", Math.round(hotel.peakOccupancy * 100.0) / 100.0);
        data.put("todayIncome", hotel.todayIncome);
        data.put("todayCheckins", hotel.todayCheckins);
        data.put("totalIncome", hotel.totalIncome);
        data.put("totalCheckins", hotel.totalCheckins);
        data.put("totalNights", hotel.totalNights);
        data.put("totalCleans", hotel.totalCleans);
        data.put("totalComplaints", hotel.totalComplaints);
        data.put("breakfastIncome", hotel.breakfastIncome);
        LinkedHashMap<String, Object> pricing = new LinkedHashMap<>();
        pricing.put("standard", hotel.standardPrice);
        pricing.put("standardMin", 10000);
        pricing.put("standardMax", 15000);
        pricing.put("comfort", hotel.comfortPrice);
        pricing.put("comfortMin", 14000);
        pricing.put("comfortMax", 21000);
        pricing.put("deluxe", hotel.deluxePrice);
        pricing.put("deluxeMin", 19000);
        pricing.put("deluxeMax", 28000);
        pricing.put("hourlyPrice", hotel.hourlyPrice);
        pricing.put("hourlyMin", 1200);
        pricing.put("hourlyMax", 2000);
        pricing.put("hourly", hotel.hourlyEnabled);
        data.put("pricing", pricing);
        // 货架零售（本体便利店货架：每架 3 层）——1.4.1 起配置存本体 RetailState，与便利店备货 UI 同源。
        org.merlin204.wss.shop.RetailState retail = org.merlin204.wss.shop.RestaurantData.get(player.server).restaurant(shop.id).retail;
        List<Map<String, Object>> shelfViews = new ArrayList<>();
        for (Map.Entry<Long, org.merlin204.wss.shop.RetailState.Shelf> entry : retail.shelves.entrySet()) {
            List<Map<String, Object>> layerViews = new ArrayList<>();
            org.merlin204.wss.shop.RetailState.Shelf sh = entry.getValue();
            for (int i = 0; i < 3; i++) {
                Map<String, Object> lv = new LinkedHashMap<>();
                lv.put("goods", sh.products[i] == null ? "" : sh.products[i]);
                lv.put("name", HotelGoods.name(sh.products[i]));
                lv.put("price", HotelGoods.price(sh.products[i]));
                lv.put("count", sh.count[i]);
                lv.put("max", sh.products[i].isEmpty() ? 0 : HotelGoods.perLayer(sh.products[i]));
                layerViews.add(lv);
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("pos", Long.toString(entry.getKey()));
            view.put("layers", layerViews);
            shelfViews.add(view);
        }
        data.put("shelves", shelfViews);
        List<Map<String, Object>> catalogViews = new ArrayList<>();
        for (HotelGoods.Goods g : HotelGoods.ALL()) {
            catalogViews.add(Map.of("id", g.id(), "name", g.name(), "category", g.category(), "price", g.price()));
        }
        data.put("goodsCatalog", catalogViews);
        data.put("goodsIncome", hotel.goodsIncome);
        data.put("daily", new TreeMap<>(hotel.dailyIncome));
        data.put("dailyOccupancy", new TreeMap<>(hotel.dailyOccupancy));
        data.put("topRoom", topRoom(hotel));
        data.put("contestDay", HotelApi.contestDay(hotel.currentDay));
        data.put("pulseActive", hotel.journeyPulseDay == hotel.currentDay);
        // —— 二期：等级线 / 节气 / 服务事件 ——
        data.put("level", hotel.level);
        data.put("title", hotel.title());
        data.put("termName", termName(hotel));
        data.put("seasonLabel", wsshotel.hotel.HotelSeasons.seasonLabel(hotel.currentTerm));
        data.put("serviceIncome", hotel.serviceIncome);
        data.put("serviceActive", hotel.serviceJobs.size());
        data.put("slowCount", hotel.slowCount);
        data.put("roomCount", hotel.roomCount);
        data.put("drinkPrice", hotel.drinkPrice);
        data.put("drinkIncome", hotel.drinkIncome);
        data.put("drinkCounters", hotel.drinkCounters.size());
        LinkedHashMap<String, Object> breakfastView = new LinkedHashMap<>();
        breakfastView.put("enabled", hotel.breakfastEnabled);
        breakfastView.put("dishes", List.copyOf(hotel.breakfastDishes));
        breakfastView.put("available", available);
        breakfastView.put("cap", hotel.breakfastCap());
        data.put("breakfast", breakfastView);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new LedgerReply(GSON.toJson(data)));
    }

    /** 当前节气名（按业务日推算；未同步时返回空串）。 */
    private static String termName(HotelState hotel) {
        try {
            if (hotel.currentDay != Long.MIN_VALUE) {
                return org.merlin204.wss.environment.TermDate.ofDay(hotel.currentDay).name();
            }
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    /** F3 连锁台账：聚合本人全部小旅馆的持久化统计（未加载门店可读）。 */
    private static void handleChain(ServerPlayer player, ChainRequest payload) {
        if (player == null || !managed(player, payload.shopId())) {
            return;
        }
        ShopData.Shop entry = ShopSessions.current(player);
        if (entry == null) {
            entry = ShopData.get(player.server).find(payload.shopId());
        }
        if (entry == null) {
            return;
        }
        java.util.UUID owner = entry.owner;
        List<Map<String, Object>> hotels = new ArrayList<>();
        long totalIncome = 0;
        long totalNights = 0;
        long totalCheckins = 0;
        int totalRooms = 0;
        int totalOccupied = 0;
        for (ShopData.Shop shop : ShopData.get(player.server).shops()) {
            if (shop.kind != HotelShopType.kind() || !owner.equals(shop.owner)) {
                continue;
            }
            HotelState hotel = WssHotelMod.attachment(shop);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", shop.id);
            view.put("name", shop.name == null ? "" : shop.name);
            view.put("totalIncome", hotel.totalIncome);
            view.put("totalNights", hotel.totalNights);
            view.put("totalCheckins", hotel.totalCheckins);
            view.put("roomCount", hotel.roomCount);
            view.put("occupied", hotel.stays.size());
            view.put("level", hotel.level);
            hotels.add(view);
            totalIncome += hotel.totalIncome;
            totalNights += hotel.totalNights;
            totalCheckins += hotel.totalCheckins;
            totalRooms += hotel.roomCount;
            totalOccupied += hotel.stays.size();
        }
        hotels.sort((a, b) -> Long.compare((long) b.get("totalIncome"), (long) a.get("totalIncome")));
        if (hotels.size() > 20) {
            hotels = new ArrayList<>(hotels.subList(0, 20));
        }
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("schema", 2);
        data.put("mode", "chain");
        data.put("name", "连锁台账");
        data.put("termName", termName(WssHotelMod.attachment(entry)));
        data.put("seasonLabel", wsshotel.hotel.HotelSeasons.seasonLabel(WssHotelMod.attachment(entry).currentTerm));
        data.put("hotels", hotels);
        data.put("totalIncome", totalIncome);
        data.put("totalNights", totalNights);
        data.put("totalCheckins", totalCheckins);
        data.put("totalRooms", totalRooms);
        data.put("occupied", totalOccupied);
        data.put("occupancy", totalRooms == 0 ? 0 : Math.round(100.0 * totalOccupied / totalRooms) / 100.0);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new LedgerReply(GSON.toJson(data)));
    }

    private static String topRoom(HotelState hotel) {
        String best = "";
        long bestIncome = 0;
        for (Map.Entry<String, Long> entry : hotel.roomIncome.entrySet()) {
            if (entry.getValue() > bestIncome) {
                bestIncome = entry.getValue();
                best = entry.getKey();
            }
        }
        if (best.isEmpty()) {
            return "";
        }
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.of(Long.parseLong(best));
        return pos.getX() + "," + pos.getY() + "," + pos.getZ() + " · " + bestIncome + "金";
    }

    /** 客房升级：档位 +1，费用按当前档位（标准→舒适 20000，舒适→豪华 50000）。 */
    private static void handleUpgrade(ServerPlayer player, UpgradeRoom payload) {
        if (player == null || !managed(player, payload.shopId())) {
            return;
        }
        ShopData.Shop shop = ShopSessions.current(player);
        if (shop == null) {
            shop = ShopData.get(player.server).find(payload.shopId());
        }
        if (shop == null) {
            return;
        }
        HotelState hotel = WssHotelMod.attachment(shop);
        String key = Long.toString(payload.head());
        int tier = hotel.roomTiers.getOrDefault(key, -1);
        if (tier < 0) {
            // 未升级过的房间：以扫描档位为准
            ShopFacilities facilities = ShopFacilities.scan(player.serverLevel(), shop);
            HotelRooms rooms = facilities == null ? HotelCrowdPolicy.EMPTY
                    : facilities.extension(HotelRooms.class, HotelCrowdPolicy.EMPTY);
            tier = -1;
            for (HotelRooms.Room room : rooms.rooms()) {
                if (room.head().asLong() == payload.head()) {
                    tier = room.tier();
                    break;
                }
            }
            if (tier < 0) {
                return; // 不是本店客房
            }
        }
        if (tier >= 2) {
            return; // 已是豪华档
        }
        int cost = tier == 0 ? 20000 : 50000;
        var money = org.merlin204.wss.shop.EmploymentData.get(player.server);
        if (money.balance(shop.owner) < cost
                || !money.changeBalance(shop.owner, -cost, shop.id, "客房升级 · " + (tier == 0 ? "标准→舒适" : "舒适→豪华"))) {
            GameSoundsLike.reject(player, "金币不足，升级需要 " + cost + " 金币。");
            return;
        }
        hotel.roomTiers.put(key, tier + 1);
        // 房态缓存即时同步：否则回包里的档位还是旧值，要等下一次业务 tick 才刷新。
        for (int[] room : hotel.roomCache) {
            if (new net.minecraft.core.BlockPos(room[0], room[1], room[2]).asLong() == payload.head()) {
                room[3] = tier + 1;
                break;
            }
        }
        hotel.bump();
        HotelApi.LOG.info("客房升级（{}）：{} → 档位 {}，花费 {} 金", shop.id, key, tier + 1, cost);
        reply(player, shop);
    }
    /** 金币不足等拒绝提示的本地占位（避免直接依赖本体包级 API）。 */
    private static final class GameSoundsLike {
        static void reject(ServerPlayer player, String message) {
            HotelApi.LOG.info("操作被拒绝（{}）：{}", player.getGameProfile().getName(), message);
        }
    }

    private static HotelState.Stay stayAt(HotelState hotel, long head) {
        for (HotelState.Stay stay : hotel.stays.values()) {
            if (stay.head == head) {
                return stay;
            }
        }
        return null;
    }

    private HotelPackets() {
    }
}
