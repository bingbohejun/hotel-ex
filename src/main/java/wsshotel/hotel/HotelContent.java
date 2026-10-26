package wsshotel.hotel;

import org.merlin204.wss.shop.BusinessPlanCatalog;
import org.merlin204.wss.shop.BusinessPlanConditions;
import org.merlin204.wss.shop.CityResidents;
import org.merlin204.wss.shop.CustomerImpression;
import org.merlin204.wss.shop.EmployeeEntity;
import org.merlin204.wss.shop.RestaurantData;
import org.merlin204.wss.shop.ShopContent;
import org.merlin204.wss.shop.ShopData;
import org.merlin204.wss.shop.ShopDialogue;
import org.merlin204.wss.shop.ShopKind;
import org.merlin204.wss.shop.SpecialCustomers;
import net.minecraft.server.MinecraftServer;

import java.util.List;

/** 小旅馆的店型文案策略。 */
public final class HotelContent implements ShopContent {

    public static final HotelContent INSTANCE = new HotelContent();
    private static final List<String> PREFERENCES = List.of(
            "出差路过，想找个安静的地方睡一觉。",
            "窗户朝街没关系，床干净就行。",
            "希望前台动作快一点，我还赶路呢。",
            "枕头软一点就更完美了。",
            "退房时请把账单列清楚，谢谢。");

    private HotelContent() {
    }

    @Override
    public String fanReturnDescription() {
        return "住得舒心的房客会更常来住";
    }

    @Override
    public ShopDialogue dialogue() {
        return ShopDialogue.of(HotelShopType.kind());
    }

    @Override
    public String companionLibrary() {
        return "restaurant";
    }

    @Override
    public String introduction() {
        return "把店面改成一家温馨的小旅馆。\n前台接待为房客办理入住与退房，客房保洁在房客离开后打扫床位。\n房费按住宿晚数结算，住得越久、房间越舒适，收入越可观。";
    }

    @Override
    public CustomerImpression impression(MinecraftServer server, ShopData.Shop shop, EmployeeEntity npc) {
        RestaurantData.Restaurant state = RestaurantData.get(server).restaurant(shop.id);
        RestaurantData.Order order = null;
        for (RestaurantData.Order candidate : state.orders) {
            if (npc.getUUID().equals(candidate.customer)) {
                order = candidate;
                break;
            }
        }
        if (order == null) {
            // 房客可见定价（P1-A）：印象文本带上当前价目。
            var hotel = wsshotel.WssHotelMod.attachment(shop);
            return new CustomerImpression("想找个干净、安静的地方过夜。听说标准间一晚 "
                    + hotel.standardPrice + " 金，豪华房 " + hotel.deluxePrice + " 金。", "", 0, 0, -1.0);
        }
        String text = switch (order.stage) {
            case EAT -> "睡得很香，先不吵我。";
            case CHECKOUT -> "睡饱了，这就去退房。";
            case LEAVE -> "住得不错，下次还来。";
            default -> "排队办入住呢，别着急。";
        };
        return new CustomerImpression(text, "客房住宿", order.price, order.taste, order.finishedMeal ? CityResidents.mealScore(order) : -1.0);
    }

    @Override
    public String review(int type, double score, long seed) {
        List<String> lines = score >= 60.0
                ? List.of("床铺很干净，一夜好眠。", "前台很麻利，退房不用等。", "房间安静，性价比不错。", "下次出差还住这家。")
                : (score < 40.0
                ? List.of("房间还没打扫好就让我入住，差点。", "等前台等了好久。", "床有点硬，凑合一晚吧。")
                : List.of("睡得还行，先走了。", "手续挺快，谢谢。", "一般般，能用。"));
        return lines.get(Math.floorMod(seed + (long) type, lines.size()));
    }

    @Override
    public BusinessPlanCatalog.Plan plan(BusinessPlanCatalog.Plan plan) {
        return plan;
    }

    /**
     * 经营计划条件适配（与厕所店型 RestroomPlans.condition 同模式）：
     * 依赖 DishCatalog 菜品数据的条件在小旅馆必然 NPE（住宿计划 wss_hotel:room_stay
     * 不是菜品），这里按旅馆语义给出固定结果，其余条件交回本体默认判定。
     */
    @Override
    public Boolean planCondition(int id, BusinessPlanConditions.Context context) {
        return switch (id) {
            case 1 -> wsshotel.WssHotelMod.attachment(context.shop()).standardPrice <= 40; // 有 ≤40 金的"菜单项"：标准间默认 40
            case 4, 9, 25, 43, 50, 67, 69 -> false;                              // 菜品口味/档次类条件：小旅馆不适用
            default -> null;
        };
    }

    @Override
    public List<String> planFoods(BusinessPlanConditions.Context context) {
        // 住宿计划食物不在 DishCatalog，返回空集让 foods 类条件保持未达成。
        return List.of();
    }

    @Override
    public String preference(int type) {
        return PREFERENCES.get(Math.floorMod(type, PREFERENCES.size()));
    }

    @Override
    public String preference(SpecialCustomers.Character character) {
        return PREFERENCES.get(Math.floorMod(character.citizen(), PREFERENCES.size()));
    }

    @Override
    public String reward(SpecialCustomers.Character character) {
        return "感谢惠顾，欢迎下次入住。";
    }
}
