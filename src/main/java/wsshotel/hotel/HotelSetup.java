package wsshotel.hotel;

import org.merlin204.wss.shop.CityShopData;
import org.merlin204.wss.shop.FurnitureCatalog;
import org.merlin204.wss.shop.RestaurantData;
import org.merlin204.wss.shop.ShopData;
import org.merlin204.wss.shop.ShopFacilities;
import org.merlin204.wss.shop.ShopFacilityScan;
import org.merlin204.wss.shop.ShopSetup;
import org.merlin204.wss.shop.StarterLayouts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import wsshotel.HotelConfig;
import wsshotel.WssHotelMod;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 小旅馆开店策略：初始布局（前台+床位）、床位设施扫描、家具准入。
 */
public final class HotelSetup implements ShopSetup {

    public static final HotelSetup INSTANCE = new HotelSetup();

    private HotelSetup() {
    }

    // —— 设施扫描：把床识别为客房，货物架识别为零售货架 ——

    @Override
    public ShopFacilityScan scanner() {
        return new ShopFacilityScan() {
            private final HotelRooms.Collector collector = new HotelRooms.Collector();
            private net.minecraft.server.level.ServerLevel level;

            @Override
            public void accept(net.minecraft.server.level.ServerLevel level, BlockPos pos, BlockState state) {
                this.level = level;
                // 床与候位椅都要转发：此前只转发床，Collector 的候位椅分支成了死代码，
                // 导致候位椅永远扫描不到（P1-F 候位等待退化为一直站立）。
                if (isBed(state) || HotelRooms.Collector.isWaitingChair(state)) {
                    collector.accept(pos, state);
                }
            }

            @Override
            public ShopFacilities finish(ShopFacilities common) {
                if (common == null || this.level == null) {
                    return common;
                }
                HotelRooms rooms = collector.finish(this.level);
                // 货架直接采用本体便利店货架（retail_shelf）：核心扫描器已把下半块位置
                // 收进 retailShelves，这里转成旅馆自己的扩展供零售玩法使用。
                ShopFacilities result = common.withExtension(HotelRooms.class, rooms)
                        .withExtension(HotelShelves.class, new HotelShelves(common.retailShelves()));
                // 岗位待命锚点：前台接待 → 收银工位，客房保洁 → 首间客房门口。
                java.util.Map<org.merlin204.wss.shop.ShopJob, net.minecraft.core.BlockPos> stations = new java.util.HashMap<>();
                if (!common.registers().isEmpty() && common.registers().get(0).worker() != null) {
                    stations.put(org.merlin204.wss.shop.ShopJob.CASHIER, common.registers().get(0).worker());
                }
                if (!rooms.rooms().isEmpty()) {
                    stations.put(WssHotelMod.MAID, rooms.rooms().get(0).approach());
                }
                if (!stations.isEmpty()) {
                    result = result.withExtension(org.merlin204.wss.shop.ShopStaff.Stations.class,
                            new org.merlin204.wss.shop.ShopStaff.Stations(stations));
                }
                return result;
            }
        };
    }

