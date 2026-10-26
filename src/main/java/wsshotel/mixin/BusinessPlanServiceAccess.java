package wsshotel.mixin;

import net.minecraft.server.level.ServerLevel;
import org.merlin204.wss.shop.RestaurantData;
import org.merlin204.wss.shop.ShopData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露本体接待后的经营计划登记（包级私有）：
 * 会员/粉丝进度推进、排队计划标记等。本体各店型在 admit 成功后都会调用。
 */
@Mixin(targets = "org.merlin204.wss.shop.BusinessPlanService")
public interface BusinessPlanServiceAccess {

    @Invoker("admitted")
    static void invokeAdmitted(ServerLevel level, ShopData.Shop shop, RestaurantData.Restaurant state,
                               RestaurantData.Order order) {
        throw new AssertionError();
    }
}
