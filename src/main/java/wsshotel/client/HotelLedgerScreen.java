package wsshotel.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import org.merlin204.wss.client.ui.SproutTabView;
import org.merlin204.wss.client.shop.ShopModalScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import wsshotel.net.HotelPackets;

import java.util.ArrayList;
import java.util.List;

/**
 * 客房台账（本体同款宽面板，ShopModalScreen 640×430）：
 * 统计总览 + 页签（房态 / 价目 / 连锁）。
 * 数据经台账网络拉取，收包后重建内容（ShopModalScreen.init 可重入，滚动记忆保留）。
 */
public final class HotelLedgerScreen extends ShopModalScreen {

    private static final String[] TIER_NAMES = {"标准", "舒适", "豪华"};
    private static final int MAX_VISIBLE_ROWS = 14;

    /** 价目编辑工作副本：0 标准 / 1 舒适 / 2 豪华 / 3 钟点 / 4 饮品。 */
    private final int[] prices = new int[]{10000, 14000, 19000, 1200, 200};
    private final int[] mins = new int[]{10000, 14000, 19000, 1200, 50};
    private final int[] maxs = new int[]{15000, 21000, 28000, 2000, 2000};
    private final List<String> dishChoices = new ArrayList<>();
    private final String[] dishSlots = new String[5];
    private int breakfastCap = 3;
    private boolean hourly;
    private boolean breakfast;
    private int selectedTab;
    private boolean requested;
    private boolean chainRequested;

    public HotelLedgerScreen(Screen previous) {
        super("客房台账", previous);
    }

    @Override
    protected int cardWidth() {
        return 640;
    }

    @Override
    protected int cardHeight() {
        return 430;
    }

    @Override
    public void autoManage() {
        this.onClose();
    }

