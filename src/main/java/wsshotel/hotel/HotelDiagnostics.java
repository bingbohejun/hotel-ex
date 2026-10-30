package wsshotel.hotel;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.merlin204.wss.shop.EmploymentData;
import org.merlin204.wss.shop.EmployeeEntity;
import org.merlin204.wss.shop.RestaurantData;
import org.merlin204.wss.shop.RestaurantService;
import org.merlin204.wss.shop.ShopData;
import org.merlin204.wss.shop.ShopFacilities;
import org.merlin204.wss.shop.ShopJob;
import org.merlin204.wss.shop.ShopPresence;
import org.merlin204.wss.shop.ShopZones;
import org.merlin204.wss.shop.SpecialCustomerData;
import wsshotel.HotelApi;
import wsshotel.WssHotelMod;
import wsshotel.mixin.CityResidentsAccess;

import java.util.ArrayList;
import java.util.List;

/**
 * 人流量排查打点：独立于本体业务 tick 的服务器 tick 监听。
 * 每家在线小旅馆每 100 tick（5 秒）输出一行"接待门槛"状态，
 * 覆盖本体接待链的每道门，顾客不进店时看这行日志即可定位环节。
 * 同时监测业务 tick 是否停滞（停滞=区块加载判定失败等，属于本体侧拦截）。
 */
public final class HotelDiagnostics {

    private static volatile long lastBusinessTick;
    private static volatile MinecraftServer lastServer;

    /** 业务 tick 心跳，用于判断"本体根本没在跑这家店的业务循环"。 */
    public static void touchBusinessTick() {
        lastBusinessTick = System.currentTimeMillis();
    }

    /**
     * 到店漏斗打点（经 Mixin 写入本体的 CityResidents.arrivalObserver）。
     * 输出本体机会生成对每家店的每一步判定结果：
     * candidate_no_room/menu/demand/flow/interest/entrance/spawn_occupied → spawned_walk/car → order_admitted。
     * 只记录小旅馆；拒绝原因用 DEBUG，成功到店用 INFO。
     */
    public static void installArrivalObserver() {
        CityResidentsAccess.wssHotel$setArrivalObserver((shopId, outcome) -> {
            try {
                MinecraftServer server = lastServer;
                if (server == null) {
                    return;
                }
                ShopData.Shop shop = ShopData.get(server).find(shopId);
                if (shop == null || shop.kind != HotelShopType.kind()) {
                    return;
                }
                boolean success = outcome.startsWith("spawned") || outcome.equals("order_admitted");
                if (success) {
                    HotelApi.LOG.info("[{}] 到店漏斗 → {}", shopId, outcome);
                } else {
                    HotelApi.LOG.debug("[{}] 到店漏斗 → {}", shopId, outcome);
                }
            } catch (Throwable ignored) {
                // 打点不影响业务
            }
        });
    }

