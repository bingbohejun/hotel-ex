package wsshotel.hotel;

import net.minecraft.server.MinecraftServer;
import org.merlin204.wss.environment.TermDate;
import org.merlin204.wss.environment.WorldCalendarData;
import wsshotel.HotelApi;
import wsshotel.HotelConfig;

/**
 * F7 节气淡旺季：接本体 24 节气历法（TermDate，term 0=立春）。
 * 每季节（6 节气）一组客流倍率与房价系数，全部可配置。
 */
public final class HotelSeasons {

    private static final String[] SEASON_LABELS = {"春 · 旺季", "夏 · 淡季", "秋 · 平季", "冬 · 旺季"};

    private HotelSeasons() {
    }

    /** 当前节气日期；异常时回退第 0 天（立春）。 */
    public static TermDate current(MinecraftServer server) {
        try {
            return WorldCalendarData.get(server.overworld()).date();
        } catch (RuntimeException e) {
            HotelApi.LOG.warn("读取节气历法失败，按平季处理", e);
            return TermDate.ofDay(0);
        }
    }

    private static int seasonIndex(int term) {
        if (term < 0) {
            return -1;
        }
        int idx = Math.floorMod(term, 24) / 6;
        return idx >= 0 && idx < 4 ? idx : -1;
    }

    /** 季节客流倍率；term<0（未同步）或配置异常时返回 1.0。 */
    public static double traffic(int term) {
        int idx = seasonIndex(term);
        if (idx < 0) {
            return 1.0;
        }
        var table = HotelConfig.SEASON_TRAFFIC.get();
        return idx < table.size() ? table.get(idx) : 1.0;
    }

    /** 季节房价系数。 */
    public static double price(int term) {
        int idx = seasonIndex(term);
        if (idx < 0) {
            return 1.0;
        }
        var table = HotelConfig.SEASON_PRICE.get();
        return idx < table.size() ? table.get(idx) : 1.0;
    }

    public static String seasonLabel(int term) {
        int idx = seasonIndex(term);
        return idx < 0 ? "" : SEASON_LABELS[idx];
    }
}
