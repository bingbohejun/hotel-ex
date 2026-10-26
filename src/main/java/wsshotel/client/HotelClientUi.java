package wsshotel.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import org.merlin204.wss.api.client.RegisterWssClientUiEvent;
import org.merlin204.wss.api.client.ShopNavSpec;
import org.merlin204.wss.client.shop.ShopClient;
import wsshotel.WssHotelMod;
import wsshotel.hotel.HotelShopType;
import wsshotel.net.HotelPackets;

/**
 * 客户端注册：店铺管理界面里的“客房台账”与“客房升级”导航入口，以及台账数据接收。
 * 仅在物理客户端加载。
 */
public final class HotelClientUi {

    private static volatile JsonObject ledger;
    private static volatile String ledgerShop;
    private static volatile com.google.gson.JsonObject chainLedger;

    public static void register(RegisterWssClientUiEvent event) {
        // ShopNavSpec 签名：(label, icon, u, v, size, order, open)——size 是图标尺寸（1..28），
        // 不是宽高对；超限会抛 IllegalArgumentException 导致启动崩溃。
        event.shopNav(WssHotelMod.id("ledger"), HotelShopType.kind(), new ShopNavSpec(
                "客房台账",
                ResourceLocation.parse("wonder_shop_story_core:textures/gui/sprout/ui/icons/all_icons.png"),
                224, 16, 16, 30,
                parent -> new HotelLedgerScreen(parent)));
        // 客房升级（蓝框）：床位档位升级与四档价目调整。
        // 注：曾尝试移入店铺配置页签（ShopConfigurationMixin），因本体回调链式签名的
        // Mixin 兼容问题三次未稳定，1.4.8 起回退为侧边导航入口。
        event.shopNav(WssHotelMod.id("upgrade"), HotelShopType.kind(), new ShopNavSpec(
                "客房升级",
                ResourceLocation.parse("wonder_shop_story_core:textures/gui/sprout/ui/icons/all_icons.png"),
                224, 16, 16, 32,
                parent -> new HotelUpgradeScreen(parent)));
    }

    /** 当前店铺管理会话的店铺 ID（打开台账页时使用）。 */
    public static String currentShopId() {
        return ShopClient.state != null ? ShopClient.state.id() : null;
    }

    public static void request(String shopId) {
        if (shopId != null) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new HotelPackets.LedgerRequest(shopId));
        }
    }

    /** F3：连锁台账请求（聚合本人全部小旅馆）。 */
    public static void requestChain(String shopId) {
        if (shopId != null) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new HotelPackets.ChainRequest(shopId));
        }
    }

    /** 服务端回包入口（HotelPackets 客户端处理器调用）。 */
    public static void receive(String json) {
        try {
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            ledgerShop = parsed.has("name") ? parsed.get("name").getAsString() : "";
            if (parsed.has("mode") && "chain".equals(parsed.get("mode").getAsString())) {
                chainLedger = parsed;
            } else {
                ledger = parsed;
            }
            Screen screen = Minecraft.getInstance().screen;
            if (screen instanceof HotelLedgerScreen ledgerScreen) {
                ledgerScreen.onDataArrived();
            } else if (screen instanceof HotelUpgradeScreen upgradeScreen) {
                upgradeScreen.onDataArrived();
            }
        } catch (RuntimeException ignored) {
        }
    }

    public static JsonObject ledger() {
        return ledger;
    }

    public static JsonObject chainLedger() {
        return chainLedger;
    }

    private HotelClientUi() {
    }
}
