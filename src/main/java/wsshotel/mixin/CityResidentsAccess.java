package wsshotel.mixin;

import java.util.function.BiConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露本体 CityResidents 的到店打点观察器（包级私有静态字段）。
 * 附属用它接收顾客到店漏斗的每一步结果（candidate_* / spawned_* / order_*），
 * 与本体自家的调试能力对齐。
 */
@Mixin(targets = "org.merlin204.wss.shop.CityResidents")
public interface CityResidentsAccess {

    @Accessor("arrivalObserver")
    static void wssHotel$setArrivalObserver(BiConsumer<String, String> observer) {
        throw new AssertionError();
    }
}
