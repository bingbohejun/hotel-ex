package wsshotel.hotel;

import org.merlin204.wss.api.ShopContext;
import org.merlin204.wss.api.WssEvents;
import org.merlin204.wss.shop.AbstractShopBusiness;
import org.merlin204.wss.shop.BusinessPlanGifts;
import org.merlin204.wss.shop.CityResidents;
import wsshotel.mixin.BusinessPlanServiceAccess;
import wsshotel.mixin.RestaurantServiceAccess;
import wsshotel.mixin.ShopStaffLifecycleAccess;
import wsshotel.mixin.ShopStaffLifeAccess;
import org.merlin204.wss.shop.DishCatalog;
import org.merlin204.wss.shop.DayEndData;
import org.merlin204.wss.shop.DiningFurniture;
import org.merlin204.wss.shop.EmploymentData;
import org.merlin204.wss.shop.EmployeeEntity;
import org.merlin204.wss.shop.NpcEmotion;
import org.merlin204.wss.shop.RetailShelfBlockEntity;
import org.merlin204.wss.shop.RestaurantData;
import org.merlin204.wss.shop.RestaurantRecipes;
import org.merlin204.wss.shop.RestaurantService;
import org.merlin204.wss.shop.RetailState;
import org.merlin204.wss.shop.RestroomState;
import org.merlin204.wss.shop.ShopBusiness;
import org.merlin204.wss.shop.ShopData;
import org.merlin204.wss.shop.ShopFacilities;
import org.merlin204.wss.shop.ShopJob;
import org.merlin204.wss.shop.ShopStaff;
import org.merlin204.wss.shop.ShopWorkContext;
import org.merlin204.wss.shop.ShopZones;
import org.merlin204.wss.shop.SpecialCustomerData;
import org.merlin204.wss.shop.SpecialCustomers;
import org.merlin204.wss.shop.StaffShifts;
import org.merlin204.wss.shop.TermShopEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import wsshotel.HotelApi;
import wsshotel.HotelBlocks;
import wsshotel.HotelConfig;
import wsshotel.WssHotelMod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 小旅馆业务状态机（复用 RestaurantData.Order 与 Stage）：
 * QUEUE（候房，可在候位椅等待）→ ORDERING（前台登记）→ EAT（住宿计时）
 * → CHECKOUT（前台退房结算 + 评价）→ LEAVE（离店，房间转待清洁）。
 *
 * 订单带 restroom 标记（本体"简单服务"口径）：共享评价/等待/恢复逻辑按 o.taste 评分，
 * 由 {@link #satisfaction} 计算住宿满意度。
 *
 * 必须继承 {@link AbstractShopBusiness}：其 tick() 为 final，会在业务循环后强制执行
 * ShopStaffLifecycle.finish（员工任务收尾/情绪/对话）。此前直接 implements ShopBusiness
 * 导致该钩子从未运行，属于接入缺失。
 */
public final class HotelBusiness extends AbstractShopBusiness {

    public static final HotelBusiness INSTANCE = new HotelBusiness();

    private static final double ARRIVE_SQR = 2.25;

    /** operate 自身的节流计数器（每调用一次 +5，等价真实流逝刻数），见 operate 注释。 */
    private int cycle;
    private static final int WALK_TIMEOUT = 1500;
    private static final int LEAVE_TIMEOUT = 3000;
    private static final String[] TIER_NAMES = {"标准", "舒适", "豪华"};

    private HotelBusiness() {
    }

    // —— 开店条件 ——

    @Override
    public String missing(MinecraftServer server, ShopData.Shop shop, RestaurantData.Restaurant state, ShopFacilities facilities) {
        HotelRooms rooms = facilities == null ? HotelCrowdPolicy.EMPTY : facilities.extension(HotelRooms.class, HotelCrowdPolicy.EMPTY);
        if (rooms.rooms().isEmpty()) {
            return "请摆放床位（任意颜色的床），并在床边留出能走到的站位。";
        }
        if (facilities.registers().isEmpty()) {
            return "请在前台摆放收银电脑，电脑前后留出站位。";
        }
        List<ShopJob> working = EmploymentData.get(server).workers(shop.id).stream()
                .map(EmploymentData.Worker::job)
                .toList();
        for (ShopJob job : shop.kind.type().staffing().required()) {
            if (!working.contains(job)) {
                return "请安排" + shop.kind.job(job) + "。";
            }
        }
        return "";
    }

    // —— 接待 ——

    @Override
    public RestaurantData.Order admit(MinecraftServer server, ShopData.Shop shop, EmployeeEntity guest, int type, ShopFacilities facilities, String flow) {
        HotelRooms rooms = facilities.extension(HotelRooms.class, HotelCrowdPolicy.EMPTY);
        HotelState hotel = WssHotelMod.attachment(shop);
        HotelRooms.Room room = rooms.rooms().stream()
                .filter(r -> hotel.roomFree(r.head().asLong()))
                .findFirst()
                .orElse(null);
        if (room == null) {
            // 走到这里说明本体门槛已放行但本店拒单，打点便于排查（空房不足是唯一拒绝原因）。
            HotelApi.LOG.debug("[{}] 顾客申请入住被拒：当前无空房（扫描到 {} 间客房）", shop.id, rooms.rooms().size());
            return null;
        }

        RestaurantData.Restaurant state = RestaurantData.get(server).restaurant(shop.id);
        RestaurantData.Order order = new RestaurantData.Order();
        order.customer = guest.getUUID();
        order.customerPosition = guest.blockPosition().asLong();
        order.citizenType = type;
        order.price = 0;
        order.plan = HotelApi.stayPlan();
        // “简单服务”标记：共享评价/等待/恢复逻辑按 o.taste 评分，由退房时的满意度填充。
        order.restroom = new RestroomState.Visit();
        order.table = room.head().asLong();
        order.chair = room.head().asLong();
        order.approach = room.approach().asLong();
        order.stage = RestaurantData.Stage.ORDERING;

        // 普通顾客与特殊顾客兼容：识别皮肤、记录角色、清空用餐邀约字段。
        SpecialCustomers.Character special = HotelApi.specialSkin(guest.customSkin());
        CityResidents.Actor actor = HotelApi.actor(server, guest.getUUID());
        if (special != null) {
            order.specialCustomer = special.id();
            order.specialOccasion = actor == null ? 0L : actor.occasion;
        }
        if (actor != null) {
            order.planSource = actor.planSource;
            actor.planSource = 0;
            actor.planFood = "";
            actor.planBooking = null;
            actor.planParty = null;
        }

        long day = HotelApi.businessDay(server);
        HotelState.Stay stay = hotel.beginStay(order.id.toString(), guest.getUUID().toString(), room,
                server.overworld().getGameTime(), day, type);
        stay.tier = effectiveTier(hotel, room);
        stay.hourly = hotel.hourlyEnabled
                && Math.floorMod(order.id.hashCode(), 100) < (int) (HotelConfig.HOURLY_CHANCE.get() * 100);
        if (hotel.breakfastEnabled && !hotel.breakfastDishes.isEmpty()
                && guest.getRandom().nextFloat() < HotelConfig.BREAKFAST_CHANCE.get()) {
            stay.breakfastOrdered = true;
            stay.breakfastDish = hotel.breakfastDishes.get(guest.getRandom().nextInt(hotel.breakfastDishes.size()));
        }
        int unit = hotel.priceOf(stay.tier);
        // F5：长租订单在报价里注明折扣。
        String discountTag = !stay.hourly && stay.plannedNights >= HotelConfig.LONG_STAY_MIN_NIGHTS.get()
                ? "（长租" + Math.round(HotelConfig.LONG_STAY_DISCOUNT.get() * 10) + "折）" : "";
        order.citizenDemand = (stay.hourly ? "钟点房" : stay.plannedNights + "晚") + " · " + TIER_NAMES[stay.tier] + "房 · "
                + unit + "金/晚" + discountTag;
        // 入住即给出预期满意度：情绪系统在 EAT 入场时读取 taste 生成表情（0 分会显得很差）；
        // 退房结算时会按实际体验覆盖为最终评分。
        int expected = satisfaction(hotel, stay, envFactor(server, shop));
        order.taste = expected;
        order.charm = expected;

        state.orders.add(order);
        hotel.totalCheckins++;
        hotel.todayCheckins++;
        updatePeak(hotel, rooms.rooms().size());
        TermShopEffects.admitted(server, state, type);
        BusinessPlanGifts.admitted(state, order);
        // 本体接待登记（Mixin 暴露）：会员/粉丝进度推进等，与核心店型 admit 对齐。
        ServerLevel admitLevel = RestaurantService.level(server, shop);
        if (admitLevel != null) {
            BusinessPlanServiceAccess.invokeAdmitted(admitLevel, shop, state, order);
        }
        RestaurantData.get(server).setDirty();
        HotelApi.LOG.info("[{}] 顾客入住：{}{}，房型={}（当前订单 {}）", shop.id,
                special != null ? "特殊房客「" + special.name() + "」" : "市民",
                stay.hourly ? "（钟点房）" : "",
                TIER_NAMES[stay.tier], state.orders.size());
        return order;
    }

    // —— 主循环 ——

    /**
     * 本体业务循环入口（AbstractShopBusiness.tick 为 final，会先调 operate 再跑员工收尾）。
     */
    @Override
    protected void operate(ShopWorkContext context) {
        MinecraftServer server = context.server();
        ServerLevel level = context.level();
        ShopData.Shop shop = context.shop();
        RestaurantData.Restaurant state = context.state();
        ShopFacilities facilities = context.facilities();
        HotelRooms rooms = facilities.extension(HotelRooms.class, HotelCrowdPolicy.EMPTY);
        HotelState hotel = WssHotelMod.attachment(shop);

        // operate 由本体按固定相位每 5 刻调用一次（due(shop,5)），本循环内部的节流一律用
        // 自身调用计数（每次 +5 刻），绝不用 gameTime 取模——否则与调用相位错开时
        // 对应分支会永远不执行（ensureStaffOnDuty/计夜等都曾因此失效）。
        cycle += 5;

        // 运行态与店铺关联，供工作策略/客流权重反查挂载数据。
        HotelWorkPolicy.HotelShops.link(state, shop);

        // 旧档兜底：本体签约时把营业时段设为 0（永不营业），且开店引导只教玩家给
        // 第一家店设置时段——附属店型没有任何提示，导致 r.open 恒 false，
        // 普通市民与特殊房客都不会到访。这里把 hours=0 的旅馆自动改为全天营业（掩码 7）。
        // 玩家之后仍可在店铺管理界面改回任意时段。
        if (state.hours == null || state.hours == 0) {
            state.hours = 7;
            RestaurantData.get(server).setDirty();
            HotelApi.LOG.warn("[{}] 检测到营业时段未设置（0=永不营业），已自动改为全天营业；此前房客不会到访。", shop.id);
        }

        long day = HotelApi.businessDay(server);
        long previous = hotel.todayGameDay;
        hotel.rollDay(day, rooms.rooms().size());
        if (previous != Long.MIN_VALUE && previous != day) {
            complaints(context, hotel, day);
            serviceTimeouts(context, hotel, day);
        }

        refreshRoomCache(hotel, rooms, level);
        // F7：同步当前节气序号（demandWeight 用，避免客流回调里碰 server）。
        hotel.currentTerm = HotelSeasons.current(server).term();
        // F3：持久化房间数（连锁台账跨店读取用）。
        hotel.roomCount = rooms.rooms().size();
        // F2：饮品台站位缓存。
        hotel.drinkCounters = rooms.drinkCounters();
        HotelDiagnostics.touchBusinessTick();
        staffLifecycle(context);
        retail(context, hotel, rooms);

        for (RestaurantData.Order order : new ArrayList<>(state.orders)) {
            Entity entity = level.getEntity(order.customer);
            if (!(entity instanceof EmployeeEntity guest) || guest.isRemoved()) {
                order.missingCustomerTicks += 20;
                if (order.missingCustomerTicks > 200) {
                    depart(context, hotel, order, null, true, true);
                }
                continue;
            }
            order.age++;
            switch (order.stage) {
                case QUEUE -> waiting(context, hotel, rooms, order, guest);
                case ORDERING -> registering(context, hotel, rooms, order, guest);
                case EAT -> staying(context, hotel, rooms, order, guest);
                case CHECKOUT -> checkout(context, hotel, order, guest);
                case LEAVE -> leaving(context, hotel, order, guest);
                default -> depart(context, hotel, order, guest, true, true);
            }
        }

        cleaning(context, hotel, rooms);
        servicing(context, hotel, rooms);
        standby(context, hotel, rooms);
        updatePeak(hotel, rooms.rooms().size());
    }

    /** 房间实际档位：客房升级覆盖优先，否则按床方块颜色判定。 */
    private int effectiveTier(HotelState hotel, HotelRooms.Room room) {
        Integer override = hotel.roomTiers.get(Long.toString(room.head().asLong()));
        return override != null ? override : room.tier();
    }

    /**
     * 员工生命周期驱动（与本体店型完全一致）。
     * 经 Mixin 暴露的 ShopStaffLifecycle.update 完整负责：按排班生成/召回员工（含 NBT 恢复、
     * routine.away 清理、离店存档）、上/下班与待召回流程——此前因包级私有无法调用，
     * 自建的 place+清 away 替代方案由此退役。
     * available 判定与烘焙坊循环同构：ShopStaffLife.available（排班在班且无计划占用）。
     */
    private void staffLifecycle(ShopWorkContext context) {
        ServerLevel level = context.level();
        ShopData.Shop shop = context.shop();
        RestaurantData.Restaurant state = context.state();
        for (EmploymentData.Worker w : context.allWorkers()) {
            boolean available = ShopStaffLifeAccess.invokeAvailable(state, w.profile().id(), w.job());
            ShopStaffLifecycleAccess.invokeUpdate(level, shop, state, w, context.facilities(), available);
        }
    }

    // —— P3 货物架零售 ——

    /**
     * 货物架零售玩法主循环：需求生成 → 过期清理 → 任务分配 → 服务员任务推进。
     * 每 10 刻推进一次（operate 每 5 刻一次，cycle % 10 取偶数次）。
     * 1.4.1 起货架配置直接存本体 RetailState（r.retail.shelves）——与便利店同款备货 UI
     * （ShopScreen 备货页签 + RetailPackets）原生打通；旧档配置在首次运行时迁入。
     */
    private void retail(ShopWorkContext context, HotelState hotel, HotelRooms rooms) {
        if (cycle % 10 != 0) {
            return;
        }
        ServerLevel level = context.level();
        List<BlockPos> shelves = livingShelves(context);
        RetailState retail = context.state().retail;
        if (shelves.isEmpty()) {
            if (!hotel.demands.isEmpty() || !hotel.tasks.isEmpty() || !retail.shelves.isEmpty()) {
                hotel.demands.clear();
                hotel.tasks.clear();
                retail.shelves.clear();
                retail.revision++;
                RestaurantData.get(context.server()).setDirty();
            }
            return;
        }
        migrateRetail(context, hotel, retail);
        purgeRemovedShelves(retail, shelves);
        hotel.tasks.removeIf(t -> "RESTOCK".equals(t.type) && t.shelf != null
                && !shelves.stream().anyMatch(p -> Long.toString(p.asLong()).equals(t.shelf)));
        syncShelves(context, hotel, retail, shelves);
        generateDemands(context, hotel, retail, level);
        expireDemands(context, hotel, level);
        assignWaiterTasks(context, hotel, retail);
        runWaiterTasks(context, hotel, retail);
    }

    /** 旧档迁移：把 HotelState.shelves（1.4.0 及之前的配置）搬入本体 RetailState。 */
    private void migrateRetail(ShopWorkContext context, HotelState hotel, RetailState retail) {
        if (hotel.retailMigrated || hotel.shelves.isEmpty()) {
            hotel.retailMigrated = true;
            return;
        }
        boolean moved = false;
        int migratedCount = hotel.shelves.size();
        for (Map.Entry<String, HotelState.Shelf> entry : hotel.shelves.entrySet()) {
            try {
                long pos = Long.parseLong(entry.getKey());
                RetailState.Shelf target = retail.shelves.computeIfAbsent(pos, k -> new RetailState.Shelf());
                HotelState.Shelf from = entry.getValue();
                for (int i = 0; i < 3; i++) {
                    if (from.configured(i)) {
                        target.products[i] = from.layers[i];
                        target.count[i] = from.counts[i];
                    }
                }
                moved = true;
            } catch (NumberFormatException ignored) {
            }
        }
        hotel.shelves.clear();
        if (moved) {
            retail.revision++;
        }
        hotel.retailMigrated = true;
        HotelApi.LOG.info("货物架配置已迁移到本体零售数据层（{} 个货架）", migratedCount);
        RestaurantData.get(context.server()).setDirty();
    }

    /** 仍在店内的货架位置（本体便利店货架，方块实体在下半块）。 */
    private List<BlockPos> livingShelves(ShopWorkContext context) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos pos : context.facilities().extension(HotelShelves.class, HotelShelves.EMPTY).shelves()) {
            if (context.level().getBlockEntity(pos) instanceof RetailShelfBlockEntity) {
                out.add(pos.immutable());
            }
        }
        return out;
    }

    /** 清理已被拆除的货架：状态、补货任务一并移除。 */
    private void purgeRemovedShelves(RetailState retail, List<BlockPos> shelves) {
        java.util.Set<Long> alive = new java.util.HashSet<>();
        for (BlockPos p : shelves) {
            alive.add(p.asLong());
        }
        retail.shelves.keySet().removeIf(pos -> !alive.contains(pos));
    }

    /** 货架状态对齐：新货架建条目、层存量钳到单层容量、并推送方块实体渲染。 */
    private void syncShelves(ShopWorkContext context, HotelState hotel, RetailState retail, List<BlockPos> shelves) {
        ServerLevel level = context.level();
        for (BlockPos pos : shelves) {
            RetailState.Shelf s = retail.shelves.computeIfAbsent(pos.asLong(), k -> new RetailState.Shelf());
            for (int i = 0; i < 3; i++) {
                if (!s.products[i].isEmpty()) {
                    int cap = HotelGoods.perLayer(s.products[i]);
                    if (cap > 0 && s.count[i] > cap) {
                        s.count[i] = cap;
                    }
                } else if (s.count[i] != 0) {
                    s.count[i] = 0;
                }
            }
            // 性能：内容签名未变化时跳过 BE 写入（此前每 10 刻全量推送造成持续方块实体更新）。
            pushDisplay(hotel, level, pos, s);
        }
    }

    /** 把三层状态写进货架方块实体（供本体货架渲染器显示商品）。签名未变化时跳过写入。 */
    private void pushDisplay(HotelState hotel, ServerLevel level, BlockPos pos, RetailState.Shelf s) {
        String signature = hotel.displaySignature(s);
        String key = Long.toString(pos.asLong());
        if (signature.equals(hotel.shelfDisplaySig.get(key))) {
            return;
        }
        hotel.shelfDisplaySig.put(key, signature);
        if (level.getBlockEntity(pos) instanceof RetailShelfBlockEntity be) {
            String[] products = new String[3];
            for (int i = 0; i < 3; i++) {
                products[i] = s.products[i] == null ? "" : s.products[i];
            }
            be.display(products, s.count);
        }
    }

    /** 需求生成：坐在床上的房客小概率随机想要一件货架现货（按层取）。 */
    private void generateDemands(ShopWorkContext context, HotelState hotel, RetailState retail, ServerLevel level) {
        if (hotel.demands.size() >= 4) {
            return;
        }
        for (RestaurantData.Order o : context.state().orders) {
            if (hotel.demands.size() >= 4) {
                return;
            }
            if (o.stage != RestaurantData.Stage.EAT) {
                continue;
            }
            HotelState.Stay stay = hotel.stays.get(o.id.toString());
            if (stay == null || !stay.lying) {
                continue;
            }
            String oid = o.id.toString();
            if (hotel.demands.stream().anyMatch(d -> d.orderId.equals(oid))) {
                continue;
            }
            if (level.random.nextInt(60) != 0) {
                continue;
            }
            java.util.Set<String> stocked = new java.util.LinkedHashSet<>();
            for (RetailState.Shelf sh : retail.shelves.values()) {
                for (int i = 0; i < 3; i++) {
                    if (!sh.products[i].isEmpty() && sh.count[i] > 0) {
                        stocked.add(sh.products[i]);
                    }
                }
            }
            if (stocked.isEmpty()) {
                continue;
            }
            java.util.List<String> ids = new java.util.ArrayList<>(stocked);
            String pick = ids.get(level.random.nextInt(ids.size()));
            HotelState.Demand d = new HotelState.Demand();
            d.orderId = oid;
            d.customer = o.customer.toString();
            d.goods = pick;
            d.createdAt = level.getGameTime();
            hotel.demands.add(d);
            EmployeeEntity guest = entityOf(level, o.customer);
            if (guest != null) {
                guest.express(NpcEmotion.QUESTION);
                ShopStaff.status(guest, "想要 " + HotelGoods.name(pick));
            }
            RestaurantData.get(context.server()).setDirty();
        }
    }

    /** 需求过期（10 分钟无人满足）：房客失望，需求与对应任务移除。 */
    private void expireDemands(ShopWorkContext context, HotelState hotel, ServerLevel level) {
        long now = level.getGameTime();
        java.util.Iterator<HotelState.Demand> it = hotel.demands.iterator();
        while (it.hasNext()) {
            HotelState.Demand d = it.next();
            if (now - d.createdAt <= 6000L) {
                continue;
            }
            it.remove();
            hotel.tasks.removeIf(t -> "DELIVER".equals(t.type) && d.orderId.equals(t.orderId));
            EmployeeEntity guest = entityOf(level, java.util.UUID.fromString(d.customer));
            if (guest != null) {
                guest.express(NpcEmotion.SWEAT);
                ShopStaff.status(guest, "没等到想要的东西");
            }
            RestaurantData.get(context.server()).setDirty();
        }
    }

    /** 任务分配：需求→送货任务；缺货货架→补货任务；空闲服务员领任务。 */
    private void assignWaiterTasks(ShopWorkContext context, HotelState hotel, RetailState retail) {
        boolean changed = false;
        for (HotelState.Demand d : hotel.demands) {
            boolean has = hotel.tasks.stream().anyMatch(t -> "DELIVER".equals(t.type) && d.orderId.equals(t.orderId));
            if (!has) {
                HotelState.WaiterTask t = new HotelState.WaiterTask();
                t.type = "DELIVER";
                t.orderId = d.orderId;
                t.customer = d.customer;
                t.goods = d.goods;
                t.phase = 0;
                t.createdAt = context.level().getGameTime();
                hotel.tasks.add(t);
                changed = true;
            }
        }
        for (Map.Entry<Long, RetailState.Shelf> e : retail.shelves.entrySet()) {
            RetailState.Shelf s = e.getValue();
            boolean understocked = false;
            String firstGoods = "";
            for (int i = 0; i < 3; i++) {
                if (!s.products[i].isEmpty() && s.count[i] < HotelGoods.perLayer(s.products[i])) {
                    understocked = true;
                    if (firstGoods.isEmpty()) {
                        firstGoods = s.products[i];
                    }
                }
            }
            String key = Long.toString(e.getKey());
            if (understocked && hotel.tasks.stream().noneMatch(t -> "RESTOCK".equals(t.type) && key.equals(t.shelf))) {
                HotelState.WaiterTask t = new HotelState.WaiterTask();
                t.type = "RESTOCK";
                t.shelf = key;
                t.goods = firstGoods;
                t.phase = 0;
                t.createdAt = context.level().getGameTime();
                hotel.tasks.add(t);
                changed = true;
            }
        }
        // 两类人员：服务员（WAITER）送货物上门；补货员（STOCKER，本体便利店内置岗位）补满货架。
        java.util.Set<UUID> freeWaiters = new java.util.HashSet<>();
        java.util.Set<UUID> freeStockers = new java.util.HashSet<>();
        for (EmploymentData.Worker w : context.allWorkers()) {
            boolean present = ShopStaffLifeAccess.invokeAvailable(context.state(), w.profile().id(), w.job())
                    && context.level().getEntity(w.profile().id()) instanceof EmployeeEntity;
            if (!present) {
                continue;
            }
            if (w.job() == ShopJob.WAITER) {
                freeWaiters.add(w.profile().id());
            } else if (w.job() == ShopJob.STOCKER) {
                freeStockers.add(w.profile().id());
            }
        }
        for (HotelState.WaiterTask t : hotel.tasks) {
            if (t.waiter != null && !freeWaiters.contains(java.util.UUID.fromString(t.waiter))
                    && !freeStockers.contains(java.util.UUID.fromString(t.waiter))) {
                t.waiter = null;
                changed = true;
            }
        }
        // 送货任务：服务员优先；服务员全忙且有积压时，补货员顶班送货。
        long pendingDeliver = hotel.tasks.stream().filter(t -> t.waiter == null && "DELIVER".equals(t.type)).count();
        for (UUID waiterId : freeWaiters) {
            boolean busy = hotel.tasks.stream().anyMatch(t -> waiterId.toString().equals(t.waiter));
            if (busy) {
                continue;
            }
            HotelState.WaiterTask pick = hotel.tasks.stream()
                    .filter(t -> t.waiter == null && "DELIVER".equals(t.type))
                    .findFirst()
                    .orElse(null);
            if (pick != null) {
                pick.waiter = waiterId.toString();
                changed = true;
            }
        }
        // 补货员：优先补货；货架都有货且送货有积压时顶班送货。
        boolean restockAvailable = hotel.tasks.stream().anyMatch(t -> t.waiter == null && "RESTOCK".equals(t.type));
        for (UUID stockerId : freeStockers) {
            boolean busy = hotel.tasks.stream().anyMatch(t -> stockerId.toString().equals(t.waiter));
            if (busy) {
                continue;
            }
            HotelState.WaiterTask pick = hotel.tasks.stream()
                    .filter(t -> t.waiter == null && (restockAvailable
                            ? "RESTOCK".equals(t.type)
                            : (pendingDeliver > 0 && "DELIVER".equals(t.type))))
                    .findFirst()
                    .orElse(null);
            if (pick != null) {
                pick.waiter = stockerId.toString();
                changed = true;
            }
        }
        // 补货任务：补货员专属。
        for (UUID stockerId : freeStockers) {
            boolean busy = hotel.tasks.stream().anyMatch(t -> stockerId.toString().equals(t.waiter));
            if (busy) {
                continue;
            }
            HotelState.WaiterTask pick = hotel.tasks.stream()
                    .filter(t -> t.waiter == null && "RESTOCK".equals(t.type))
                    .findFirst()
                    .orElse(null);
            if (pick != null) {
                pick.waiter = stockerId.toString();
                changed = true;
            }
        }
        if (changed) {
            RestaurantData.get(context.server()).setDirty();
        }
    }

    /** 服务员任务推进（送货/补货状态机）。 */
    private void runWaiterTasks(ShopWorkContext context, HotelState hotel, RetailState retail) {
        ServerLevel level = context.level();
        // 遍历拷贝：循环体内 enqueueRestock 会向 hotel.tasks 添加元素，
        // 活迭代器会抛 ConcurrentModificationException（曾致内置服务器崩溃）。
        for (HotelState.WaiterTask t : new ArrayList<>(hotel.tasks)) {
            // 未派单（waiter 为 null）的任务跳过——UUID.fromString 不接受 null。
            if (t.waiter == null || t.waiter.isEmpty()) {
                continue;
            }
            EmployeeEntity waiter = entityOf(level, java.util.UUID.fromString(t.waiter));
            if (waiter == null) {
                t.waiter = null;
                continue;
            }
            switch (t.type == null ? "" : t.type) {
                case "DELIVER" -> runDeliver(context, hotel, retail, t, waiter, hotel.tasks);
                case "RESTOCK" -> runRestock(context, hotel, retail, t, waiter, hotel.tasks);
                default -> hotel.tasks.remove(t);
            }
        }
    }

    /**
     * 零售任务卡死检测：当前阶段超过 1200 刻未完成 → 换人重派（resets ≥2 放弃）。
     * 返回 true 表示本次应跳过该任务。
     */
    private boolean taskStuck(ShopWorkContext context, HotelState.WaiterTask t, EmployeeEntity waiter) {
        long now = context.level().getGameTime();
        if (t.stuckSince == 0) {
            t.stuckSince = now;
            return false;
        }
        if (now - t.stuckSince <= 1200) {
            return false;
        }
        t.resets++;
        waiter.retailVisual(0, java.util.List.of());
        waiter.getNavigation().stop();
        if (t.resets >= 2) {
            HotelApi.LOG.warn("[{}] 零售任务连续卡死已放弃：{} @ {} goods={}",
                    context.shop().id, t.type, t.shelf, t.goods);
            return true;
        }
        t.waiter = null;
        t.phase = 0;
        t.stuckSince = now;
        RestaurantData.get(context.server()).setDirty();
        return true;
    }


    /** 货架旁的可站立格（货架方块自身有碰撞，移动目标不能是货架本身）。 */
    private BlockPos adjacentStandable(ServerLevel level, BlockPos pos) {
        for (Direction side : List.of(Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST)) {
            BlockPos at = pos.relative(side);
            if (ShopFacilities.standable(level, at)) {
                return at;
            }
            BlockPos up = at.above();
            if (ShopFacilities.standable(level, up)) {
                return up;
            }
        }
        return null;
    }

    /** 送货：从货架层取货 → 送到房客手中（房客即时支付，服务员手提商品）。 */
    private void runDeliver(ShopWorkContext context, HotelState hotel, RetailState retail, HotelState.WaiterTask t,
                            EmployeeEntity waiter, java.util.List<HotelState.WaiterTask> tasks) {
        HotelState.Demand d = hotel.demands.stream().filter(x -> x.orderId.equals(t.orderId)).findFirst().orElse(null);
        if (d == null) {
            waiter.retailVisual(0, java.util.List.of());
            tasks.remove(t);
            return;
        }
        ServerLevel level = context.level();
        if (t.phase == 0) {
            String[] found = findStock(retail, t.goods);
            if (found == null) {
                enqueueRestock(context, hotel, retail, t.goods);
                return;
            }
            t.shelf = found[0];
            t.layer = Integer.parseInt(found[1]);
            t.stuckSince = 0;
            t.phase = 1;
        }
        if (t.phase == 1) {
            if (taskStuck(context, t, waiter)) {
                return;
            }
            BlockPos shelf = BlockPos.of(Long.parseLong(t.shelf));
            BlockPos stand = adjacentStandable(level, shelf);
            BlockPos look = shelf;
            if (!near(waiter, stand != null ? stand : shelf, 4.0)) {
                RestaurantServiceAccess.invokeGo(waiter, stand != null ? stand : shelf, look, "取货", 0.9,
                        null);
                return;
            }
            RetailState.Shelf s = retail.shelves.get(Long.parseLong(t.shelf));
            if (s == null || s.count[t.layer] <= 0) {
                return;
            }
            s.count[t.layer]--;
            pushDisplay(hotel, level, shelf, s);
            waiter.retailVisual(2, java.util.List.of(t.goods));
            t.phase = 2;
            t.stuckSince = 0;
        }
        if (t.phase == 2) {
            if (taskStuck(context, t, waiter)) {
                return;
            }
            EmployeeEntity guest = entityOf(level, java.util.UUID.fromString(t.customer));
            if (guest == null) {
                waiter.retailVisual(0, java.util.List.of());
                tasks.remove(t);
                hotel.demands.removeIf(x -> x.orderId.equals(t.orderId));
                RestaurantData.get(context.server()).setDirty();
                return;
            }
            HotelState.Stay stay = hotel.stays.get(t.orderId);
            BlockPos target = stay != null ? BlockPos.of(stay.approach) : guest.blockPosition();
            if (!near(waiter, target, 9.0)) {
                RestaurantServiceAccess.invokeGo(waiter, target, target, "送货", 0.9,
                        null);
                return;
            }
            waiter.retailVisual(0, java.util.List.of());
            int price = HotelGoods.price(t.goods);
            EmploymentData.get(context.server()).changeBalance(
                    context.shop().owner, price, context.shop().id, "房客购物 · " + HotelGoods.name(t.goods));
            hotel.goodsIncome += price;
            hotel.todayIncome += price;
            hotel.totalIncome += price;
            guest.express(NpcEmotion.LOVE);
            ShopStaff.status(guest, "收到 " + HotelGoods.name(t.goods));
            hotel.demands.removeIf(x -> x.orderId.equals(t.orderId));
            tasks.remove(t);
            RestaurantData.get(context.server()).setDirty();
        }
    }

    /** 补货：走到货架旁（免费补满各层，对齐便利店“补货员免费补满卖空的货架”）。 */
    private void runRestock(ShopWorkContext context, HotelState hotel, RetailState retail, HotelState.WaiterTask t,
                            EmployeeEntity waiter, java.util.List<HotelState.WaiterTask> tasks) {
        RetailState.Shelf s = retail.shelves.get(Long.parseLong(t.shelf));
        if (s == null) {
            waiter.retailVisual(0, java.util.List.of());
            tasks.remove(t);
            return;
        }
        if (t.phase == 0) {
            waiter.retailVisual(1, java.util.List.of(t.goods));
            t.phase = 1;
            t.stuckSince = 0;
        }
        if (t.phase == 1) {
            if (taskStuck(context, t, waiter)) {
                return;
            }
            ServerLevel level = context.level();
            BlockPos shelf = BlockPos.of(Long.parseLong(t.shelf));
            BlockPos stand = adjacentStandable(level, shelf);
            if (!near(waiter, stand != null ? stand : shelf, 4.0)) {
                RestaurantServiceAccess.invokeGo(waiter, stand != null ? stand : shelf, shelf, "补货", 0.9,
                        null);
                return;
            }
            for (int i = 0; i < 3; i++) {
                if (!s.products[i].isEmpty()) {
                    s.count[i] = HotelGoods.perLayer(s.products[i]);
                }
            }
            pushDisplay(hotel, level, shelf, s);
            waiter.retailVisual(0, java.util.List.of());
            tasks.remove(t);
            RestaurantData.get(context.server()).setDirty();
        }
    }

    /** 找到有指定商品现货的货架层，返回 {shelfKey, layer}；没有则 null。 */
    private String[] findStock(RetailState retail, String goods) {
        for (Map.Entry<Long, RetailState.Shelf> e : retail.shelves.entrySet()) {
            RetailState.Shelf s = e.getValue();
            for (int i = 0; i < 3; i++) {
                if (goods.equals(s.products[i]) && s.count[i] > 0) {
                    return new String[]{Long.toString(e.getKey()), Integer.toString(i)};
                }
            }
        }
        return null;
    }

    /** 给指定商品确保存在一条补货任务（取配置了该商品的货架，其次任意货架）。 */
    private void enqueueRestock(ShopWorkContext context, HotelState hotel, RetailState retail, String goods) {
        String target = null;
        for (Map.Entry<Long, RetailState.Shelf> entry : retail.shelves.entrySet()) {
            if (java.util.Arrays.stream(entry.getValue().products).anyMatch(goods::equals)) {
                target = Long.toString(entry.getKey());
                break;
            }
        }
        if (target == null && !retail.shelves.isEmpty()) {
            target = Long.toString(retail.shelves.keySet().iterator().next());
        }
        if (target == null) {
            return;
        }
        String chosen = target;
        boolean has = hotel.tasks.stream().anyMatch(t -> "RESTOCK".equals(t.type) && chosen.equals(t.shelf));
        if (has) {
            return;
        }
        HotelState.WaiterTask t = new HotelState.WaiterTask();
        t.type = "RESTOCK";
        t.shelf = chosen;
        t.goods = goods;
        t.phase = 0;
        t.createdAt = context.level().getGameTime();
        hotel.tasks.add(t);
        RestaurantData.get(context.server()).setDirty();
    }

    private boolean near(Entity e, BlockPos pos) {
        return e.blockPosition().distSqr(pos) <= 2.25;
    }

    private boolean near(Entity e, BlockPos pos, double radiusSq) {
        return e.blockPosition().distSqr(pos) <= radiusSq;
    }

    /** P0-E：每 20 刻刷新房态缓存供台账读取，替代台账请求时的全店扫描。 */
    private void refreshRoomCache(HotelState hotel, HotelRooms rooms, ServerLevel level) {
        if (cycle % 20 != 0) {
            return;
        }
        List<int[]> fresh = new ArrayList<>();
        boolean deluxe = false;
        for (HotelRooms.Room room : rooms.rooms()) {
            long head = room.head().asLong();
            int status;
            if (hotel.stayingAt(head)) {
                status = 1;
            } else if (hotel.cleaningAt(head)) {
                status = 3;
            } else if (Boolean.TRUE.equals(hotel.dirty.get(Long.toString(head)))) {
                status = 2;
            } else {
                status = 0;
            }
            int tier = effectiveTier(hotel, room);
            fresh.add(new int[]{room.head().getX(), room.head().getY(), room.head().getZ(), tier, status});
            deluxe |= tier == HotelRooms.TIER_DELUXE;
        }
        if (!fresh.equals(hotel.roomCache)) {
            hotel.roomCache = fresh;
            hotel.bump();
        }
        if (hotel.hasDeluxe != deluxe) {
            hotel.hasDeluxe = deluxe;
            hotel.bump();
        }
        // F1：门牌房态同步（四态贴图；门牌被拆除时自动解绑）。
        syncSigns(hotel, level);
    }

    /** F1：把每块已绑定门牌的 room_state 与房态对齐；门牌方块消失则解除绑定。 */
    private void syncSigns(HotelState hotel, ServerLevel level) {
        if (hotel.signByRoom.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (var entry : new ArrayList<>(hotel.signByRoom.entrySet())) {
            BlockPos sign = BlockPos.of(Long.parseLong(entry.getValue()));
            if (!level.isLoaded(sign) || !(level.getBlockState(sign).getBlock() instanceof RoomSignBlock signBlock)) {
                hotel.signByRoom.remove(entry.getKey());
                changed = true;
                continue;
            }
            long head = Long.parseLong(entry.getKey());
            int status;
            if (hotel.stayingAt(head)) {
                status = 1;
            } else if (hotel.cleaningAt(head)) {
                status = 3;
            } else if (Boolean.TRUE.equals(hotel.dirty.get(Long.toString(head)))) {
                status = 2;
            } else {
                status = 0;
            }
            RoomSignBlock.SignState wanted = switch (status) {
                case 1 -> RoomSignBlock.SignState.OCCUPIED;
                case 2 -> RoomSignBlock.SignState.DIRTY;
                case 3 -> RoomSignBlock.SignState.CLEANING;
                default -> RoomSignBlock.SignState.FREE;
            };
            BlockState current = level.getBlockState(sign);
            if (current.getValue(RoomSignBlock.SIGN_STATE) != wanted) {
                level.setBlock(sign, current.setValue(RoomSignBlock.SIGN_STATE, wanted), 3);
            }
        }
        if (changed) {
            hotel.bump();
        }
    }

    /** P2-F：新的一天对隔夜脏房掷投诉判定，赔款走负向流水。 */
    private void complaints(ShopWorkContext context, HotelState hotel, long day) {
        ServerLevel level = context.level();
        for (Map.Entry<String, Boolean> entry : new ArrayList<>(hotel.dirty.entrySet())) {
            if (!Boolean.TRUE.equals(entry.getValue())) {
                continue;
            }
            Long since = hotel.dirtySince.get(entry.getKey());
            if (since == null || day - since < 1) {
                continue;
            }
            if (level.random.nextFloat() >= HotelConfig.COMPLAINT_CHANCE.get()) {
                continue;
            }
            long fine = Math.round(hotel.standardPrice * HotelConfig.COMPLAINT_FINE_RATIO.get());
            ShopContext api = new ShopContext(context.server(), context.shop());
            if (api.changeBalance(-fine, "客房投诉赔款")) {
                hotel.totalComplaints++;
                hotel.complaintsToday++;
                hotel.bump();
            }
        }
    }

    // —— 候房（P1-F 候位椅） ——

    private void waiting(ShopWorkContext context, HotelState hotel, HotelRooms rooms, RestaurantData.Order order, EmployeeEntity guest) {
        HotelState.Stay stay = hotel.stays.get(order.id.toString());
        if (stay == null) {
            depart(context, hotel, order, guest, true, true);
            return;
        }
        Long seat = hotel.waitingSeats.get(order.id.toString());
        if (seat == null) {
            seat = freeChair(hotel, rooms);
            if (seat != null) {
                hotel.waitingSeats.put(order.id.toString(), seat);
            }
        }
        boolean seated = seat != null;
        int limit = seated ? 2400 : 1200;
        if (order.age > limit) {
            order.cancelled = true;
            depart(context, hotel, order, guest, true, true);
        } else {
            if (seated) {
                BlockPos spot = BlockPos.of(seat);
                if (near(guest, spot)) {
                    ShopStaff.status(guest, "在候位椅旁等候");
                } else {
                    walk(guest, spot, "前往候位椅", order);
                }
            } else {
                ShopStaff.status(guest, "等候空房");
            }
        }
    }

    private Long freeChair(HotelState hotel, HotelRooms rooms) {
        for (BlockPos chair : rooms.chairs()) {
            long key = chair.asLong();
            boolean taken = false;
            for (Long used : hotel.waitingSeats.values()) {
                if (used == key) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return key;
            }
        }
        return null;
    }

    // —— 前台登记 ——

    private void registering(ShopWorkContext context, HotelState hotel, HotelRooms rooms, RestaurantData.Order order, EmployeeEntity guest) {
        ShopFacilities.Station register = registerOf(context);
        if (register == null) {
            cancel(context, hotel, order, guest, "前台收银台被拆除");
            return;
        }
        BlockPos front = register.customer() != null ? register.customer() : register.block();
        if (!walk(guest, front, "前往前台登记", order)) {
            timeout(context, hotel, order, guest, WALK_TIMEOUT);
            return;
        }
        EmployeeEntity receptionist = receptionistNear(context, register); // 半径 32 格，确保能找到远处的收银员
        if (receptionist == null) {
            // 找不到收银员：快速失败，避免顾客长时间堆积在收银台。
            ShopStaff.status(guest, "前台无人接待（收银员未上岗或太远）");
            cancel(context, hotel, order, guest, "收银员未在岗");
            return;
        }
        walkWorker(receptionist, register.worker());
        ShopStaff.status(receptionist, "办理入住登记");
        receptionist.activity(EmployeeEntity.Activity.GREET);
        order.work += Math.round(5 * receptionist.workRate(2));
        if (order.work >= HotelConfig.REGISTER_WORK_TICKS.get()) {
            order.work = 0;
            receptionist.activity(EmployeeEntity.Activity.IDLE);
            guest.express(NpcEmotion.OK);
            order.ordered = true;
            order.stage = RestaurantData.Stage.EAT;
        }
    }

    // —— 住宿 ——

    private void staying(ShopWorkContext context, HotelState hotel, HotelRooms rooms, RestaurantData.Order order, EmployeeEntity guest) {
        HotelState.Stay stay = hotel.stays.get(order.id.toString());
        if (stay == null) {
            depart(context, hotel, order, guest, true, true);
            return;
        }
        // P0-D：床被拆除 → 按已住时长结算后离店，不产生待清洁。（order.age 每次 operate +1 ≙ 5 刻，%8 ≙ 40 刻）
        if (order.age % 8 == 0 && bedGone(context.level(), stay.head)) {
            settle(context, hotel, order, guest, null);
            depart(context, hotel, order, guest, false, false);
            return;
        }
        BlockPos approach = BlockPos.of(stay.approach);
        if (!stay.lying) {
            if (!walk(guest, approach, "前往客房", order)) {
                timeout(context, hotel, order, guest, WALK_TIMEOUT);
                return;
            }
            if (!context.state().open) {
                order.cancelled = true;
                depart(context, hotel, order, guest, true, true);
                return;
            }
            beginSit(context, stay, guest);
            if (!stay.visitCounted) {
                stay.visitCounted = true;
                DayEndData.get(context.server()).visit(context.server(), context.shop(), context.state(), order);
            }
        }
        ShopStaff.status(guest, stay.hourly ? "钟点休息中" : "住宿休息中");
        // 恢复信号：共享的进度恢复体系据此判定房客仍在推进。
        order.restroom.progress += 5;

        // 在座守卫：房客被异常移出座位时重新上座（骑乘状态下被载具锚定，正常不会触发）。
        if (stay.lying && !guest.isPassenger()) {
            beginSit(context, stay, guest);
        }

        long nightTicks = HotelConfig.NIGHT_TICKS.get();
        // 计夜：按入住以来的真实流逝刻数折算（不能用 gameTime 取模——相位错开会永远不计夜）。
        long quarterTicks = Math.max(1L, nightTicks / 4);
        long elapsed = context.level().getGameTime() - stay.checkinGameTime;
        long quarters = elapsed / quarterTicks;
        if (quarters > stay.quarterNights) {
            stay.quarterNights = quarters;
            // F6：每住满半晚（2 个四分之一晚）掷一次服务需求；单次住宿有上限。
            if (stay.quarterNights >= (stay.servicesCreated + 1) * 2L
                    && stay.servicesCreated < HotelConfig.SERVICE_MAX_PER_STAY.get()
                    && context.level().random.nextFloat() < HotelConfig.SERVICE_CHANCE.get()) {
                createServiceJob(context, hotel, stay);
            }
        }
        long target = stay.hourly ? 2 : stay.plannedNights * 4;
        if (stay.quarterNights >= target) {
            endSit(guest, stay);
            order.served = true;
            order.stage = RestaurantData.Stage.CHECKOUT;
        }
    }

    /** F6：为在住房客生成一条上门服务需求（文案取自配置池）。 */
    private void createServiceJob(ShopWorkContext context, HotelState hotel, HotelState.Stay stay) {
        var pool = HotelConfig.SERVICE_DEMANDS.get();
        if (pool.isEmpty()) {
            return;
        }
        HotelState.ServiceJob job = new HotelState.ServiceJob();
        job.id = UUID.randomUUID().toString();
        job.orderId = stay.orderId;
        job.head = stay.head;
        job.approach = stay.approach;
        job.demand = pool.get(context.level().random.nextInt(pool.size()));
        job.createdDay = hotel.currentDay;
        hotel.serviceJobs.put(job.id, job);
        stay.servicesCreated++;
        EmployeeEntity guest = entityOf(context.level(), stay.customer);
        if (guest != null) {
            guest.express(NpcEmotion.QUESTION);
            ShopStaff.status(guest, "提出需求：" + job.demand);
        }
        hotel.bump();
        RestaurantData.get(context.server()).setDirty();
    }

    /**
     * F6：上门服务任务推进——派单（保洁优先、其次前台）→ 走到床边 → GREET 完成 → 概率小费。
     * 与保洁/服务员送货共用人力但互不双占；任务随住宿结束自动撤销（endStay）。
     */
    private void servicing(ShopWorkContext context, HotelState hotel, HotelRooms rooms) {
        if (hotel.serviceJobs.isEmpty()) {
            return;
        }
        ServerLevel level = context.level();
        // 推进已派任务。
        for (HotelState.ServiceJob job : new ArrayList<>(hotel.serviceJobs.values())) {
            if (job.worker == null || job.worker.isEmpty()) {
                continue;
            }
            EmployeeEntity npc = entityOf(level, job.worker);
            HotelState.Stay stay = hotel.stays.get(job.orderId);
            if (npc == null || npc.isRemoved() || !onDuty(context, job.worker) || stay == null) {
                job.worker = ""; // 释放回待派池（房间若转待清洁，任务已由 endStay 清理）
                continue;
            }
            BlockPos approach = BlockPos.of(job.approach);
            if (!near(npc, approach)) {
                ShopStaff.status(npc, "响应房客需求");
                continue;
            }
            npc.activity(EmployeeEntity.Activity.GREET);
            ShopStaff.status(npc, "上门服务 · " + job.demand);
            job.progress += 5 * npc.workRate(1);
            if (job.progress >= HotelConfig.SERVICE_TICKS.get()) {
                stay.servicesDone++;
                long tip = Math.round(hotel.standardPrice * (0.005 + level.random.nextDouble() * 0.01));
                if (tip > 0) {
                    ShopContext api = new ShopContext(context.server(), context.shop());
                    if (api.changeBalance(tip, "客房服务小费 · " + job.demand)) {
                        hotel.serviceIncome += tip;
                        hotel.todayIncome += tip;
                        hotel.totalIncome += tip;
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntity(
                                npc, new org.merlin204.wss.shop.DiningIncome(
                                        npc.getX(), npc.getY() + 2.0, npc.getZ(), (int) Math.min(Integer.MAX_VALUE, tip)));
                    }
                }
                EmployeeEntity guest = entityOf(level, stay.customer);
                if (guest != null) {
                    guest.express(NpcEmotion.LOVE);
                    ShopStaff.status(guest, "需求已满足");
                }
                npc.activity(EmployeeEntity.Activity.IDLE);
                hotel.serviceJobs.remove(job.id);
                hotel.bump();
                RestaurantData.get(context.server()).setDirty();
            }
        }
        // 派单。
        if (!context.state().open) {
            return;
        }
        for (HotelState.ServiceJob job : hotel.serviceJobs.values()) {
            if (job.worker != null && !job.worker.isEmpty()) {
                continue;
            }
            EmployeeEntity npc = freeServant(context, hotel);
            if (npc == null) {
                break;
            }
            job.worker = npc.getUUID().toString();
            RestaurantData.get(context.server()).setDirty();
        }
    }

    /** F6：找一名空闲员工执行上门服务（保洁优先、其次前台），不与保洁/送货/其他服务双占。 */
    private EmployeeEntity freeServant(ShopWorkContext context, HotelState hotel) {
        for (ShopJob jobType : List.of(WssHotelMod.MAID, ShopJob.CASHIER)) {
            for (EmploymentData.Worker worker : ShopStaff.onDuty(context, jobType)) {
                String id = worker.profile().id().toString();
                EmployeeEntity npc = entityOf(context.level(), worker.profile().id());
                if (npc == null || hotel.cleans.containsKey(id)) {
                    continue;
                }
                boolean busy = hotel.serviceJobs.values().stream().anyMatch(j -> id.equals(j.worker))
                        || hotel.tasks.stream().anyMatch(t -> id.equals(t.waiter));
                if (!busy) {
                    return npc;
                }
            }
        }
        return null;
    }

    /** F6：跨日检查超时未满足的服务需求——房客体验 -2（结算时生效），计入怠慢。 */
    private void serviceTimeouts(ShopWorkContext context, HotelState hotel, long day) {
        boolean changed = false;
        for (HotelState.ServiceJob job : new ArrayList<>(hotel.serviceJobs.values())) {
            if (day - job.createdDay < 1) {
                continue;
            }
            hotel.serviceJobs.remove(job.id);
            HotelState.Stay stay = hotel.stays.get(job.orderId);
            if (stay != null) {
                stay.slowed = true;
                EmployeeEntity guest = entityOf(context.level(), stay.customer);
                if (guest != null) {
                    guest.express(NpcEmotion.SWEAT);
                    ShopStaff.status(guest, "没等到：" + job.demand);
                }
            }
            hotel.slowCount++;
            changed = true;
        }
        if (changed) {
            hotel.bump();
            RestaurantData.get(context.server()).setDirty();
        }
    }

    /** 床（含两个半块）是否已从世界消失。 */
    private boolean bedGone(ServerLevel level, long headLong) {
        BlockPos head = BlockPos.of(headLong);
        if (level.getBlockState(head).getBlock() instanceof BedBlock) {
            return false;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(head.relative(side)).getBlock() instanceof BedBlock) {
                return false;
            }
        }
        return true;
    }

    /**
     * 上座：用本体坐具体系（DiningFurniture.createSeat）在床位生成通用 SitEntity 并让房客骑乘。
     * 骑乘状态下房客被载具锚定，任何 AI/物理/恢复系统都不会再拉扯位置——取代鬼畜的躺卧+拉回方案。
     * 朝向：坐床头上、面向床尾（createSeat 的 table 参数决定 yaw，随后三组朝向角对齐，
     * 与本体 DiningFurniture.sit() 221-252 行一致）。
     */
    private void beginSit(ShopWorkContext context, HotelState.Stay stay, EmployeeEntity guest) {
        ServerLevel level = context.level();
        BlockPos head = BlockPos.of(stay.head);
        Direction facing = bedFacing(level, head);
        BlockPos foot = head.relative(facing == null ? Direction.NORTH : facing.getOpposite());
        Entity seat = DiningFurniture.createSeat(level, head, foot);
        if (seat == null) {
            stay.lying = true;   // 生成失败兜底：原地站立（bedGone/repairSeats 兜底清理）
            return;
        }
        // 照抄本体 sit()：记录入座点，供本体下座复用。
        seat.getPersistentData().putDouble("WssSeatEntryX", guest.getX());
        seat.getPersistentData().putDouble("WssSeatEntryY", guest.getY());
        seat.getPersistentData().putDouble("WssSeatEntryZ", guest.getZ());
        if (!level.addFreshEntity(seat)) {
            stay.lying = true;
            return;
        }
        guest.getNavigation().stop();
        guest.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        if (!guest.startRiding(seat, true)) {
            seat.discard();
            stay.lying = true;
            return;
        }
        float yaw = seat.getYRot();
        guest.setYRot(yaw);
        guest.yBodyRot = guest.yBodyRotO = yaw;
        guest.yHeadRot = guest.yHeadRotO = yaw;
        stay.lying = true;   // 字段沿用（Gson 存档兼容），语义 = "已入座"
    }

    /** 下座：停止骑乘并丢弃座位实体，落回床边站位。 */
    private void endSit(EmployeeEntity guest, HotelState.Stay stay) {
        Entity seat = guest.getVehicle();
        if (seat != null && DiningFurniture.seatEntity(seat)) {
            guest.stopRiding();
            seat.discard();   // 立即清理（店内残留空座位另有本体 repairSeats 兜底）
        }
        BlockPos approach = BlockPos.of(stay.approach);
        guest.teleportTo(approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5);
        stay.lying = false;
    }

    private Direction bedFacing(ServerLevel level, BlockPos head) {
        if (level.getBlockState(head).getBlock() instanceof BedBlock) {
            return level.getBlockState(head).getValue(BedBlock.FACING);
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(head.relative(side)).getBlock() instanceof BedBlock) {
                return side.getOpposite();
            }
        }
        return null;
    }

    // —— 退房结算 ——

    private void checkout(ShopWorkContext context, HotelState hotel, RestaurantData.Order order, EmployeeEntity guest) {
        HotelState.Stay stay = hotel.stays.get(order.id.toString());
        // F2：退房顺路买饮品——无可达柜台或超时则跳过，不阻塞退房。
        if (stay != null && !drinkStop(context, hotel, order, stay, guest)) {
            return;
        }
        ShopFacilities.Station register = registerOf(context);
        if (register == null) {
            // 收银台被拆：按已住时长直接结算，避免房客被卡死。
            settle(context, hotel, order, guest, null);
            depart(context, hotel, order, guest, false, true);
            return;
        }
        BlockPos front = register.customer() != null ? register.customer() : register.block();
        if (!walk(guest, front, "前往前台退房", order)) {
            timeout(context, hotel, order, guest, WALK_TIMEOUT);
            return;
        }
        EmployeeEntity receptionist = receptionistNear(context, register); // 半径 32 格，确保能找到远处的收银员
        if (receptionist == null) {
            // 找不到收银员：按已住时长直接结算并离开，避免房客被卡死。
            ShopStaff.status(guest, "前台无人接待（收银员未上岗或太远）");
            settle(context, hotel, order, guest, null);
            depart(context, hotel, order, guest, false, true);
            return;
        }
        walkWorker(receptionist, register.worker());
        ShopStaff.status(receptionist, "办理退房结算");
        receptionist.activity(EmployeeEntity.Activity.OPERATE);
        order.work += Math.round(5 * receptionist.workRate(2));
        if (order.work >= HotelConfig.REGISTER_WORK_TICKS.get()) {
            order.work = 0;
            receptionist.activity(EmployeeEntity.Activity.IDLE);
            settle(context, hotel, order, guest, register);
            order.stage = RestaurantData.Stage.LEAVE;
        }
    }

    /** F2：退房顺路买饮品的状态机。返回 true = 环节结束（买了或跳过），可以继续走退房流程。 */
    private boolean drinkStop(ShopWorkContext context, HotelState hotel, RestaurantData.Order order,
                              HotelState.Stay stay, EmployeeEntity guest) {
        if (stay.drinkDone) {
            return true;
        }
        if (!stay.drinkPending) {
            if (!hotel.drinkCountersList().isEmpty()
                    && guest.getRandom().nextFloat() < HotelConfig.DRINK_CHANCE.get()) {
                stay.drinkPending = true;
                stay.drinkCharge = Math.clamp(hotel.drinkPrice, 1, 100000);
                order.citizenDemand = (order.citizenDemand == null ? "" : order.citizenDemand) + " · 想买杯饮品";
            } else {
                stay.drinkDone = true;
                return true;
            }
        }
        BlockPos counter = nearestCounter(hotel, guest);
        if (counter == null || order.age > WALK_TIMEOUT * 2) {
            stay.drinkDone = true; // 不可达或等待过久：跳过，不阻塞退房
            return true;
        }
        if (!near(guest, counter)) {
            walk(guest, counter, "去饮品台买杯饮品", order);
            return false;
        }
        guest.activity(EmployeeEntity.Activity.OPERATE);
        ShopStaff.status(guest, "挑一杯饮品");
        stay.drinkWork += 5 * guest.workRate(1);
        if (stay.drinkWork >= 40) {
            guest.activity(EmployeeEntity.Activity.IDLE);
            stay.drinkDone = true;
            return true;
        }
        return false;
    }

    private BlockPos nearestCounter(HotelState hotel, EmployeeEntity guest) {
        BlockPos best = null;
        double bestSqr = Double.MAX_VALUE;
        for (BlockPos spot : hotel.drinkCountersList()) {
            double sqr = guest.blockPosition().distSqr(spot);
            if (sqr < bestSqr) {
                bestSqr = sqr;
                best = spot;
            }
        }
        return best;
    }

    /** 结算房费并接入本体评价体系（普通粉丝 / 特殊剧情由 review 分流）。 */
    private void settle(ShopWorkContext context, HotelState hotel, RestaurantData.Order order, EmployeeEntity guest, ShopFacilities.Station register) {
        if (order.settled) {
            return;
        }
        HotelState.Stay stay = hotel.stays.get(order.id.toString());
        ServerLevel level = context.level();
        long day = hotel.currentDay;

        double env = envFactor(context.server(), context.shop());
        double unit = hotel.priceOf(stay == null ? 0 : stay.tier) * env;
        long nightsBilled;
        double price;
        if (stay != null && stay.hourly) {
            nightsBilled = 0; // 钟点房不计晚数
            price = hotel.hourlyPrice; // 钟点房固定价（1200~2000，可在客房升级页调整）
        } else {
            long quarters = stay == null ? 4 : stay.quarterNights;
            long nights = Math.max(1, (quarters + 3) / 4);
            nightsBilled = nights;
            long planned = stay == null ? 1 : stay.plannedNights;
            long extra = Math.max(0, nights - planned);
            double base = unit * (nights - extra);
            // F5 长租折扣：计划晚数达门槛时对基础房费生效（超时部分不打折）。
            if (planned >= HotelConfig.LONG_STAY_MIN_NIGHTS.get()) {
                base *= HotelConfig.LONG_STAY_DISCOUNT.get();
            }
            price = base + unit * HotelConfig.OVERTIME_RATE.get() * extra;
        }
        if (HotelApi.contestDay(day)) {
            price *= 1.0 + HotelConfig.CONTEST_PRICE_BONUS.get();
        }
        if (nightNow(level)) {
            price *= 1.0 + HotelConfig.NIGHT_PRICE_BONUS.get();
        }
        // F7 节气房价系数（与旺日/夜间叠乘）。
        price *= HotelSeasons.price(HotelSeasons.current(context.server()).term());
        long breakfast = 0;
        if (stay != null && stay.breakfastOrdered) {
            DishCatalog.Dish dish = DishCatalog.get(stay.breakfastDish);
            breakfast = dish == null ? 0 : Math.round(dish.basePrice() * HotelConfig.BREAKFAST_MARKUP.get());
            price += breakfast;
        }
        // F2：退房顺路购买的饮品。
        long drink = stay != null && stay.drinkPending && stay.drinkDone ? stay.drinkCharge : 0;
        price += drink;

        long amount = Math.round(price);
        ShopContext api = new ShopContext(context.server(), context.shop());
        if (api.changeBalance(amount, "房费收入 · "
                + (stay != null && stay.hourly ? "钟点" : Math.max(1, nightsBilled) + "晚")
                + (breakfast > 0 ? " · 含早餐" : "")
                + (drink > 0 ? " · 含饮品" : ""))) {
            order.paid = true;
            order.settled = true;
            order.price = (int) Math.min(Integer.MAX_VALUE, amount);
            context.state().revenue += amount;
            hotel.totalIncome += amount;
            hotel.todayIncome += amount;
            hotel.totalNights += nightsBilled;
            hotel.breakfastIncome += breakfast;
            if (drink > 0) {
                hotel.drinkIncome += drink;
            }
            if (stay != null) {
                hotel.roomIncome.merge(Long.toString(stay.head), amount, Long::sum);
            }
            hotel.bump();
            HotelApi.event(WssEvents.Type.CUSTOMER_PAID, context.server(), context.shop(), order, amount, 0.0);
        }

        // F3/F4：常客简表（上限 256 条）+ 等级线（升级记日志）。
        hotel.guestHistory.merge(order.customer.toString(), 1L, Long::sum);
        while (hotel.guestHistory.size() > 256) {
            var it = hotel.guestHistory.entrySet().iterator();
            it.next();
            it.remove();
        }
        if (hotel.updateLevel()) {
            HotelApi.LOG.info("[{}] 旅馆升级：{} 级（{}）", context.shop().id, hotel.level, hotel.title());
        }

        // 满意度 → 本体评价（普通粉丝/人气 与 特殊剧情/SPECIAL_REVIEWED 由 review 分流）。
        if (guest != null) {
            int score = satisfaction(hotel, stay, env);
            order.taste = score;
            order.charm = score;
            CityResidents.get(context.server()).served(order, context.state());
            CityResidents.get(context.server()).review(context.server(), context.shop(), order, context.state(), guest);
            DayEndData.get(context.server()).gift(context.server(), context.shop(), context.state(), order);
        }
        if (guest != null && order.price > 0) {
            // 收入飘字（DiningIncome 为本体公开载荷）。
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntity(
                    guest, new org.merlin204.wss.shop.DiningIncome(guest.getX(), guest.getY() + 2.0, guest.getZ(), order.price));
        }
    }

    /** 住宿满意度（0-100）：房型与环境打底，早餐与上门服务加成，投诉/怠慢减分。 */
    private int satisfaction(HotelState hotel, HotelState.Stay stay, double env) {
        int base = 52 + (int) (env * 18) + (stay == null ? 0 : stay.tier) * 6;
        if (stay != null && stay.breakfastOrdered) {
            base += 5;
        }
        if (stay != null) {
            // F6：完成的上门服务 +3/次，超时怠慢 -2。
            base += 3 * stay.servicesDone;
            if (stay.slowed) {
                base -= 2;
            }
        }
        if (hotel.complaintsToday > 0) {
            base -= 10;
        }
        // F4：五星旅馆口碑加成。
        if (hotel.level >= 5) {
            base += 2;
        }
        return Math.clamp(base, 0, 100);
    }

    /** 环境系数：本体环境分（舒适+美观）映射 0.8~1.3。 */
    private double envFactor(MinecraftServer server, ShopData.Shop shop) {
        var scores = HotelApi.environment(server, shop);
        if (scores == null) {
            return 1.0;
        }
        return Math.clamp(1.0 + (scores.comfort() + scores.beauty()) / 150.0, 0.8, 1.3);
    }

    private boolean nightNow(ServerLevel level) {
        return level.getDayTime() % 24000L >= 13000L;
    }

    // —— 离店 ——

    private void leaving(ShopWorkContext context, HotelState hotel, RestaurantData.Order order, EmployeeEntity guest) {
        if (stayLying(hotel, order)) {
            HotelState.Stay stay = hotel.stays.get(order.id.toString());
            endSit(guest, stay);
        }
        BlockPos exit = exitOf(context);
        if (exit == null || walk(guest, exit, "离开旅馆", order) || order.age > LEAVE_TIMEOUT) {
            depart(context, hotel, order, guest, false, true);
        }
    }

    private boolean stayLying(HotelState hotel, RestaurantData.Order order) {
        HotelState.Stay stay = hotel.stays.get(order.id.toString());
        return stay != null && stay.lying;
    }

    /** 结束订单：释放床位与候位、归还市民、广播离店。 */
    private void depart(ShopWorkContext context, HotelState hotel, RestaurantData.Order order, EmployeeEntity guest,
                        boolean abandoned, boolean markDirty) {
        if (guest != null && stayLying(hotel, order)) {
            HotelState.Stay stay = hotel.stays.get(order.id.toString());
            endSit(guest, stay);
        }
        boolean used = order.ordered || order.served || stayUsed(hotel, order);
        HotelState.Stay stay = hotel.endStay(order.id.toString(), markDirty && used, hotel.currentDay);
        // F1：房卡纪念品——退房后概率留在房内（玩家拾取）。
        if (order.settled && stay != null && context.level().random.nextFloat() < HotelConfig.ROOM_CARD_CHANCE.get()) {
            BlockPos spot = BlockPos.of(stay.approach);
            net.minecraft.world.item.ItemStack card = new net.minecraft.world.item.ItemStack(HotelBlocks.ROOM_CARD.get());
            card.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal((context.shop().name == null ? "小旅馆" : context.shop().name) + " · 房卡"));
            net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(
                    context.level(), spot.getX() + 0.5, spot.getY() + 0.3, spot.getZ() + 0.5, card);
            context.level().addFreshEntity(drop);
        }
        if (abandoned && !order.settled) {
            order.cancelled = true;
        }
        if (order.settled) {
            context.state().completed++;
        }
        context.state().orders.remove(order);
        if (guest != null) {
            HotelApi.depart(context.level(), guest);
        } else {
            HotelApi.departByOrder(context.level(), order);
        }
        if (stay != null) {
            HotelApi.event(WssEvents.Type.CUSTOMER_LEFT, context.server(), context.shop(), order,
                    order.settled ? order.price : 0L, order.cancelled ? 0.0 : 1.0);
        }
        RestaurantData.get(context.server()).setDirty();
    }

    private boolean stayUsed(HotelState hotel, RestaurantData.Order order) {
        HotelState.Stay stay = hotel.stays.get(order.id.toString());
        return stay != null && (stay.quarterNights > 0 || stay.breakfastOrdered);
    }

    private void cancel(ShopWorkContext context, HotelState hotel, RestaurantData.Order order, EmployeeEntity guest, String reason) {
        if (guest != null) {
            ShopStaff.status(guest, reason);
            guest.express(NpcEmotion.SWEAT);
        }
        order.cancelled = true;
        depart(context, hotel, order, guest, true, true);
    }

    private void timeout(ShopWorkContext context, HotelState hotel, RestaurantData.Order order, EmployeeEntity guest, int limit) {
        if (order.age > limit) {
            cancel(context, hotel, order, guest, "等候太久，改去别家");
        }
    }

    // —— 客房保洁（P0-D：任务释放；head 索引查询） ——

    private void cleaning(ShopWorkContext context, HotelState hotel, HotelRooms rooms) {
        ServerLevel level = context.level();
        if (!context.state().open) {
            for (HotelState.CleanJob job : new ArrayList<>(hotel.cleans.values())) {
                releaseClean(hotel, job);
            }
            return;
        }

        for (HotelState.CleanJob job : new ArrayList<>(hotel.cleans.values())) {
            HotelRooms.Room room = rooms.byHead(job.head);
            EmployeeEntity maid = entityOf(level, job.worker);
            if (room == null || maid == null || maid.isRemoved() || !onDuty(context, job.worker)) {
                releaseClean(hotel, job);
                continue;
            }
            BlockPos approach = BlockPos.of(job.approach);
            if (!near(maid, approach)) {
                ShopStaff.status(maid, "前往待清扫客房");
                continue;
            }
            maid.activity(EmployeeEntity.Activity.CLEAN);
            ShopStaff.status(maid, "打扫客房");
            job.progress += 5 * maid.workRate(1);
            if (job.progress >= HotelConfig.CLEAN_TICKS.get()) {
                String key = Long.toString(job.head);
                hotel.dirty.remove(key);
                hotel.dirtySince.remove(key);
                hotel.totalCleans++;
                hotel.bump();
                maid.activity(EmployeeEntity.Activity.IDLE);
                releaseClean(hotel, job);
            }
        }

        for (Map.Entry<String, Boolean> entry : new ArrayList<>(hotel.dirty.entrySet())) {
            if (!Boolean.TRUE.equals(entry.getValue()) || hotel.cleaningAt(Long.parseLong(entry.getKey()))) {
                continue;
            }
            HotelRooms.Room room = rooms.byHead(Long.parseLong(entry.getKey()));
            if (room == null) {
                hotel.dirty.remove(entry.getKey());
                hotel.dirtySince.remove(entry.getKey());
                continue;
            }
            EmployeeEntity maid = freeMaid(context, hotel);
            if (maid == null) {
                break;
            }
            HotelState.CleanJob job = new HotelState.CleanJob();
            job.id = UUID.randomUUID().toString();
            job.worker = maid.getUUID().toString();
            job.head = room.head().asLong();
            job.approach = room.approach().asLong();
            hotel.cleans.put(job.worker, job);
        }
    }

    private void releaseClean(HotelState hotel, HotelState.CleanJob job) {
        hotel.cleans.remove(job.worker);
    }

    private EmployeeEntity freeMaid(ShopWorkContext context, HotelState hotel) {
        java.util.List<EmploymentData.Worker> onDuty = ShopStaff.onDuty(context, WssHotelMod.MAID);
        for (EmploymentData.Worker worker : onDuty) {
            EmployeeEntity maid = entityOf(context.level(), worker.profile().id());
            if (maid != null && !hotel.cleans.containsKey(maid.getUUID().toString())) {
                return maid;
            }
        }
        // 诊断：有脏房却派不出保洁时，每 500 个周期打一次原因。
        // 在班为 0 = 排班/班组未轮到；在班>0 但在店=0 = 实体未生成或不在店内。
        if (!hotel.dirty.isEmpty() && context.state().open && cycle % 500 == 0) {
            HotelApi.LOG.warn("[{}] 保洁派单失败：脏房={} 在班保洁={} 进行中保洁={}",
                    context.shop().id, hotel.dirty.size(), onDuty.size(), hotel.cleans.size());
        }
        return null;
    }

    private boolean onDuty(ShopWorkContext context, String workerId) {
        for (EmploymentData.Worker worker : context.workers()) {
            if (worker.profile().id().toString().equals(workerId)) {
                return true;
            }
        }
        return false;
    }

    // —— P0-C：空闲员工回待命位（前台→收银工位，保洁→首个客房门口） ——

    private void standby(ShopWorkContext context, HotelState hotel, HotelRooms rooms) {
        if (cycle % 200 != 0) {
            return;
        }
        ShopFacilities.Station register = registerOf(context);
        BlockPos desk = register != null ? register.worker() : null;
        BlockPos maidSpot = rooms.rooms().isEmpty() ? null : rooms.rooms().get(0).approach();
        for (EmploymentData.Worker worker : context.workers()) {
            EmployeeEntity npc = entityOf(context.level(), worker.profile().id());
            if (npc == null || npc.getPose() == Pose.SLEEPING || hotel.cleans.containsKey(worker.profile().id().toString())) {
                continue;
            }
            BlockPos anchor = null;
            if (worker.job() == ShopJob.CASHIER && desk != null) {
                anchor = desk;
            } else if (worker.job() == WssHotelMod.MAID && maidSpot != null) {
                anchor = maidSpot;
            }
            if (anchor != null && npc.blockPosition().distSqr(anchor) > 36 && npc.getNavigation().isDone()) {
                npc.getNavigation().moveTo(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5, 0.8);
            }
        }
    }

    // —— 小工具 ——

    private ShopFacilities.Station registerOf(ShopWorkContext context) {
        return context.facilities().registers().isEmpty() ? null : context.facilities().registers().get(0);
    }

    private BlockPos exitOf(ShopWorkContext context) {
        int[] bounds = context.shop().bounds;
        if (bounds == null || bounds.length < 6) {
            return null;
        }
        return new BlockPos((bounds[0] + bounds[3]) / 2, bounds[1], bounds[2] - 1);
    }

    private EmployeeEntity workerNear(ShopWorkContext context, ShopJob job, BlockPos near, double radius) {
        EmployeeEntity best = null;
        double bestSqr = radius * radius;
        for (EmploymentData.Worker worker : ShopStaff.onDuty(context, job)) {
            EmployeeEntity npc = entityOf(context.level(), worker.profile().id());
            if (npc == null) {
                continue;
            }
            double sqr = npc.blockPosition().distSqr(near);
            if (sqr <= bestSqr) {
                best = npc;
                bestSqr = sqr;
            }
        }
        return best;
    }

    private EmployeeEntity receptionistNear(ShopWorkContext context, ShopFacilities.Station register) {
        return workerNear(context, ShopJob.CASHIER, register.block(), 32);
    }

    private void walkWorker(EmployeeEntity worker, BlockPos target) {
        if (worker == null || target == null || near(worker, target)) {
            return;
        }
        if (worker.getNavigation().isDone()) {
            worker.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
        }
    }

    private EmployeeEntity entityOf(ServerLevel level, String id) {
        try {
            return entityOf(level, UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            HotelApi.LOG.warn("无法解析员工 UUID: {}", id);
            return null;
        }
    }

    private EmployeeEntity entityOf(ServerLevel level, UUID id) {
        Entity entity = level.getEntity(id);
        return entity instanceof EmployeeEntity npc ? npc : null;
    }

    /**
     * 任务化导航（与本体店型一致）：走 RestaurantService.go + OrderNavigation.Task，
     * 导航旅程与订单绑定（状态字/到达判定/卡路恢复上下文齐全）。返回是否已到达目标附近。
     * 重试节流沿用 order.serviceRetryAt（40 刻）。
     */
    private boolean walk(EmployeeEntity npc, BlockPos target, String status, RestaurantData.Order order) {
        if (npc.blockPosition().distSqr(target) <= ARRIVE_SQR) {
            return true;
        }
        long now = npc.level().getGameTime();
        if (now >= order.serviceRetryAt) {
            order.serviceRetryAt = now + 40;
            RestaurantServiceAccess.invokeGo(npc, target, target, status, 1.0, HotelNav.orderTask(order));
        }
        return false;
    }

    private boolean near(EmployeeEntity npc, BlockPos target) {
        if (npc.blockPosition().distSqr(target) <= ARRIVE_SQR) {
            return true;
        }
        if (npc.getNavigation().isDone()) {
            npc.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
        }
        return false;
    }

    private void updatePeak(HotelState hotel, int roomCount) {
        if (roomCount <= 0) {
            return;
        }
        double occupancy = Math.min(1.0, hotel.stays.size() / (double) roomCount);
        if (occupancy > hotel.peakOccupancy) {
            hotel.peakOccupancy = occupancy;
        }
    }

    // —— 恢复与展示 ——

    @Override
    public void retryCustomer(ShopWorkContext context, RestaurantData.Order order, EmployeeEntity guest) {
        HotelState hotel = WssHotelMod.attachment(context.shop());
        HotelState.Stay stay = hotel.stays.get(order.id.toString());
        if (stay != null && stay.lying && guest != null) {
            endSit(guest, stay);
        }
        if (guest != null) {
            guest.getNavigation().stop();
        }
    }

    @Override
    public void abandonCustomer(ShopWorkContext context, RestaurantData.Order order, EmployeeEntity guest) {
        depart(context, WssHotelMod.attachment(context.shop()), order, guest, true, true);
    }

    @Override
    public String status(RestaurantData.Restaurant state, List<RestaurantRecipes.Plan> menu, String reason) {
        return reason == null || reason.isBlank() ? "客房经营中" : reason;
    }

    @Override
    public String fullMessage() {
        return "客房已满，房客请在门外稍候。";
    }
}
