package wsshotel.hotel;

import org.merlin204.wss.shop.RestaurantData;
import org.merlin204.wss.shop.RestaurantRecipes;
import org.merlin204.wss.shop.RestaurantSnapshot;
import org.merlin204.wss.shop.ResearchCatalog;
import org.merlin204.wss.shop.ShopData;
import org.merlin204.wss.shop.ShopFacilities;
import org.merlin204.wss.shop.ShopProducts;
import wsshotel.HotelApi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 小旅馆不提供商品目录：没有菜单、研发与外卖。
 * 该实现满足 ShopProducts 契约，全部返回空。
 */
public enum HotelProducts implements ShopProducts {
    INSTANCE;

    public static final String STAY_FOOD = "wss_hotel:room_stay";

    @Override
    public boolean accepts(String food) {
        // 与本体厕所（RestroomShopType）一致返回 false：住宿不是可上架/可研究的"食物"。
        // 本体对 accepts 的全部消费点都在 discoveredFoods/研究路径，返回 false 无副作用。
        return false;
    }

    @Override
    public boolean canChoose(MinecraftServer server, RestaurantData.Restaurant state, int citizenType, List<RestaurantRecipes.Plan> menu, boolean special) {
        return !menu.isEmpty();
    }

    @Override
    public RestaurantRecipes.Plan choose(MinecraftServer server, ShopData.Shop shop, RestaurantData.Restaurant state, int citizenType, List<RestaurantRecipes.Plan> menu, boolean special, int occasion, String preferred) {
        return menu.isEmpty() ? null : menu.getFirst();
    }

    @Override
    public RestaurantRecipes.Plan chooseSpecial(MinecraftServer server, ShopData.Shop shop, RestaurantData.Restaurant state, org.merlin204.wss.shop.SpecialCustomers.Character customer, List<RestaurantRecipes.Plan> menu) {
        return menu.isEmpty() ? null : menu.getFirst();
    }

    /** 住宿服务的“菜单”：与本体厕所同口径——有客房时返回合成的住宿计划（本体接待链路要求菜单非空）。 */
    @Override
    public List<RestaurantRecipes.Plan> available(ShopData.Shop shop, RestaurantData.Restaurant state, ShopFacilities facilities) {
        return facilities != null && !facilities.extension(HotelRooms.class, HotelCrowdPolicy.EMPTY).rooms().isEmpty()
                ? List.of(HotelApi.stayPlan())
                : List.of();
    }

    @Override
    public List<RestaurantRecipes.Plan> potential(RestaurantData.Restaurant state, boolean includeUnlearned) {
        return List.of(HotelApi.stayPlan());
    }

    @Override
    public List<RestaurantRecipes.Plan> all() {
        return List.of(HotelApi.stayPlan());
    }

    @Override
    public Set<String> offered(RestaurantData.Restaurant state) {
        return Set.of(STAY_FOOD);
    }

    @Override
    public int variety(RestaurantData.Restaurant state) {
        // 与本体厕所一致硬编码 10：varietyPopularity(10)=0、menuVariety(10)=1.0，
        // 属"中性值"。若返回 1，MenuVariety.popularity 会给店铺扣 30 人气，
        // 压低 popularityRadius 与到店概率。
        return 10;
    }

    @Override
    public RestaurantSnapshot.MenuDish display(MinecraftServer server, RestaurantData.Restaurant state, RestaurantRecipes.Plan plan, ShopFacilities facilities) {
        // 与本体厕所同口径：必须返回非空占位 MenuDish（RestaurantService.snapshot 会把
        // menu/catalog 逐项交给 display 并经 Gson 发给店铺管理界面；返回 null 会让
        // 菜单列表里出现 null 元素，客户端解析/渲染可能崩，进而影响在管理界面设置营业时段）。
        return new RestaurantSnapshot.MenuDish(
                STAY_FOOD, 1, false, true, List.of(), 0, 0, 0, 0, List.of(), List.of());
    }

    @Override
    public List<ResearchCatalog.Recipe> researchRecipes() {
        return List.of();
    }

    @Override
    public String emptyMessage() {
        return "小旅馆无需上架商品，摆好床位就能接待房客。";
    }

    @Override
    public String varietyDescription() {
        return "客房";
    }

    @Override
    public boolean nearDisplay(ServerLevel level, ShopData.Shop shop, BlockPos pos, ShopFacilities facilities) {
        return false;
    }

    @Override
    public Collection<String> decorationIngredients(RestaurantRecipes.Plan plan) {
        return List.of();
    }
}
