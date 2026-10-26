package wsshotel.hotel;

import org.merlin204.wss.shop.CustomerLoyalty;
import org.merlin204.wss.shop.RestaurantData;
import org.merlin204.wss.shop.ShopCrowdPolicy;
import org.merlin204.wss.shop.ShopData;
import org.merlin204.wss.shop.ShopVisitTiming;
import wsshotel.HotelApi;
import wsshotel.HotelConfig;
import wsshotel.WssHotelMod;

import java.util.List;

/**
 * 小旅馆客流策略：单通道"stay"（QUEUE 限流）。
 * 服务容量 = 当前干净空房数；门口候位固定 2 个。
 * 客流权重 = 定价弹性 × 旺日 × 口碑 × 旅游脉冲 × 旅行团计划。
 */
public final class HotelCrowdPolicy {

    public static final HotelRooms EMPTY = new HotelRooms(List.of(), List.of());

    public static ShopCrowdPolicy create() {
        return new ShopCrowdPolicy(
                List.of(new ShopCrowdPolicy.Channel(
                        "stay",
                        ShopCrowdPolicy.Limit.QUEUE,
                        food -> true,
                        order -> order.stage != null,
                        order -> order.stage == RestaurantData.Stage.QUEUE,
                        context -> freeRooms(context),
                        context -> 2L
                )),
                (restaurant, actor) -> "",
                HotelCrowdPolicy::demandWeight,
                channel -> 1.0,
                // 住宿业态全天候客流（MEALS 按用餐窗口调度，夜里窗口为 0 会整天没客）。
                ShopVisitTiming.SHOPPING
        );
    }

    /** 当前可立即入住的干净空房数。 */
    public static long freeRooms(ShopCrowdPolicy.Context context) {
        if (context.facilities() == null) {
            return 0L;
        }
        HotelRooms rooms = context.facilities().extension(HotelRooms.class, EMPTY);
        HotelState state = WssHotelMod.attachment(context.shop());
        return rooms.rooms().stream().filter(room -> state.roomFree(room.head().asLong())).count();
    }

    /**
     * 到店意愿权重（ordinaryArrivals 回调）。
     * 只做查表与算术：定价弹性、比赛日、口碑、旅游脉冲、旅行团计划，无世界访问。
     */
    public static double demandWeight(RestaurantData.Restaurant state) {
        ShopData.Shop shop = HotelWorkPolicy.HotelShops.shop(state);
        if (shop == null) {
            return 1.0;
        }
        HotelState hotel = WssHotelMod.attachment(shop);

        // 定价弹性：三档均价相对弹性基准越高，到店意愿越低（基准随物价体系配置）。
        double avg = (hotel.standardPrice + hotel.comfortPrice + hotel.deluxePrice) / 3.0;
        double ratio = avg / Math.max(1.0, HotelConfig.ELASTICITY_BASE.get());
        double weight = Math.clamp(1.45 - 0.45 * ratio, 0.2, 1.5);

        // F7：节气淡旺季客流倍率。
        weight *= HotelSeasons.traffic(hotel.currentTerm);

        // 旺日（本体比赛日）。
        if (hotel.currentDay != Long.MIN_VALUE && HotelApi.contestDay(hotel.currentDay)) {
            weight *= HotelConfig.CONTEST_TRAFFIC.get();
        }

        // 旅游返程脉冲（次日有效）。
        if (hotel.journeyPulseDay == hotel.currentDay && hotel.journeyPulseGuests > 0) {
            weight *= 1.0 + hotel.journeyPulseGuests * 0.1;
        }

        // 口碑：粉丝市民类型越多，客流越高（本体公式，每类 +5%，上限 3 倍）。
        int fans = 0;
        for (var history : state.customerLoyalty.values()) {
            if (history.fan()) {
                fans++;
            }
        }
        weight *= CustomerLoyalty.wordOfMouth(fans);

        // 旅行团包房计划激活中。
        if (HotelPlans.GROUP_BOOKING_ID != Integer.MIN_VALUE && state.plans.active(HotelPlans.GROUP_BOOKING_ID)) {
            weight *= 1.4;
        }
        return weight;
    }

    private HotelCrowdPolicy() {
    }
}
