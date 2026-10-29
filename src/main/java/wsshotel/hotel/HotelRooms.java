package wsshotel.hotel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import wsshotel.HotelConfig;

/**
 * 酒店客房设施：把店内成对的床（床头+床尾）扫描为“房间”，候位椅单独收集，
 * 以 ShopFacilities 扩展的形式挂到共享设施快照上，并提供床头索引供 O(1) 查询。
 */
public final class HotelRooms {

    /** 房型：0 标准 / 1 舒适 / 2 豪华。 */
    public static final int TIER_STANDARD = 0;
    public static final int TIER_COMFORT = 1;
    public static final int TIER_DELUXE = 2;

    /** 一间客房：床头锚点、床尾、床边站位、床方块 ID、房型档位。 */
    public record Room(BlockPos head, BlockPos foot, BlockPos approach, String bedId, int tier) {
        public double centerX() {
            return (head.getX() + foot.getX()) / 2.0 + 0.5;
        }

        public double centerZ() {
            return (head.getZ() + foot.getZ()) / 2.0 + 0.5;
        }

        public double centerY() {
            return head.getY();
        }
    }

    private final List<Room> rooms;
    private final Map<Long, Room> byHead;
    private final List<BlockPos> chairs;
    private final List<BlockPos> drinkCounters;

    public HotelRooms(List<Room> rooms, List<BlockPos> chairs) {
        this(rooms, chairs, List.of());
    }

    public HotelRooms(List<Room> rooms, List<BlockPos> chairs, List<BlockPos> drinkCounters) {
        this.rooms = List.copyOf(rooms);
        this.chairs = List.copyOf(chairs);
        this.drinkCounters = List.copyOf(drinkCounters);
        Map<Long, Room> index = new HashMap<>();
        for (Room room : this.rooms) {
            index.put(room.head().asLong(), room);
        }
        this.byHead = Map.copyOf(index);
    }

    public List<Room> rooms() {
        return rooms;
    }

    /** 床头索引查询（替代线性遍历）。 */
    public Room byHead(long head) {
        return byHead.get(head);
    }

    /** 候位椅坐标列表。 */
    public List<BlockPos> chairs() {
        return chairs;
    }

    /** F2 饮品柜台站位点列表。 */
    public List<BlockPos> drinkCounters() {
        return drinkCounters;
    }

    public static final HotelRooms EMPTY = new HotelRooms(List.of(), List.of());

    /** 按床方块 ID 判定房型档位。 */
    public static int tierOf(String bedId) {
        if (bedId != null && HotelConfig.TIER_DELUXE_BLOCKS.get().contains(bedId)) {
            return TIER_DELUXE;
        }
        if (bedId != null && HotelConfig.TIER_COMFORT_BLOCKS.get().contains(bedId)) {
            return TIER_COMFORT;
        }
        return TIER_STANDARD;
    }

    /** 收集器：每次设施扫描都应创建独立实例，不跨店铺共享。 */
    public static final class Collector {
        private final List<BlockPos> heads = new ArrayList<>();
        private final Set<Long> seen = new HashSet<>();
        private final List<BlockPos> chairs = new ArrayList<>();
        private final Set<Long> chairSeen = new HashSet<>();
        private final List<BlockPos> counters = new ArrayList<>();
        private final Set<Long> counterSeen = new HashSet<>();

        public void accept(BlockPos pos, BlockState state) {
            if (state.getBlock() instanceof BedBlock) {
                BlockPos head = state.getValue(BedBlock.PART) == BedPart.HEAD
                        ? pos.immutable()
                        : pos.relative(state.getValue(BedBlock.FACING));
                if (seen.add(head.asLong())) {
                    heads.add(head);
                }
                return;
            }
            // F2：饮品台（附属自有方块）。
            if (isDrinkCounter(state) && counterSeen.add(pos.asLong())) {
                counters.add(pos.immutable());
                return;
            }
            if (isWaitingChair(state) && chairSeen.add(pos.asLong())) {
                chairs.add(pos.immutable());
            }
        }

        public HotelRooms finish(ServerLevel level) {
            List<Room> out = new ArrayList<>();
            for (BlockPos head : heads) {
                BlockState headState = level.getBlockState(head);
                if (!(headState.getBlock() instanceof BedBlock)) {
                    continue;
                }
                Direction facing = headState.getValue(BedBlock.FACING);
                BlockPos foot = head.relative(facing.getOpposite());
                BlockPos approach = approach(level, head, foot);
                if (approach != null) {
                    String id = BuiltInRegistries.BLOCK.getKey(headState.getBlock()).toString();
                    out.add(new Room(head.immutable(), foot.immutable(), approach, id, tierOf(id)));
                }
            }
            List<BlockPos> seatSpots = new ArrayList<>();
            for (BlockPos chair : chairs) {
                BlockPos spot = approach(level, chair, chair);
                if (spot != null) {
                    seatSpots.add(spot);
                }
            }
            List<BlockPos> counterSpots = new ArrayList<>();
            for (BlockPos counter : counters) {
                BlockPos spot = approach(level, counter, counter);
                if (spot != null) {
                    counterSpots.add(spot);
                }
            }
            return new HotelRooms(out, seatSpots, counterSpots);
        }

        /** F2 饮品台识别：yuushya 立式贩卖机（pos=middle 挂实体模型）与自家旅馆饮料机（仅下格）。 */
        private static boolean isDrinkCounter(BlockState state) {
            net.minecraft.resources.ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            String path = key.getPath();
            if ("wss_hotel".equals(key.getNamespace()) && path.equals("drink_machine")) {
                return true;
            }
            if (!"yuushya".equals(key.getNamespace())) {
                return false;
            }
            if (!path.startsWith("vending_machine_") && !path.equals("cold_juice_dispenser")) {
                return false;
            }
            // 仅收集挂实体模型的 middle 格，避免 bottom/top/none 占位格重复。
            for (net.minecraft.world.level.block.state.properties.Property<?> prop : state.getProperties()) {
                if (prop.getName().equals("pos")) {
                    return "middle".equals(String.valueOf(state.getValue(prop)));
                }
            }
            return true;
        }

        /** 候位椅识别：配置列表优先，否则自动识别 ID 含 chair 的方块。 */
        public static boolean isWaitingChair(BlockState state) {
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (HotelConfig.WAITING_CHAIRS.get().contains(key.toString())) {
                return true;
            }
            if (!HotelConfig.WAITING_CHAIRS.get().isEmpty()) {
                return false;
            }
            return key.getPath().contains("chair");
        }

        /** 在方块四周找一个可站立的格位。 */
        private static BlockPos approach(ServerLevel level, BlockPos a, BlockPos b) {
            for (BlockPos anchor : List.of(a, b)) {
                for (Direction side : List.of(Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST)) {
                    BlockPos ground = anchor.relative(side);
                    for (int dy : new int[]{0, 1, -1}) {
                        BlockPos at = ground.offset(0, dy, 0);
                        if (standable(level, at)) {
                            return at.immutable();
                        }
                    }
                }
            }
            return null;
        }

        private static boolean standable(ServerLevel level, BlockPos at) {
            return level.getBlockState(at).getCollisionShape(level, at).isEmpty()
                    && level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty()
                    && !level.getBlockState(at.below()).getCollisionShape(level, at.below()).isEmpty();
        }
    }
}
