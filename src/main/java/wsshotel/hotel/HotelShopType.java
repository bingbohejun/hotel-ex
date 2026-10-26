package wsshotel.hotel;

import org.merlin204.wss.shop.ShopBusiness;
import org.merlin204.wss.shop.ShopCapability;
import org.merlin204.wss.shop.ShopCrowdPolicy;
import org.merlin204.wss.shop.ShopFeatures;
import org.merlin204.wss.shop.ShopJob;
import org.merlin204.wss.shop.ShopKind;
import org.merlin204.wss.shop.ShopPresentation;
import org.merlin204.wss.shop.ShopResearch;
import org.merlin204.wss.shop.ShopStaffing;
import org.merlin204.wss.shop.ShopType;
import org.merlin204.wss.shop.ShopWorkPolicy;
import wsshotel.WssHotelMod;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 组装小旅馆的 ShopType 十策略。 */
public final class HotelShopType {

    private static volatile ShopKind kind;

    /** 注册后由 {@link WssHotelMod} 写入规范实例。 */
    public static void kind(ShopKind value) {
        kind = value;
    }

    public static ShopKind kind() {
        ShopKind value = kind;
        if (value == null) {
            throw new IllegalStateException("Hotel shop kind not registered yet");
        }
        return value;
    }

    /** 附属店型配置回调：以基础家族店型为底，替换全部策略。 */
    public static ShopType definition(ShopType base) {
        ShopKind kind = base.kind();
        ShopFeatures features = new ShopFeatures(
                // RETAIL_SHELVES：货物架零售复用本体便利店货架（WorkstationHealth.SHELF 校验需要）。
                // QUALIFICATIONS：解锁“交涉”页（本体资质谈判，供货渠道按玩家通用），旅馆与便利店同款备货链路。
                Set.of(ShopCapability.CUSTOMER_PAYMENTS, ShopCapability.RETAIL_SHELVES, ShopCapability.QUALIFICATIONS),
                List.of(ShopJob.CASHIER, WssHotelMod.MAID, ShopJob.WAITER, ShopJob.STOCKER, ShopJob.MANAGER),
                Map.of(
                        ShopJob.CASHIER, "前台接待",
                        WssHotelMod.MAID, "客房保洁",
                        ShopJob.WAITER, "服务员",
                        ShopJob.STOCKER, "补货员",
                        ShopJob.MANAGER, "店长"),
                ShopFeatures.Research.NEGOTIATION,
                // Stock.SHELVES：店铺配置显示本体同款“备货”页签（RetailStockPanel，数据 r.retail）。
                ShopFeatures.Stock.SHELVES,
                "acquire-" + kind.name(),
                "把这里改成一家温馨的小旅馆？床位、前台都会帮你搭好。",
                wording -> wording.replace("顾客", "房客").replace("结账", "退房结算"),
                new ShopPresentation("overview", "房客", "房费收入", false));

        ShopStaffing staffing = new ShopStaffing(
                Map.of(
                        ShopJob.CASHIER, new ShopStaffing.Role(
                                facilities -> facilities.registers().size(),
                                "没有前台的话，房客无法办理入住和退房。",
                                "把收银电脑放在一格高的台面上，前后留出站位，再安排一名前台接待。"),
                        WssHotelMod.MAID, new ShopStaffing.Role(
                                facilities -> Math.max(1, (facilities.extension(HotelRooms.class, HotelCrowdPolicy.EMPTY).rooms().size() + 2) / 3),
                                "没有客房保洁的话，退房后的房间会一直没人打扫。",
                                "房客退房后房间需要打扫才能再次入住，建议每 3 间客房安排 1 名保洁。"),
                        ShopJob.WAITER, new ShopStaffing.Role(
                                facilities -> facilities.extension(HotelShelves.class, HotelShelves.EMPTY).shelves().isEmpty() ? 0 : Math.max(1, facilities.extension(HotelShelves.class, HotelShelves.EMPTY).shelves().size() / 2),
                                "没有服务员的话，房客点的货物没人送货。",
                                "摆放货物架后，安排服务员为点单的房客送货。"),
                        ShopJob.STOCKER, new ShopStaffing.Role(
                                facilities -> facilities.extension(HotelShelves.class, HotelShelves.EMPTY).shelves().isEmpty() ? 0 : Math.max(1, facilities.extension(HotelShelves.class, HotelShelves.EMPTY).shelves().size() / 2),
                                "没有补货员的话，货架卖空了不会自动补满。",
                                "摆放货物架后，建议每 3 个货架安排 1 名补货员，负责把货架补满。"),
                        ShopJob.MANAGER, new ShopStaffing.Role(
                                facilities -> 1,
                                "店长能提升整体经营效率。",
                                "店长不是必需岗位，生意忙起来再考虑。")),
                List.of(ShopJob.CASHIER, WssHotelMod.MAID),
                List.of(
                        new ShopStaffing.Help("前台接待", "站在收银电脑后方，为房客办理入住登记与退房结算。"),
                        new ShopStaffing.Help("客房保洁", "房客退房后前往客房打扫床位，房间才能再次入住。"),
                        new ShopStaffing.Help("服务员", "把房客点的货物从货架取来送到房间。"),
                        new ShopStaffing.Help("补货员", "把配置好的商品补满货架，让零售货源充足。")));
        // 必需岗位只有前台接待与客房保洁：服务员缺员只影响货物架零售（无人上架/送货），
        // 不再卡住接待门槛（此前摆货架未雇服务员会导致 ShopStaffLife.ready=false，房客一律不来）。

        return new ShopType(
                kind,
                features,
                HotelBusiness.INSTANCE,
                HotelProducts.INSTANCE,
                HotelSetup.INSTANCE,
                HotelContent.INSTANCE,
                staffing,
                ShopResearch.NONE,
                HotelCrowdPolicy.create(),
                HotelWorkPolicy.INSTANCE);
    }
}
