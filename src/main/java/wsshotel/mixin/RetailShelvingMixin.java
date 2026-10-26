package wsshotel.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.merlin204.wss.shop.ShopData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import wsshotel.hotel.HotelShopType;

/** 小旅馆货物架零售：放行货架配置的"便利店门禁"（revision 乐观锁与资质校验保持本体原样）。 */
@Mixin(targets = "org.merlin204.wss.shop.RetailShelving", remap = false)
public abstract class RetailShelvingMixin {

    @ModifyExpressionValue(
            method = "configure",
            at = @At(value = "INVOKE", target = "Lorg/merlin204/wss/shop/ShopKind;is(Lorg/merlin204/wss/shop/ShopKind;)Z", remap = false)
    )
    private static boolean wsshotel$configureAllowHotel(boolean original, ServerPlayer player, ShopData.Shop shop, long revision, long shelf, int layer, String item) {
        return original || shop.kind == HotelShopType.kind();
    }
}
