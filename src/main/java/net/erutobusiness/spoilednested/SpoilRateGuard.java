package net.erutobusiness.spoilednested;

import com.mrbysco.spoiled.config.SpoiledConfig;
import com.mrbysco.spoiled.config.SpoiledConfigCache;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Spoiled 本体の「腐敗の間隔」が 0 のまま世界が動いたら、設定の値を入れ直す（0 で割ってサーバが落ちるのを防ぐ）。
 *
 * <p><b>なぜ要るのか</b>: Spoiled 2.2.x の {@code SpoilHandler.onWorldTick} は、毎 tick
 * {@code level.getGameTime() % SpoiledConfigCache.spoilRate} を計算する。{@code spoilRate} は
 * {@code long} の static で<b>初めは 0</b>、COMMON の設定を読んだとき（{@code SpoiledConfig.onLoad} →
 * {@code setSpoilRate}）にだけ入る。設定の値は 1 未満にできない（{@code defineInRange(…, 30, 1, …)}）ので、
 * 0 になるのは「設定を読む前に世界が動いた」ときだけ。そのとき {@code ArithmeticException: / by zero} で
 * サーバが落ちる（2026-09-28 に 1 回起きた。同じ構成の起動 5 回のうち 1 回で、なぜ順番が逆になったかは分かっていない）。
 *
 * <p><b>作り</b>: Spoiled の処理（優先度 HIGH）より先に動くよう、優先度 HIGHEST で同じ tick を見る。
 * 値が 1 以上なら何もしない（毎 tick の読むだけ 1 回）。0 以下なら設定の値を入れ直し、警告を 1 行出す。
 * 設定がまだ読めない（{@code IllegalStateException}）ときは、設定の既定値を入れる。
 */
public class SpoilRateGuard {

    private static final Logger LOG = LogManager.getLogger(SpoiledNested.MOD_ID);

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) {
            return;
        }
        if (SpoiledConfigCache.spoilRate > 0) {
            return;
        }
        int seconds;
        String from;
        try {
            seconds = SpoiledConfig.COMMON.spoilRate.get();
            from = "設定の値";
        } catch (RuntimeException e) {
            seconds = SpoiledConfig.COMMON.spoilRate.getDefault();
            from = "設定がまだ読めないので既定値（" + e.getClass().getSimpleName() + "）";
        }
        SpoiledConfigCache.setSpoilRate(seconds);
        LOG.warn("⚠ Spoiled の腐敗の間隔が 0 のまま世界が動いた（{} の gameTime {}）。{} {} 秒を入れ直した（そのままだと 0 で割って落ちる）",
            event.level.dimension().location(), event.level.getGameTime(), from, seconds);
    }
}
