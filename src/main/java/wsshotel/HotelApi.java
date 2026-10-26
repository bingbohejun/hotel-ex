package wsshotel;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.merlin204.wss.api.WssEvents;
import org.merlin204.wss.shop.CityResidents;
import org.merlin204.wss.shop.ContestRules;
import org.merlin204.wss.shop.DayEndData;
import org.merlin204.wss.shop.EmployeeEntity;
import org.merlin204.wss.shop.RestaurantData;
import org.merlin204.wss.shop.RestaurantRecipes;
import org.merlin204.wss.shop.ShopData;
import org.merlin204.wss.shop.ShopEnvironment;
import org.merlin204.wss.shop.SpecialCustomers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本体 API 适配层：集中包装跨版本最易变动的调用点，
 * 本体升级时只需修改本类。仅服务器线程使用。
 */
public final class HotelApi {

    public static final Logger LOG = LoggerFactory.getLogger("wss_hotel");

    private HotelApi() {
    }

    /** 住宿订单的合成计划（8 参构造是本体升级的首要脆弱点）。 */
    public static RestaurantRecipes.Plan stayPlan() {
        return new RestaurantRecipes.Plan(
                "wss_hotel:stay", "wss_hotel:room_stay", 1, 0,
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.List.of());
    }

    /** 识别特殊顾客皮肤；普通市民返回 null。 */
    public static SpecialCustomers.Character specialSkin(String customSkin) {
        try {
            return SpecialCustomers.skin(customSkin);
        } catch (RuntimeException e) {
            LOG.warn("识别特殊顾客失败: {}", customSkin, e);
            return null;
        }
    }

    public static CityResidents.Actor actor(MinecraftServer server, UUID id) {
        return CityResidents.get(server).actor(id);
    }

    /** 归还市民实体给城市人流。 */
    public static void depart(ServerLevel level, EmployeeEntity npc) {
        if (npc != null && !npc.isRemoved()) {
            CityResidents.get(level.getServer()).depart(level, npc);
        }
    }

    /** 按订单结束（订单已从列表移除后的兜底归还）。 */
    public static void departByOrder(ServerLevel level, RestaurantData.Order order) {
        if (level.getEntity(order.customer) instanceof EmployeeEntity npc) {
            depart(level, npc);
        }
    }

    /** 本体业务日（游戏日）。 */
    public static long businessDay(MinecraftServer server) {
        return DayEndData.businessDay(server);
    }

    /** 本体比赛日（每 6 天一次的旺日）。 */
    public static boolean contestDay(long day) {
        return ContestRules.contestDay(day);
    }

    /** 店铺环境分快照（本体内部有缓存，调用廉价）。 */
    public static ShopEnvironment.Scores environment(MinecraftServer server, ShopData.Shop shop) {
        try {
            return ShopEnvironment.snapshot(server, shop);
        } catch (RuntimeException e) {
            LOG.warn("读取环境分失败: {}", shop.id, e);
            return null;
        }
    }

    /** 订单事件广播。 */
    public static void event(WssEvents.Type type, MinecraftServer server, ShopData.Shop shop,
                             RestaurantData.Order order, long amount, double value) {
        WssEvents.order(type, server, shop, order, amount, value);
    }
}