    @Override
    public void tick() {
        super.tick();
        // 打开期间每 10 秒自动刷新一次本店台账。
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

    private void requestChainIfNeeded() {
        if (!this.chainRequested) {
            this.chainRequested = true;
            HotelClientUi.requestChain(HotelClientUi.currentShopId());
        }
    }

    void onDataArrived() {
        this.requested = false;
        this.init();
    }


    @Override
    protected void content(UIElement card, int w, int h) {
        if (!this.requested && HotelClientUi.ledger() == null) {
            requestIfNeeded();
        }
        JsonObject data = this.selectedTab == 2 ? HotelClientUi.chainLedger() : HotelClientUi.ledger();
        if (data == null) {
            card.addChild(HotelUI.text("正在读取台账……", 12, 44, w - 24));
            return;
        }

        // —— 统计总览 ——
        int y = 40;
        card.addChild(HotelUI.text("今日房费 " + longOf(data, "todayIncome") + " 金 · 累计 " + longOf(data, "totalIncome") + " 金", 12, y, w - 24));
        y += 14;
        card.addChild(HotelUI.text("在住 " + intOf(data, "occupied") + "/" + intOf(data, "totalRooms")
                + " · 入住率 " + Math.round(doubleOf(data, "occupancy") * 100) + "%"
                + " · 峰值 " + Math.round(doubleOf(data, "peakOccupancy") * 100) + "%", 12, y, w - 24));
        y += 14;
        card.addChild(HotelUI.text("接待 " + longOf(data, "totalCheckins") + " 位 · " + longOf(data, "totalNights")
                + " 晚 · 保洁 " + longOf(data, "totalCleans") + " 次 · 投诉 " + longOf(data, "totalComplaints")
                + " · 服务小费 " + longOf(data, "serviceIncome") + " 金", 12, y, w - 24));
        y += 14;
        StringBuilder tags = new StringBuilder("Lv." + intOf(data, "level") + " " + textOf(data, "title"));
        String termName = textOf(data, "termName");
        if (!termName.isEmpty()) {
            tags.append(" · ").append(termName).append("（").append(textOf(data, "seasonLabel")).append("）");
        }
        if (data.has("contestDay") && data.get("contestDay").getAsBoolean()) {
            tags.append(" · [旺日]");
        }
        if (data.has("pulseActive") && data.get("pulseActive").getAsBoolean()) {
            tags.append(" · [旅行团脉冲]");
        }
        card.addChild(HotelUI.text(tags.toString(), 12, y, w - 24));
        y += 18;

        // —— 底部刷新按钮 ——
        card.addChild(HotelUI.button("刷新", 12, h - 42, 90, () -> {
            this.requested = false;
            this.chainRequested = false;
            requestIfNeeded();
            if (this.selectedTab == 2) {
                requestChainIfNeeded();
            }
        }));

        // —— 页签：房态 / 价目 / 连锁 ——
        SproutTabView tabs = new SproutTabView();
        HotelUI.box(tabs, 8, y, w - 16, h - y - 50);
        card.addChild(tabs);

        UIElement roomsPage = tabs.page("房态", "wss-hotel-tab-rooms");
        buildRoomsPage(roomsPage, data, w - 44);

        UIElement pricingPage = tabs.page("价目", "wss-hotel-tab-pricing");
        buildPricingPage(pricingPage, data, w - 44);

        UIElement chainPage = tabs.page("连锁", "wss-hotel-tab-chain");
        if (HotelClientUi.chainLedger() == null) {
            requestChainIfNeeded();
        }
        buildChainPage(chainPage, HotelClientUi.chainLedger(), w - 44);

        tabs.select(Math.min(this.selectedTab, 2));
        tabs.setOnTabSelected(tab -> {
            this.selectedTab = tabs.index();
            if (this.selectedTab == 2) {
                requestChainIfNeeded();
            }
        });
    }

    private void buildRoomsPage(UIElement page, JsonObject data, int width) {
        int y = 6;
        JsonArray rooms = data.has("rooms") ? data.getAsJsonArray("rooms") : new JsonArray();
        if (rooms.size() == 0) {
            page.addChild(HotelUI.text("还没有识别到床位：摆放任意颜色的床即可成为客房。", 4, y, width));
            return;
        }
        int shown = 0;
        for (JsonElement element : rooms) {
            if (shown >= MAX_VISIBLE_ROWS) {
                page.addChild(HotelUI.text("…… 其余 " + (rooms.size() - shown) + " 间从略", 4, y, width));
                break;
            }
            JsonObject room = element.getAsJsonObject();
            String status = textOf(room, "status");
            String stateText = switch (status) {
                case "occupied" -> "入住中";
                case "dirty" -> "待清洁";
                case "cleaning" -> "保洁中";
                default -> "空闲";
            };
            page.addChild(HotelUI.text(
                    tierName(intOf(room, "tier")) + "房 " + intOf(room, "x") + "," + intOf(room, "y") + "," + intOf(room, "z"),
                    4, y, width / 2));
            page.addChild(HotelUI.text(stateText + " · " + textOf(room, "detail"), width / 2 + 8, y, width / 2 - 8));
            y += 16;
            shown++;
        }
    }

    private void buildPricingPage(UIElement page, JsonObject data, int width) {
        if (data.has("pricing")) {
            JsonObject pricing = data.getAsJsonObject("pricing");
            prices[0] = pricing.get("standard").getAsInt();
            prices[1] = pricing.get("comfort").getAsInt();
            prices[2] = pricing.get("deluxe").getAsInt();
            prices[3] = pricing.has("hourlyPrice") ? pricing.get("hourlyPrice").getAsInt() : 1200;
            mins[0] = pricing.has("standardMin") ? pricing.get("standardMin").getAsInt() : 10000;
            maxs[0] = pricing.has("standardMax") ? pricing.get("standardMax").getAsInt() : 15000;
            mins[1] = pricing.has("comfortMin") ? pricing.get("comfortMin").getAsInt() : 14000;
            maxs[1] = pricing.has("comfortMax") ? pricing.get("comfortMax").getAsInt() : 21000;
            mins[2] = pricing.has("deluxeMin") ? pricing.get("deluxeMin").getAsInt() : 19000;
            maxs[2] = pricing.has("deluxeMax") ? pricing.get("deluxeMax").getAsInt() : 28000;
            mins[3] = pricing.has("hourlyMin") ? pricing.get("hourlyMin").getAsInt() : 1200;
            maxs[3] = pricing.has("hourlyMax") ? pricing.get("hourlyMax").getAsInt() : 2000;
            prices[4] = data.has("drinkPrice") ? data.get("drinkPrice").getAsInt() : 200;
            hourly = pricing.get("hourly").getAsBoolean();
        }
        if (data.has("breakfast")) {
            JsonObject breakfastJson = data.getAsJsonObject("breakfast");
            breakfast = breakfastJson.get("enabled").getAsBoolean();
            breakfastCap = breakfastJson.has("cap") ? breakfastJson.get("cap").getAsInt() : 3;
            dishChoices.clear();
            for (JsonElement element : breakfastJson.getAsJsonArray("available")) {
                dishChoices.add(element.getAsString());
            }
            int slot = 0;
            for (JsonElement element : breakfastJson.getAsJsonArray("dishes")) {
                if (slot < dishSlots.length) {
                    dishSlots[slot++] = element.getAsString();
                }
            }
            for (; slot < dishSlots.length; slot++) {
                dishSlots[slot] = null;
            }
        }

        int y = 6;
        String[] rowNames = {"标准房/晚", "舒适房/晚", "豪华房/晚", "钟点房/次", "饮品/次"};
        for (int row = 0; row < rowNames.length; row++) {
            final int rowIndex = row;
            page.addChild(HotelUI.text(rowNames[row], 4, y + 5, 90));
            page.addChild(HotelUI.text(String.valueOf(prices[row]), 100, y + 5, 90));
            page.addChild(HotelUI.button("-", 190, y, 36, () -> {
                prices[rowIndex] = Math.clamp(prices[rowIndex] - step(rowIndex), mins[rowIndex], maxs[rowIndex]);
                pushPricing(rowIndex);
            }));
            page.addChild(HotelUI.button("+", 232, y, 36, () -> {
                prices[rowIndex] = Math.clamp(prices[rowIndex] + step(rowIndex), mins[rowIndex], maxs[rowIndex]);
                pushPricing(rowIndex);
            }));
            page.addChild(HotelUI.text(mins[row] + "~" + maxs[row], 276, y + 5, 110));
            y += 24;
        }

        final int hourlyRow = y;
        page.addChild(HotelUI.text("钟点房（半晚价引流）", 4, hourlyRow + 5, 130));
        page.addChild(HotelUI.button(hourly ? "开" : "关", 150, hourlyRow, 40, () -> {
            hourly = !hourly;
            pushPricing(3);
        }));
        y += 26;

        final int breakfastRow = y;
        page.addChild(HotelUI.text("早餐加购（房客点单，按菜价加收）", 4, breakfastRow + 5, 180));
        page.addChild(HotelUI.button(breakfast ? "开" : "关", 190, breakfastRow, 40, () -> {
            breakfast = !breakfast;
            pushBreakfast();
        }));
        y += 26;
        if (breakfast) {
            for (int slot = 0; slot < breakfastCap && slot < dishSlots.length; slot++) {
                final int slotIndex = slot;
                page.addChild(HotelUI.text("菜" + (slot + 1), 4, y + 5, 40));
                page.addChild(HotelUI.text(dishSlots[slot] == null ? "（空）" : clip(dishName(dishSlots[slot]), 150), 50, y + 5, 170));
                page.addChild(HotelUI.button("<", 230, y, 30, () -> {
                    dishSlots[slotIndex] = neighbor(dishSlots[slotIndex], dishChoices, false);
                    pushBreakfast();
                }));
                page.addChild(HotelUI.button(">", 264, y, 30, () -> {
                    dishSlots[slotIndex] = neighbor(dishSlots[slotIndex], dishChoices, true);
                    pushBreakfast();
                }));
                y += 24;
            }
        }
    }

    private static int step(int row) {
        return row == 4 ? 50 : 100;
    }

    /** 价目/钟点/饮品即时下发（服务端校验后回包刷新）。 */
    private void pushPricing(int changedRow) {
        String shopId = HotelClientUi.currentShopId();
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new HotelPackets.PriceUpdate(shopId == null ? "" : shopId, prices[0], prices[1], prices[2], prices[3], hourly));
        if (changedRow == 4) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new HotelPackets.DrinkPriceUpdate(shopId == null ? "" : shopId, prices[4]));
        }
    }

    private void pushBreakfast() {
        String shopId = HotelClientUi.currentShopId();
        StringBuilder dishes = new StringBuilder();
        for (int i = 0; i < breakfastCap && i < dishSlots.length; i++) {
            if (dishSlots[i] != null && !dishSlots[i].isEmpty()) {
                if (dishes.length() > 0) {
                    dishes.append(';');
                }
                dishes.append(dishSlots[i]);
            }
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new HotelPackets.BreakfastUpdate(shopId == null ? "" : shopId, breakfast, dishes.toString()));
    }

    private void buildChainPage(UIElement page, JsonObject data, int width) {
        if (data == null) {
            page.addChild(HotelUI.text("正在读取连锁数据……", 4, 6, width));
            return;
        }
        int y = 6;
        page.addChild(HotelUI.text("全部门店房费累计 " + longOf(data, "totalIncome") + " 金 · "
                + longOf(data, "totalNights") + " 晚 · 接待 " + longOf(data, "totalCheckins") + " 位", 4, y, width));
        y += 16;
        page.addChild(HotelUI.text("在住 " + intOf(data, "occupied") + "/" + intOf(data, "totalRooms")
                + " · 入住率 " + Math.round(doubleOf(data, "occupancy") * 100) + "%", 4, y, width));
        y += 18;
        JsonArray hotels = data.has("hotels") ? data.getAsJsonArray("hotels") : new JsonArray();
        if (hotels.size() == 0) {
            page.addChild(HotelUI.text("名下还没有其他小旅馆。", 4, y, width));
            return;
        }
        for (int i = 0; i < hotels.size() && i < 20; i++) {
            JsonObject hotel = hotels.get(i).getAsJsonObject();
            page.addChild(HotelUI.text((i + 1) + ". " + textOf(hotel, "name") + "（Lv." + intOf(hotel, "level") + "）", 4, y, width / 2));
            page.addChild(HotelUI.text(longOf(hotel, "totalIncome") + "金 · " + intOf(hotel, "occupied") + "/" + intOf(hotel, "roomCount") + "间",
                    width / 2 + 8, y, width / 2 - 8));
            y += 16;
        }
    }

    private static String neighbor(String current, List<String> choices, boolean next) {
        int index = current == null ? -1 : choices.indexOf(current);
        if (next) {
            index = Math.min(choices.size() - 1, index + 1);
        } else {
            index = Math.max(0, index - 1);
        }
        if (index < 0) {
            index = 0;
        }
        return choices.get(index);
    }

    private static String tierName(int tier) {
        return tier >= 0 && tier < TIER_NAMES.length ? TIER_NAMES[tier] : TIER_NAMES[0];
    }

    private static String textOf(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static int intOf(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsInt() : 0;
    }

    private static long longOf(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsLong() : 0L;
    }

    private static double doubleOf(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsDouble() : 0.0;
    }

    private static String clip(String text, int length) {
        return text.length() > length ? text.substring(0, length) + "…" : text;
    }

    private static String dishName(String dishId) {
        try {
            net.minecraft.resources.ResourceLocation key = net.minecraft.resources.ResourceLocation.tryParse(dishId);
            if (key != null) {
                if (net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key)) {
                    return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key).getDescription().getString();
                }
                if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(key)) {
                    return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(key).getName().getString();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return dishId;
    }
}
