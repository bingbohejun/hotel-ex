package wsshotel.hotel;

import org.merlin204.wss.shop.EmployeeWork;
import org.merlin204.wss.shop.RestaurantData;
import org.merlin204.wss.shop.ShopData;
import org.merlin204.wss.shop.ShopWorkPolicy;
import wsshotel.WssHotelMod;

import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 小旅馆工作策略：上报客房保洁任务，供共享的员工恢复体系使用。
 * busy/collect 只拿到 Restaurant 运行态，店铺引用由业务循环经
 * {@link HotelShops} 登记（WeakHashMap，随状态实例回收）。
 */
public final class HotelWorkPolicy implements ShopWorkPolicy {

    public static final HotelWorkPolicy INSTANCE = new HotelWorkPolicy();

    private HotelWorkPolicy() {
    }

    @Override
    public boolean busy(RestaurantData.Restaurant state, UUID employee) {
        HotelState hotel = HotelShops.state(state);
        if (hotel == null) {
            return false;
        }
        if (hotel.cleans.containsKey(employee.toString())) {
            return true;
        }
        // F6：上门服务任务同样视为占用。
        for (HotelState.ServiceJob job : hotel.serviceJobs.values()) {
            if (employee.toString().equals(job.worker)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void collect(RestaurantData.Restaurant state, ShopWorkPolicy.Collector collector) {
        HotelState hotel = HotelShops.state(state);
        if (hotel == null) {
            return;
        }
        for (HotelState.CleanJob job : hotel.cleans.values()) {
            double progress = Math.min(0.99, job.progress / Math.max(1.0, (double) wsshotel.HotelConfig.CLEAN_TICKS.get()));
            UUID worker = UUID.fromString(job.worker);
            collector.add(worker, "hotel-room", job.id, progress, HotelWorkPolicy.RETRY);
        }
        // F6：上门服务任务一并上报（仅已派单的）。
        for (HotelState.ServiceJob job : hotel.serviceJobs.values()) {
            if (job.worker == null || job.worker.isEmpty()) {
                continue;
            }
            double progress = Math.min(0.99, job.progress / Math.max(1.0, (double) wsshotel.HotelConfig.SERVICE_TICKS.get()));
            collector.add(UUID.fromString(job.worker), "hotel-service", job.id, progress, HotelWorkPolicy.SERVICE_RETRY);
        }
    }

    /** F6 服务任务卡死恢复：释放执行员工（需求回待派池，稍后重新派单）。 */
    private static final EmployeeWork.Recovery SERVICE_RETRY = new EmployeeWork.Recovery() {
        @Override
        public void retry(org.merlin204.wss.shop.ShopWorkContext context, org.merlin204.wss.shop.EmployeeEntity employee, Object key, int attempts) {
            HotelState hotel = WssHotelMod.attachment(context.shop());
            if (hotel == null) {
                return;
            }
            HotelState.ServiceJob job = hotel.serviceJobs.get(String.valueOf(key));
            if (job != null && employee.getUUID().toString().equals(job.worker)) {
                job.worker = "";
                employee.getNavigation().stop();
                employee.activity(org.merlin204.wss.shop.EmployeeEntity.Activity.IDLE);
            }
        }
    };

    /** 保洁任务卡死时的恢复：释放任务（房间仍保持待清洁，稍后重新派单）。 */
    private static final EmployeeWork.Recovery RETRY = new EmployeeWork.Recovery() {
        @Override
        public void retry(org.merlin204.wss.shop.ShopWorkContext context, org.merlin204.wss.shop.EmployeeEntity employee, Object key, int attempts) {
            HotelState hotel = WssHotelMod.attachment(context.shop());
            if (hotel == null) {
                return;
            }
            HotelState.CleanJob job = hotel.cleans.get(employee.getUUID().toString());
            if (job != null && String.valueOf(key).equals(job.id)) {
                hotel.cleans.remove(employee.getUUID().toString());
                employee.getNavigation().stop();
                employee.activity(org.merlin204.wss.shop.EmployeeEntity.Activity.IDLE);
            }
        }
    };

    /** Restaurant 运行态 → 店铺 的弱引用登记表（仅服务器线程使用）。 */
    public static final class HotelShops {
        private static final WeakHashMap<RestaurantData.Restaurant, ShopData.Shop> LINKS = new WeakHashMap<>();

        public static void link(RestaurantData.Restaurant state, ShopData.Shop shop) {
            LINKS.put(state, shop);
        }

        public static ShopData.Shop shop(RestaurantData.Restaurant state) {
            return LINKS.get(state);
        }

        public static HotelState state(RestaurantData.Restaurant restaurant) {
            ShopData.Shop shop = shop(restaurant);
            return shop == null ? null : WssHotelMod.attachment(shop);
        }

        private HotelShops() {
        }
    }
}
