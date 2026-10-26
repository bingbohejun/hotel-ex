package wsshotel.mixin;

import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.merlin204.wss.shop.EmploymentData;
import org.merlin204.wss.shop.RestaurantData;
import org.merlin204.wss.shop.ShopData;
import org.merlin204.wss.shop.ShopFacilities;
import org.merlin204.wss.shop.ShopJob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露本体员工在岗判定的包级私有静态方法：
 * available = 排班在班且无经营计划占用（对应烘焙坊循环里的 unavailable 判定）；
 * ready = 本体接待门槛"必要岗位是否有人到店"（日结遥测里"部分必要岗位尚无人到店"的判定本体）。
 */
@Mixin(targets = "org.merlin204.wss.shop.ShopStaffLife")
public interface ShopStaffLifeAccess {

    @Invoker("available")
    static boolean invokeAvailable(RestaurantData.Restaurant state, UUID employee, ShopJob job) {
        throw new AssertionError();
    }

    @Invoker("ready")
    static boolean invokeReady(ServerLevel level, ShopData.Shop shop, RestaurantData.Restaurant state,
                               List<EmploymentData.Worker> workers, ShopFacilities facilities) {
        throw new AssertionError();
    }
}
