package wsshotel.mixin;

import net.minecraft.server.MinecraftServer;
import org.merlin204.wss.shop.ShopData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 本体（wonder_shop_story_core 1.6.7）特殊顾客奖励的分支是写死的 switch，末尾 default 直接
 * {@code throw new IllegalArgumentException("Unimplemented special reward: " + id)}。
 * 附属通过 RegisterWssContentEvent#customer 注册的顾客一旦评分 ≥60 进入 review，就会命中 default，
 * 异常沿 CityResidents.review → RetailService.tick 冒到 MinecraftServer.tickServer，整个服务端崩溃
 * （日志：Unimplemented special reward: wss_hotel:salesman_lao_zhou）。
 *
 * <p>这里在 reward 入口拦掉附属命名空间的顾客：本体不认它们，奖励由附属自己的
 * WssEvents.Type.SPECIAL_REVIEWED 监听器（房客答谢）负责，跳过本体即可，不影响本体顾客的任何逻辑。</p>
 */
@Mixin(targets = "org.merlin204.wss.shop.SpecialCustomerStories", remap = false)
public abstract class SpecialCustomerRewardMixin {

    @Inject(method = "reward", at = @At("HEAD"), cancellable = true)
    private static void wsshotel$skipAddonReward(MinecraftServer server, ShopData.Shop shop, String id,
                                                 CallbackInfo ci) {
        if (id != null && id.startsWith(wsshotel.WssHotelMod.MOD_ID + ":")) {
            ci.cancel();
        }
    }
}
