package wsshotel.hotel;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * 货物架设施扩展：店内扫描到的全部货物架位置（与 HotelRooms 同模式挂在 ShopFacilities 上）。
 * 使用时会过滤掉已被拆除的货架。
 */
public record HotelShelves(List<BlockPos> shelves) {

    public static final HotelShelves EMPTY = new HotelShelves(List.of());

    public HotelShelves {
        shelves = List.copyOf(shelves);
    }
}
