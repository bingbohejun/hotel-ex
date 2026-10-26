package wsshotel.hotel;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;

/** F2 大堂饮品台：房客退房前顺路购买饮品的柜台（购买逻辑在 HotelBusiness.checkout）。 */
public class DrinkCounterBlock extends Block {

    public DrinkCounterBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(DrinkCounterBlock::new);
    }
}
