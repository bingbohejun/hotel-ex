package wsshotel.mixin;

import net.minecraft.server.level.ServerLevel;
import org.merlin204.wss.shop.ShopData;
import org.merlin204.wss.shop.ShopJob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import wsshotel.hotel.HotelShopType;

import java.util.HashSet;
import java.util.Set;

/**
 * 附属“服务员”（WAITER）兼任本体“补货员”（STOCKER）职责：
 * 本体工作方块检查（WorkstationHealth）要求货架空层时有补货员在编，
 * 旅馆店型下把在编服务员计入补货员——玩家只雇一个岗位即可覆盖货架零售。
 */
@Mixin(targets = "org.merlin204.wss.shop.WorkstationHealth", remap = false)
public abstract class WorkstationHealthMixin {

    @Inject(method = "staff", at = @At("TAIL"), cancellable = true, require = 0)
    private static void wsshotel$waiterCoversStocker(ServerLevel level, ShopData.Shop shop,
                                                     CallbackInfoReturnable<Set<ShopJob>> cir) {
        Set<ShopJob> jobs = cir.getReturnValue();
        if (shop.kind == HotelShopType.kind() && jobs.contains(ShopJob.WAITER) && !jobs.contains(ShopJob.STOCKER)) {
            Set<ShopJob> merged = new HashSet<>(jobs);
            merged.add(ShopJob.STOCKER);
            cir.setReturnValue(merged);
        }
    }
}
