package wsshotel.mixin;

import net.minecraft.server.level.ServerLevel;
import org.merlin204.wss.shop.EmploymentData;
import org.merlin204.wss.shop.EmployeeEntity;
import org.merlin204.wss.shop.RestaurantData;
import org.merlin204.wss.shop.ShopData;
import org.merlin204.wss.shop.ShopFacilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露本体员工生命周期驱动（包级私有）。
 * 本体各店型的业务循环都逐员工调用它完成 生成/召回/排班上下岗/离店存档；
 * 附属调用后与核心店型的员工管理完全一致（含 recall 的 away 标记清理、NBT 恢复）。
 */
@Mixin(targets = "org.merlin204.wss.shop.ShopStaffLifecycle")
public interface ShopStaffLifecycleAccess {

    @Invoker("update")
    static EmployeeEntity invokeUpdate(ServerLevel level, ShopData.Shop shop, RestaurantData.Restaurant state,
                                       EmploymentData.Worker worker, ShopFacilities facilities, boolean available) {
        throw new AssertionError();
    }
}
