package wsshotel.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import org.merlin204.wss.client.shop.ShopModalScreen;

/**
 * 客房升级（本体同款宽面板，ShopModalScreen 480×340）：
 * 床位档位永久提升（标准→舒适 20000，舒适→豪华 50000），影响定价与房客体验。
 * 价目编辑在台账页。
 */
public final class HotelUpgradeScreen extends ShopModalScreen {

    private static final String[] TIER_NAMES = {"标准", "舒适", "豪华"};
    private static final int MAX_VISIBLE_ROWS = 12;

    private boolean requested;

    public HotelUpgradeScreen(Screen previous) {
        super("客房升级", previous);
    }

    @Override
    protected int cardWidth() {
        return 480;
    }

    @Override
    protected int cardHeight() {
        return 340;
    }

    @Override
    public void autoManage() {
        this.onClose();
    }

    @Override
    public void tick() {
        super.tick();
        if (Minecraft.getInstance().level != null && Minecraft.getInstance().level.getGameTime() % 200 == 0) {
            requestIfNeeded();
        }
    }

    private void requestIfNeeded() {
        if (!this.requested) {
            this.requested = true;
            HotelClientUi.request(HotelClientUi.currentShopId());
        }
    }

    void onDataArrived() {
        this.requested = false;
        this.init();
    }

    private static int intOf(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsInt() : 0;
    }

    @Override
    protected void content(UIElement card, int w, int h) {
        if (!this.requested && HotelClientUi.ledger() == null) {
            requestIfNeeded();
        }
        JsonObject data = HotelClientUi.ledger();
        if (data == null) {
            card.addChild(HotelUI.text("正在读取房态……", 12, 44, w - 24));
            return;
        }

        int y = 40;
        card.addChild(HotelUI.text("床位档位永久提升（与床色无关），影响房价与房客体验。", 12, y, w - 24));
        y += 18;

        JsonArray rooms = data.has("rooms") ? data.getAsJsonArray("rooms") : new JsonArray();
        if (rooms.size() == 0) {
            card.addChild(HotelUI.text("还没有客房：摆放任意颜色的床即可。", 12, y, w - 24));
            return;
        }
        int shown = 0;
        for (JsonElement element : rooms) {
            if (shown >= MAX_VISIBLE_ROWS) {
                card.addChild(HotelUI.text("…… 其余 " + (rooms.size() - shown) + " 间从略", 12, y, w - 24));
                break;
            }
            JsonObject room = element.getAsJsonObject();
            int tier = intOf(room, "tier");
            long head = room.has("head") ? room.get("head").getAsLong() : 0L;
            String label = TIER_NAMES[Math.min(2, tier)] + "房 " + intOf(room, "x") + "," + intOf(room, "y") + "," + intOf(room, "z");
            card.addChild(HotelUI.text(label, 12, y + 5, 200));
            if (tier < 2) {
                final long roomHead = head;
                final int nextTier = tier + 1;
                int cost = tier == 0 ? 20000 : 50000;
                card.addChild(HotelUI.button("升" + TIER_NAMES[nextTier] + " " + cost + "金", 220, y, 130, () -> {
                    String shopId = HotelClientUi.currentShopId();
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new wsshotel.net.HotelPackets.UpgradeRoom(shopId == null ? "" : shopId, roomHead));
                    HotelClientUi.request(shopId);
                }));
            } else {
                card.addChild(HotelUI.text("已顶级", 220, y + 5, 130));
            }
            y += 22;
            shown++;
        }
    }
}
