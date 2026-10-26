package wsshotel.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.merlin204.wss.shop.ShopData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import wsshotel.hotel.HotelShopType;

/**
 * 小旅馆货物架零售：放行本体便利店零售的"便利店门禁"。
 * 数据层（r.retail）与配置链路（RetailShelving.configure 的 revision/资质校验）原样复用，
 * 仅把 is(CONVENIENCE) 判定对小旅馆放行——资质按 owner 通用，货架售货由 HotelBusiness.retail 自驱动。
 */
@Mixin(targets = "org.merlin204.wss.shop.RetailPackets", remap = false)
public abstract class RetailPacketsMixin {

    @ModifyExpressionValue(
            method = "send",
            at = @At(value = "INVOKE", target = "Lorg/merlin204/wss/shop/ShopKind;is(Lorg/merlin204/wss/shop/ShopKind;)Z", remap = false)
    )
    private static boolean wsshotel$sendAllowHotel(boolean original, ServerPlayer player, ShopData.Shop shop, String message) {
        return original || shop.kind == HotelShopType.kind();
    }

    @ModifyExpressionValue(
            method = "handle",
            at = @At(value = "INVOKE", target = "Lorg/merlin204/wss/shop/ShopKind;is(Lorg/merlin204/wss/shop/ShopKind;)Z", remap = false)
    )
    private static boolean wsshotel$handleAllowHotel(boolean original, ServerPlayer player, ShopData.Shop shop, String json) {
        return original || shop.kind == HotelShopType.kind();
    }
}
