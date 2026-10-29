package wsshotel.hotel;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * 旅馆饮料机：立式三连方块（lower/middle/upper），贴图引用 yuushya 贩卖机。
 * 房客退房前顺路购买饮品（识别与购买逻辑在 HotelBusiness / HotelRooms）。
 */
public class DrinkMachineBlock extends Block {

    public enum Half implements StringRepresentable {
        LOWER("lower"),
        MIDDLE("middle"),
        UPPER("upper");

        private final String name;

        Half(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Half> HALF = EnumProperty.create("half", Half.class);

    private static final Map<Half, VoxelShape> SHAPES = new EnumMap<>(Half.class);

    static {
        SHAPES.put(Half.LOWER, Block.box(1, 0, 1, 15, 16, 15));
        SHAPES.put(Half.MIDDLE, Block.box(1, 0, 1, 15, 16, 15));
        SHAPES.put(Half.UPPER, Block.box(1, 0, 1, 15, 16, 15));
    }

    public DrinkMachineBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(HALF, Half.LOWER));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(DrinkMachineBlock::new);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection())
                .setValue(HALF, Half.LOWER);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.getOrDefault(state.getValue(HALF), SHAPES.get(Half.LOWER));
    }

    /** 放置下格后自动补上中、上两格（贴图连续的立式机身）。 */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer,
                            net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (state.getValue(HALF) == Half.LOWER) {
            level.setBlock(pos.above(), state.setValue(HALF, Half.MIDDLE), 3);
            level.setBlock(pos.above().above(), state.setValue(HALF, Half.UPPER), 3);
        }
    }

    /** 缺少相邻段（被破坏/阻挡）时整机变为空气，避免悬空残块。 */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        Half half = state.getValue(HALF);
        BlockPos above = pos.above();
        BlockPos below = pos.below();
        boolean broken = switch (half) {
            case LOWER -> direction == Direction.UP && !isHalf(level, above, Half.MIDDLE);
            case MIDDLE -> (direction == Direction.DOWN && !isHalf(level, below, Half.LOWER))
                    || (direction == Direction.UP && !isHalf(level, above, Half.UPPER));
            case UPPER -> direction == Direction.DOWN && !isHalf(level, below, Half.MIDDLE);
        };
        return broken ? Blocks.AIR.defaultBlockState() : state;
    }

    private static boolean isHalf(LevelAccessor level, BlockPos pos, Half half) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof DrinkMachineBlock && state.getValue(HALF) == half;
    }
}