    public static boolean isBed(BlockState state) {
        if (state.getBlock() instanceof BedBlock) {
            return true;
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return HotelConfig.BED_BLOCKS.get().contains(id);
    }

    // —— 初始布局：仿便利店（前台收银电脑），餐桌位换成床 ——

    @Override
    public Map<BlockPos, BlockState> layout(StarterLayouts.Layout l) {
        LinkedHashMap<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        BlockPos register = pos(l, l.register());
        if (register != null) {
            blocks.put(register, block("yuushya:raw_oak_wood"));
            Direction facing = dir(l.register().size() > 2 ? l.register().get(2) : "S");
            BlockState computer = block("yuushya:checkout_computer");
            if (computer != null) {
                blocks.put(register.above(), horizontal(computer, facing));
            }
        }

        String bedId = HotelConfig.BED_BLOCKS.get().isEmpty() ? "minecraft:red_bed" : HotelConfig.BED_BLOCKS.get().get(0);
        for (List<String> table : l.tables()) {
            BlockPos at = pos(l, table);
            if (at == null) {
                continue;
            }
            Direction facing = table.size() > 2 && table.get(2).equals("Z") ? Direction.SOUTH : Direction.EAST;
            placeBed(blocks, at, facing, bedId);
        }

        List<List<String>> kitchen = l.kitchen();
        if (kitchen != null && !kitchen.isEmpty()) {
            BlockPos rest = pos(l, kitchen.get(0));
            if (rest != null) {
                BlockState chair = block("kaleidoscope_cookery:chair_oak");
                if (chair != null) {
                    blocks.put(rest, horizontal(chair, dir(kitchen.get(0).size() > 2 ? kitchen.get(0).get(2) : "S")));
                }
            }
        }
        return blocks;
    }

    private static void placeBed(Map<BlockPos, BlockState> blocks, BlockPos foot, Direction facing, String bedId) {
        Block bed = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(bedId));
        if (!(bed instanceof BedBlock)) {
            bed = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("minecraft:red_bed"));
        }
        if (!(bed instanceof BedBlock)) {
            return;
        }
        BlockState footState = bed.defaultBlockState()
                .setValue(BedBlock.FACING, facing)
                .setValue(BedBlock.PART, BedPart.FOOT);
        BlockState headState = footState.setValue(BedBlock.PART, BedPart.HEAD);
        blocks.put(foot.immutable(), footState);
        blocks.put(foot.relative(facing).immutable(), headState);
    }

    private static BlockPos pos(StarterLayouts.Layout l, List<String> entry) {
        if (entry == null || entry.size() < 2) {
            return null;
        }
        try {
            int x = Integer.parseInt(entry.get(0));
            int z = Integer.parseInt(entry.get(1));
            // 与本体 StarterLayouts.pos 一致：布局坐标需加上店铺用地原点。
            var property = org.merlin204.wss.shop.CityProperties.find(l.id());
            if (property == null) {
                return null;
            }
            int[] bounds = property.bounds();
            return new BlockPos(bounds[0] + x, StarterLayouts.floor(l, x, z), bounds[2] + z);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Direction dir(String text) {
        return switch (text == null ? "" : text.toUpperCase()) {
            case "N" -> Direction.NORTH;
            case "S" -> Direction.SOUTH;
            case "E" -> Direction.EAST;
            case "W" -> Direction.WEST;
            default -> Direction.SOUTH;
        };
    }

    private static BlockState block(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.get(key).defaultBlockState();
    }

    private static BlockState horizontal(BlockState state, Direction facing) {
        return state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                : state;
    }

    // —— 初始化与准入 ——

    @Override
    public void initialize(MinecraftServer server, ShopData.Shop shop, RestaurantData.Restaurant state, CityShopData.Progress progress) {
        // 本体签约时把所有新店的营业时段设为 0（RestaurantData.Restaurant.hours = 0，即永不营业）：
        // r.open 恒为 false → 市民调度（streetOrigins/readyToReceive 要求 receiving）与
        // 特殊房客预约（offerWhenOpen 要求 open）全部被拦，表现为“NPC 与特殊顾客都不进店”。
        // 本体的开店引导只教玩家给第一家店设置营业时段，附属店型没有该提示。
        // 小旅馆按行业惯例默认全天营业（掩码 7 = 三个时段全开），玩家仍可在店铺管理界面修改。
        if (state.hours == null || state.hours == 0) {
            state.hours = 7;
            RestaurantData.get(server).setDirty();
        }
    }

    /** P2-D：小旅馆只提供自家的旅游目的地（温泉疗养地），其余不开放。 */
    @Override
    public org.merlin204.wss.shop.JourneyCatalog.Destination journey(org.merlin204.wss.shop.JourneyCatalog.Destination destination) {
        return destination != null && HotelDestinations.ID.equals(destination.id()) ? destination : null;
    }

    @Override
    public boolean ingredient(String item) {
        return false;
    }

    @Override
    public boolean furniture(String id, FurnitureCatalog.Entry entry) {
        // 自家物件（床对应的原版床方块在 BED_BLOCKS 配置里）一律放行；
        // 货架用本体便利店货架（与便利店同款，类别 work）。
        if (id != null && (HotelConfig.BED_BLOCKS.get().contains(id)
                || id.equals("wonder_shop_story_core:retail_shelf")
                || id.equals("wss_hotel:drink_machine"))) {
            return true;
        }
        String category = entry.category();
        return !("work".equals(category) || "materials".equals(category));
    }

    @Override
    public List<String> benefits(FurnitureCatalog.Entry entry) {
        return List.of(FurnitureCatalog.contribution(entry));
    }
}