    /** 在主类构造里注册：NeoForge.EVENT_BUS.addListener(HotelDiagnostics::onServerTick)。 */
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        lastServer = server;
        if (server.overworld() == null || server.getTickCount() % 100 != 0) {
            return;
        }
        for (ShopData.Shop shop : ShopData.get(server).shops()) {
            if (shop.kind != HotelShopType.kind()) {
                continue;
            }
            try {
                trace(server, shop);
            } catch (Throwable t) {
                HotelApi.LOG.warn("接待门槛诊断输出失败: {}", shop.id, t);
            }
        }
    }

    private static void trace(MinecraftServer server, ShopData.Shop shop) {
        boolean online = ShopPresence.online(server, shop);
        StringBuilder sb = new StringBuilder("接待门槛 →")
                .append(" 在线=").append(online);
        if (!online) {
            HotelApi.LOG.info("[{}] {}", shop.id, sb);
            return;
        }

        boolean stale = System.currentTimeMillis() - lastBusinessTick > 3000;
        sb.append(" 业务tick=").append(stale ? "停滞!" : "正常");

        ServerLevel level = RestaurantService.level(server, shop);
        sb.append(" 区块=").append(level != null);
        if (level == null) {
            HotelApi.LOG.info("[{}] {}", shop.id, sb);
            return;
        }

        RestaurantData.Restaurant state = RestaurantData.get(server).restaurant(shop.id);
        int minute = org.merlin204.wss.shop.CityResidents.minute(server);
        int inWindow = 0;
        long day = org.merlin204.wss.environment.WorldCalendarData.get(server.overworld()).date().day();
        for (int type = 1; type <= 40; type++) {
            org.merlin204.wss.shop.CitizenCatalog.Type t = org.merlin204.wss.shop.CitizenCatalog.type(type);
            if (t != null && (t.window(day, minute, 0) != null || t.window(day, minute, 1) != null)) {
                inWindow++;
            }
        }
        sb.append(" 营业=").append(state.open)
                .append(" 时段=").append(state.hours)
                .append(" 时钟=").append(String.format("%02d:%02d", minute / 60, minute % 60))
                .append(" 用餐窗口=").append(inWindow).append("/40类");

        int[] b = shop.bounds;
        BlockPos center = new BlockPos((b[0] + b[3]) / 2, b[1], (b[2] + b[5]) / 2);
        sb.append(" 几何加载=").append(level.hasChunkAt(center));

        ShopFacilities f = RestaurantService.facilities(server, shop);
        if (f == null) {
            sb.append(" 设施=未生成");
            HotelApi.LOG.info("[{}] {}", shop.id, sb);
            return;
        }
        sb.append(" 入口=").append(f.entry() != null)
                .append(" 收银台=").append(f.registers().size());

        HotelRooms rooms = f.extension(HotelRooms.class, HotelCrowdPolicy.EMPTY);
        HotelState hotel = WssHotelMod.attachment(shop);
        int free = 0;
        for (HotelRooms.Room room : rooms.rooms()) {
            if (hotel.roomFree(room.head().asLong())) {
                free++;
            }
        }
        sb.append(" 客房=").append(rooms.rooms().size())
                .append(" 空房=").append(free)
                .append(" 菜单=").append(RestaurantService.menu(shop, state, f).size())
                .append(" 订单=").append(state.orders.size());
        // 前台排队明细：占用者（截断显示）/占用者是否就位/排队人数——"顾客堵收银台"时看这里。
        int deskQueue = 0;
        for (RestaurantData.Order o : state.orders) {
            if ((o.stage == RestaurantData.Stage.ORDERING || o.stage == RestaurantData.Stage.CHECKOUT)
                    && !o.id.toString().equals(hotel.deskUser)) {
                deskQueue++;
            }
        }
        sb.append(" 前台占=").append(hotel.deskUser == null ? "-" : hotel.deskUser.substring(0, 8))
                .append(" 已就位=").append(hotel.deskArrived)
                .append(" 排队=").append(deskQueue);

        String miss;
        if (f.entry() == null) {
            miss = "请为店铺留出通行的入口";
        } else if (f.registers().isEmpty()) {
            miss = "缺少收银电脑";
        } else {
            miss = HotelBusiness.INSTANCE.missing(server, shop, state, f);
        }
        // 本体真实接待门槛（Mixin 暴露的 ShopStaffLife.ready，与日结遥测同一判定）。
        boolean ready = wsshotel.mixin.ShopStaffLifeAccess.invokeReady(
                level, shop, state, EmploymentData.get(server).workers(shop.id), f);
        sb.append(" 缺=\"").append(miss.isEmpty() ? "无" : miss).append('"')
                .append(" ready=").append(ready);
        // 动态必需岗位（含条件性岗位，如货物架零售的服务员）——ready=false 时看这里缺谁。
        List<String> requiredNames = new ArrayList<>();
        for (ShopJob job : shop.kind.type().staffing().required(state, f)) {
            requiredNames.add(shop.kind.job(job));
        }
        sb.append(" 必需=").append(requiredNames);

        // F6 服务员明细：在店/可派单/任务/需求/货架/缺货层——货架空了不补货时看这里。
        int waiterEmployed = 0;
        int waiterInZone = 0;
        int waiterAvailable = 0;
        for (EmploymentData.Worker w : EmploymentData.get(server).workers(shop.id)) {
            if (w.job() != ShopJob.WAITER) {
                continue;
            }
            waiterEmployed++;
            EmployeeEntity npc = level.getEntity(w.profile().id()) instanceof EmployeeEntity entity ? entity : null;
            if (npc != null && ShopZones.belongs(level, shop, npc.blockPosition())) {
                waiterInZone++;
            }
            if (wsshotel.mixin.ShopStaffLifeAccess.invokeAvailable(state, w.profile().id(), ShopJob.WAITER)) {
                waiterAvailable++;
            }
        }
        int understocked = 0;
        for (org.merlin204.wss.shop.RetailState.Shelf shelf : state.retail.shelves.values()) {
            for (int i = 0; i < 3; i++) {
                if (!shelf.products[i].isEmpty() && shelf.count[i] < HotelGoods.perLayer(shelf.products[i])) {
                    understocked++;
                }
            }
        }
        int waiterBusy = (int) hotel.tasks.stream().filter(t -> t.waiter != null && !t.waiter.isEmpty()).count();
        sb.append(" | 服务员 雇=").append(waiterEmployed)
                .append(" 在店=").append(waiterInZone)
                .append(" 任务=").append(waiterBusy)
                .append(" 需求=").append(hotel.demands.size())
                .append(" 货架=").append(state.retail.shelves.size())
                .append(" 缺货层=").append(understocked);

        // 补货员明细：雇/在店/可派/任务——货架空着不补时看这里。
        int stockerEmployed = 0;
        int stockerInZone = 0;
        int stockerAvailable = 0;
        for (EmploymentData.Worker w : EmploymentData.get(server).workers(shop.id)) {
            if (w.job() != ShopJob.STOCKER) {
                continue;
            }
            stockerEmployed++;
            if (level.getEntity(w.profile().id()) instanceof EmployeeEntity entity
                    && ShopZones.belongs(level, shop, entity.blockPosition())) {
                stockerInZone++;
            }
            if (wsshotel.mixin.ShopStaffLifeAccess.invokeAvailable(state, w.profile().id(), ShopJob.STOCKER)) {
                stockerAvailable++;
            }
        }
        int stockerBusy = (int) hotel.tasks.stream().filter(t -> t.waiter != null && !t.waiter.isEmpty()
                && "RESTOCK".equals(t.type)).count();
        sb.append(" | 补货员 雇=").append(stockerEmployed)
                .append(" 在店=").append(stockerInZone)
                .append(" 可派=").append(stockerAvailable)
                .append(" 任务=").append(stockerBusy);
        if (!hotel.tasks.isEmpty()) {
            StringBuilder taskInfo = new StringBuilder(" 任务详情=[");
            for (wsshotel.hotel.HotelState.WaiterTask t : hotel.tasks) {
                long stuck = t.stuckSince == 0 ? 0 : level.getGameTime() - t.stuckSince;
                taskInfo.append(t.type).append("@").append(t.shelf).append(" p").append(t.phase)
                        .append(" 卡").append(stuck).append("t, ");
            }
            taskInfo.append("]");
            sb.append(taskInfo);
        }

        // 保洁明细：脏房堆积（拒客）时看 在班/在店 是否为 0。
        int maidEmployed = 0;
        int maidInZone = 0;
        int maidAvailable = 0;
        for (EmploymentData.Worker w : EmploymentData.get(server).workers(shop.id)) {
            if (w.job() != WssHotelMod.MAID) {
                continue;
            }
            maidEmployed++;
            if (level.getEntity(w.profile().id()) instanceof EmployeeEntity entity
                    && ShopZones.belongs(level, shop, entity.blockPosition())) {
                maidInZone++;
            }
            if (wsshotel.mixin.ShopStaffLifeAccess.invokeAvailable(state, w.profile().id(), WssHotelMod.MAID)) {
                maidAvailable++;
            }
        }
        sb.append(" | 保洁 脏房=").append(hotel.dirty.size())
                .append(" 雇=").append(maidEmployed)
                .append(" 在店=").append(maidInZone)
                .append(" 可派=").append(maidAvailable)
                .append(" 任务=").append(hotel.cleans.size());

        for (ShopJob job : shop.kind.type().staffing().required()) {
            int employed = 0;
            int inZone = 0;
            for (EmploymentData.Worker w : EmploymentData.get(server).workers(shop.id)) {
                if (w.job() != job) {
                    continue;
                }
                employed++;
                if (level.getEntity(w.profile().id()) instanceof EmployeeEntity npc
                        && ShopZones.belongs(level, shop, npc.blockPosition())) {
                    inZone++;
                }
            }
            sb.append(" | ").append(shop.kind.job(job)).append(' ').append(inZone).append('/').append(employed);
        }

        List<String> pending = new ArrayList<>();
        for (SpecialCustomerData.Visit v : SpecialCustomerData.get(server).visits) {
            if (shop.id.equals(v.shop)) {
                pending.add(v.character + (v.arrived ? "(已到场)" : "(预约中)"));
            }
        }
        sb.append(" | 特殊房客:").append(pending.isEmpty() ? "无" : pending);

        sb.append(" 扫货架=").append(f.retailShelves().size())
                .append(" 容量 服=").append(shop.kind.type().staffing().capacity(f, ShopJob.WAITER))
                .append(" 补=").append(shop.kind.type().staffing().capacity(f, ShopJob.STOCKER));

        // 每 2 分钟一次全量扫描校验（仅当有玩家在店铺附近——无人查看容量时无需消耗）：
        // 世界内货架/客房数与设施快照不一致（常见于 WorldEdit/非常规改块不触发本体失效监听）时，
        // 强制刷新设施快照，让岗位容量与货架零售恢复同步。
        if (server.getTickCount() % 2400 == 0) {
            boolean playerNearby = false;
            for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.level() == level && p.blockPosition().distSqr(center) < 4096.0) {
                    playerNearby = true;
                    break;
                }
            }
            if (!playerNearby) {
                return;
            }
            ShopFacilities fresh = ShopFacilities.scan(level, shop);
            int freshRooms = fresh.extension(HotelRooms.class, HotelCrowdPolicy.EMPTY).rooms().size();
            int cachedRooms = f.extension(HotelRooms.class, HotelCrowdPolicy.EMPTY).rooms().size();
            if (fresh.retailShelves().size() != f.retailShelves().size() || freshRooms != cachedRooms) {
                org.merlin204.wss.shop.RestaurantService.invalidate(shop);
                HotelApi.LOG.info("[{}] 检测到店内设施变动未刷新，已强制重扫：货架 {}→{} 客房 {}→{}",
                        shop.id, f.retailShelves().size(), fresh.retailShelves().size(), cachedRooms, freshRooms);
            }
        }

        HotelApi.LOG.info("[{}] {}", shop.id, sb);
    }

    private HotelDiagnostics() {
    }
}
