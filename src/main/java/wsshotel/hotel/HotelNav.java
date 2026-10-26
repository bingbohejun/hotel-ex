package wsshotel.hotel;

import java.lang.reflect.Method;
import net.minecraft.world.entity.Entity;
import org.merlin204.wss.shop.RestaurantData;

/**
 * 任务化导航的工具桥：创建与订单绑定的本体导航任务（OrderNavigation.Task，包私有）。
 * 桥经反射调用本体 OrderNavigation.task(order)——NeoForge 的 mod 模块包默认对彼此开放，
 * setAccessible 可用；失败时返回 null，导航退化为无任务移动（不影响寻路，只少恢复上下文）。
 */
public final class HotelNav {

    private static volatile Method taskMethod;

    /** 创建订单导航任务；task 参数在本体 go 中声明为 Object，此处以 Object 返回。 */
    public static Object orderTask(RestaurantData.Order order) {
        if (order == null) {
            return null;
        }
        try {
            Method m = taskMethod;
            if (m == null) {
                m = Class.forName("org.merlin204.wss.shop.OrderNavigation")
                        .getDeclaredMethod("task", RestaurantData.Order.class);
                m.setAccessible(true);
                taskMethod = m;
            }
            return m.invoke(null, order);
        } catch (Throwable t) {
            return null;
        }
    }

    private HotelNav() {
    }
}
