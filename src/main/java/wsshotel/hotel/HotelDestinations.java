package wsshotel.hotel;

import org.merlin204.wss.shop.JourneyCatalog;
import wsshotel.WssHotelMod;

import java.util.List;

/**
 * 小旅馆的旅游目的地（P2-D）：
 * 温泉疗养地——员工往返后，次日旅馆客流获得包房脉冲。
 * 图片直接引用本体已随包发布的图层资源，不重复携带。
 */
public final class HotelDestinations {

    public static final String ID = "wss_hotel:hot_spring";

    private HotelDestinations() {
    }

    public static JourneyCatalog.Destination hotSpring() {
        return new JourneyCatalog.Destination(
                ID,
                "山间温泉疗养地",
                3,
                2750,
                "山道尽头有一处冒着热气的泉眼，员工泡完回来都说睡得特别香。",
                // 奖励池：沿用本体已通过校验的原料 ID。
                List.of("minecraft:amethyst_shard", "minecraft:slime_ball", "minecraft:iron_nugget"),
                List.of(
                        new JourneyCatalog.Layer("wonder_shop_story_core:textures/journey/journey_01/0.png", 576, 324),
                        new JourneyCatalog.Layer("wonder_shop_story_core:textures/journey/journey_01/1.png", 576, 324),
                        new JourneyCatalog.Layer("wonder_shop_story_core:textures/journey/journey_01/2.png", 576, 324)),
                "wss_hotel");
    }
}
