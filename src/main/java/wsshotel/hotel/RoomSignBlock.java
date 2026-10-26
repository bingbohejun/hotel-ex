package wsshotel.hotel;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * F1 客房门牌：挂墙薄板方块，四态（空闲/入住/待清洁/保洁中）由业务 tick 同步，
 * 无 BlockEntity——贴图变体即状态显示。放置时自动绑定最近的客房。
 */
public class RoomSignBlock extends Block {

    public enum SignState implements StringRepresentable {
        FREE("free"),
        OCCUPIED("occupied"),
        DIRTY("dirty"),
        CLEANING("cleaning");

        private final String name;

        SignState(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<SignState> SIGN_STATE = EnumProperty.create("room_state", SignState.class);

    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        SHAPES.put(Direction.NORTH, Block.box(2, 4, 0, 14, 12, 2));
        SHAPES.put(Direction.SOUTH, Block.box(2, 4, 14, 14, 12, 16));
        SHAPES.put(Direction.WEST, Block.box(0, 4, 2, 2, 12, 14));
        SHAPES.put(Direction.EAST, Block.box(14, 4, 2, 16, 12, 14));
    }

    public RoomSignBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(SIGN_STATE, SignState.FREE));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(RoomSignBlock::new);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SIGN_STATE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 牌面朝向放置者，板贴在身后一侧的墙上。
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(SIGN_STATE, SignState.FREE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.getOrDefault(state.getValue(FACING), SHAPES.get(Direction.NORTH));
    }
}
