package wsshotel.client;

import com.lowdragmc.lowdraglib2.gui.ui.style.StyleOrigin;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import net.minecraft.network.chat.Component;
import org.merlin204.wss.client.ui.SproutButton;
import org.merlin204.wss.client.shop.ShopNavButton;

/**
 * 本体同款 LDLib2 布局助手（跨包复刻本体 StaffPanel 的包级 helper）：
 * 绝对定位（lss: position/left/top/width/height/flex-shrink）、
 * 九宫格暖色面板背景（本体 setting_menu.png 精灵）、文本与按钮。
 */
public final class HotelUI {

    private HotelUI() {
    }

    /** 绝对定位 + 尺寸（等价 StaffPanel.box）。 */
    public static <T extends UIElement> T box(T element, int x, int y, int width, int height) {
        element.lss("position", "absolute", StyleOrigin.INLINE);
        element.lss("left", x, StyleOrigin.INLINE);
        element.lss("top", y, StyleOrigin.INLINE);
        size(element, width, height);
        return element;
    }

    public static void size(UIElement element, int width, int height) {
        element.lss("width", width, StyleOrigin.INLINE);
        element.lss("height", height, StyleOrigin.INLINE);
        element.lss("flex-shrink", 0, StyleOrigin.INLINE);
    }

    /** 本体同款暖色面板背景（StaffPanel.panel 的精灵与九宫格边距一致）。 */
    public static void panel(UIElement element) {
        element.style(style -> style.backgroundTexture(
                SpriteTexture.of("wonder_shop_story_core:textures/gui/sprout/ui/other_ui_sprites/setting_menu.png")
                        .setSprite(139, 12, 106, 122)
                        .setBorder(5, 5, 5, 5)));
    }

    public static Label text(String content, int x, int y, int width) {
        Label label = new Label();
        label.setText(content);
        box(label, x, y, width, 12);
        return label;
    }

    public static SproutButton button(String content, int x, int y, int width, Runnable onClick) {
        SproutButton button = new SproutButton();
        button.setText(ShopNavButton.buttonText(content));
        button.textStyle(style -> style.textAlignHorizontal(com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal.CENTER));
        button.setOnClick(event -> onClick.run());
        box(button, x, y, width, 22);
        button.lss("padding-top", 2, StyleOrigin.INLINE);
        button.lss("padding-bottom", 2, StyleOrigin.INLINE);
        return button;
    }

    public static Label heading(String content, int x, int y, int width) {
        Label label = text(content, x, y, width);
        label.textStyle(style -> style.fontSize(12.0F));
        return label;
    }

    public static Label note(String content, int x, int y, int width) {
        Label label = text(content, x, y, width);
        label.textStyle(style -> style.fontSize(8.0F));
        return label;
    }

    /** 台账页面通用标题行。 */
    public static Label title(String content, int cardWidth) {
        Label label = text(content, (cardWidth - 200) / 2, 0, 200);
        label.textStyle(style -> style.fontSize(12.0F));
        return label;
    }

    public static Component plain(String content) {
        return Component.literal(content);
    }
}
