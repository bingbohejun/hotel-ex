package wsshotel.hotel;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import org.merlin204.wss.shop.RetailCatalog;
import org.merlin204.wss.shop.RetailShelfDisplays;

/**
 * 小旅馆零售商品工具：1.4.1 起名称/售价/目录统一查本体 {@link RetailCatalog}
 * （与便利店备货 UI 完全同源）；本类保留容量查询与旧档兼容。
 * 内置 JSON 表（retail_products.json）仅用于 hotel.goodsCatalog 台账字段的兼容输出。
 */
public final class HotelGoods {

    /** 一条商品：id=物品 id（货架展示/交付用），price=顾客支付单价（金）。 */
    public record Goods(String id, String name, String category, int price) {
    }

    private static final List<Goods> ALL = load();
    private static final Map<String, Goods> INDEX = new HashMap<>();

    static {
        for (Goods g : ALL) {
            INDEX.put(g.id(), g);
        }
    }

    private static List<Goods> load() {
        try (InputStream stream = HotelGoods.class.getResourceAsStream("/assets/wss_hotel/catalog/retail_products.json");
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            List<Goods> list = new Gson().fromJson(reader, new TypeToken<List<Goods>>() { }.getType());
            return List.copyOf(list);
        } catch (Exception e) {
            throw new IllegalStateException("无法读取旅馆零售商品目录", e);
        }
    }

    public static List<Goods> ALL() {
        return ALL;
    }

    public static String name(String id) {
        RetailCatalog.Product product = RetailCatalog.get(id);
        return product == null ? "商品" : product.name();
    }

    public static int price(String id) {
        RetailCatalog.Product product = RetailCatalog.get(id);
        return product == null ? 5 : product.price();
    }

    /** 货架单层容量（本体按物品尺寸 4/8/12 不等）；未登记的物品返回 0（不可陈列）。 */
    public static int perLayer(String id) {
        if (RetailCatalog.get(id) == null) {
            return 0;
        }
        int cap = RetailShelfDisplays.capacity(stack(id));
        return cap > 0 ? cap : 8;
    }

    /** 解析物品 id → ItemStack（用于容量查询/校验物品存在）。 */
    public static ItemStack stack(String id) {
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)));
    }

    /** 物品 id 是否真实存在且可陈列（本体零售目录内）。 */
    public static boolean displayable(String id) {
        return RetailCatalog.get(id) != null && perLayer(id) > 0 && !stack(id).isEmpty();
    }

    private HotelGoods() {
    }
}
