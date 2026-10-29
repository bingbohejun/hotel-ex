package wsshotel;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import wsshotel.hotel.DrinkMachineBlock;
import wsshotel.hotel.RoomSignBlock;

/**
 * 附属自有方块与物品 + 创造模式标签页“旅馆扩展”。
 * 门牌：挂墙四态房态显示（放置时绑定最近客房，业务 tick 同步状态）；
 * 房卡：退房概率掉落的纪念品；饮品台：房客退房前顺路购买饮品的柜台。
 */
public final class HotelBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(WssHotelMod.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(WssHotelMod.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, WssHotelMod.MOD_ID);

    /** F1 客房门牌：挂墙四态房态显示。 */
    public static final DeferredBlock<RoomSignBlock> ROOM_SIGN = BLOCKS.registerBlock("room_sign", RoomSignBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .strength(0.8f)
                    .sound(SoundType.WOOD)
                    .noOcclusion());

    public static final DeferredItem<BlockItem> ROOM_SIGN_ITEM =
            ITEMS.registerSimpleBlockItem("room_sign", ROOM_SIGN);

    /** F1 房卡：退房概率掉落的纪念品（自定义店名）。 */
    public static final DeferredItem<Item> ROOM_CARD = ITEMS.register("room_card",
            () -> new Item(new Item.Properties().stacksTo(16)));

    /** F2 旅馆饮料机：立式三连方块，工作页购买（仅旅馆店型）。 */
    public static final DeferredBlock<DrinkMachineBlock> DRINK_MACHINE =
            BLOCKS.registerBlock("drink_machine", wsshotel.hotel.DrinkMachineBlock::new,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_RED)
                            .strength(1.2f)
                            .sound(SoundType.METAL)
                            .noOcclusion());

    public static final DeferredItem<BlockItem> DRINK_MACHINE_ITEM =
            ITEMS.registerSimpleBlockItem("drink_machine", DRINK_MACHINE);


    /** “旅馆扩展”创造模式标签页。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> HOTEL_TAB = TABS.register("hotel_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.literal("旅馆扩展"))
                    .icon(() -> new ItemStack(ROOM_CARD.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ROOM_SIGN_ITEM.get());
                        output.accept(DRINK_MACHINE_ITEM.get());
                        output.accept(ROOM_CARD.get());
                    })
                    .build());

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        TABS.register(modBus);
    }

    private HotelBlocks() {
    }
}
