package wsshotel.hotel;

import org.merlin204.wss.api.AddonPlan;
import org.merlin204.wss.api.RegisterWssContentEvent;
import org.merlin204.wss.api.ShopContext;
import org.merlin204.wss.api.WssRegistries;
import org.merlin204.wss.shop.BusinessPlanCatalog;
import org.merlin204.wss.shop.BusinessPlanSelection;
import org.merlin204.wss.shop.ShopCapability;
import wsshotel.HotelApi;
import wsshotel.WssHotelMod;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * 小旅馆经营计划：
 * 旅行团包房（DAYS：5 天客流与房费提升，客流由 {@link HotelCrowdPolicy} 读取激活状态）；
 * 深度大扫除（IMMEDIATE：立即清空全部待清洁客房）。
 */
public final class HotelPlans {

    /** 旅行团包房计划数字 ID（激活查询用）。 */
    public static volatile int GROUP_BOOKING_ID = Integer.MIN_VALUE;
    /** 深度大扫除计划数字 ID。 */
    public static volatile int DEEP_CLEAN_ID = Integer.MIN_VALUE;

    private static final BusinessPlanSelection.Schema NO_CHOICE =
            new BusinessPlanSelection.Schema(0, 0, 0, 0, 0, 0, 0, false, 0, 0, "");

    private HotelPlans() {
    }

    /** 仅小旅馆可获取（能力集合无法区分店型，用条件收口）。 */
    private static boolean hotelOnly(ShopContext context) {
        return context.shop().kind == HotelShopType.kind();
    }

    public static void register(RegisterWssContentEvent event) {
        var groupId = WssHotelMod.id("plan_group_booking");
        GROUP_BOOKING_ID = WssRegistries.planId(groupId);
        var cleanId = WssHotelMod.id("plan_deep_clean");
        DEEP_CLEAN_ID = WssRegistries.planId(cleanId);

        BusinessPlanCatalog.Plan groupBooking = new BusinessPlanCatalog.Plan(
                GROUP_BOOKING_ID,
                "旅行团包房",
                "旅行社看上了你的客房。",
                300, 2, "5天", "至少 3 间客房",
                "5 天内客流与房费提升",
                "5 天内游客成批到来，入住率与房费明显上升。",
                BusinessPlanCatalog.Mode.DAYS, 5,
                BusinessPlanCatalog.Acquisition.REPEAT, 3,
                Set.of(ShopCapability.CUSTOMER_PAYMENTS));
        event.plan(groupId, new AddonPlan(
                groupBooking, NO_CHOICE,
                context -> hotelOnly(context) && WssHotelMod.attachment(context.shop()).roomCache.size() >= 3,
                (context, choices) -> "",
                noOp(), noOp()));

        BusinessPlanCatalog.Plan deepClean = new BusinessPlanCatalog.Plan(
                DEEP_CLEAN_ID,
                "深度大扫除",
                "保洁队上门，一次到底。",
                200, 1, "立即", "存在待清洁客房",
                "立即清空全部待清洁客房",
                "请保洁队上门，所有待清洁客房立即恢复可入住。",
                BusinessPlanCatalog.Mode.IMMEDIATE, 0,
                BusinessPlanCatalog.Acquisition.REPEAT, 0,
                Set.of(ShopCapability.CUSTOMER_PAYMENTS));
        event.plan(cleanId, new AddonPlan(
                deepClean, NO_CHOICE,
                context -> hotelOnly(context) && WssHotelMod.attachment(context.shop()).dirty.containsValue(Boolean.TRUE),
                (context, choices) -> "",
                (context, run) -> {
                    HotelState hotel = WssHotelMod.attachment(context.shop());
                    int cleaned = 0;
                    for (String head : new ArrayList<>(hotel.dirty.keySet())) {
                        if (Boolean.TRUE.equals(hotel.dirty.get(head))) {
                            hotel.dirty.remove(head);
                            hotel.dirtySince.remove(head);
                            cleaned++;
                        }
                    }
                    hotel.totalCleans += cleaned;
                    hotel.bump();
                    HotelApi.LOG.info("深度大扫除完成：清理 {} 间客房（{}）", cleaned, context.shop().id);
                },
                noOp()));
    }

    private static BiConsumer<org.merlin204.wss.api.ShopContext, org.merlin204.wss.shop.BusinessPlanState.Run> noOp() {
        return (context, run) -> {
        };
    }
}
