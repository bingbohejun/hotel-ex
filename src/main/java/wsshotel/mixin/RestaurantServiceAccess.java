package wsshotel.mixin;

import net.minecraft.core.BlockPos;
import org.merlin204.wss.shop.EmployeeEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露本体导航统一入口 go（本体各店型的顾客/员工移动都走它）。
 * task 参数为 Object（实为 OrderNavigation.Task，由 HotelNav.orderTask 反射创建）。
 * 注意：接口型 Mixin 只能包含访问器/调用器存根（无实现体），实现逻辑放附属自己的类。
 */
@Mixin(targets = "org.merlin204.wss.shop.RestaurantService")
public interface RestaurantServiceAccess {

    @Invoker("go")
    static boolean invokeGo(EmployeeEntity e, BlockPos target, BlockPos look, String status, double speed, Object task) {
        throw new AssertionError();
    }
}
