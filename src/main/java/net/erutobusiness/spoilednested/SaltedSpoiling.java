package net.erutobusiness.spoilednested;

import com.mrbysco.spoiled.config.SpoiledConfigCache;
import com.mrbysco.spoiled.recipe.SpoilRecipe;
import com.mrbysco.spoiled.util.SpoilHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 塩漬けにした食料を「完全に止める」のではなく「極めて遅らせる」。
 *
 * <p><b>なぜ要るのか</b>: Spoiled の {@code saltCompat} は boolean で、有効にすると
 * 塩漬けの食料は {@code canSpoil} が false を返して<b>一切腐らなくなる</b>。
 * 当部の方針は「<b>絶対に腐らない置き場は作らない</b>」（冷蔵庫ですら 0.01）なのに、
 * 塩は海水から無限に採れるうえ元の食料のまま残るので、これだけが例外的に最強になっていた。
 *
 * <p><b>作り</b>: {@code saltCompat = true} のままにして Spoiled には塩漬けを
 * <b>完全に無視させる</b>。そのうえで本クラスが、無視された分を<b>低い倍率で</b>進める。
 * ⚠ 二重に進まないのはこのため——本体が触らないものだけを触る。
 *
 * <p><b>倍率の決め方</b>: {@code containerModifier} の擬似キー
 * <b>{@code spoiled:salted}</b> を読む（書いていなければ {@link #DEFAULT_RATE}）。
 * ⚠ ブロックエンティティのIDではないが、<b>倍率のつまみを1か所にまとめる</b>ために
 * 同じ表を使っている。config 側にもその旨のコメントを置いてある。
 */
final class SaltedSpoiling {

    /** Salt（mortuusars 製）が塩漬けの印として書く NBT キー。 */
    private static final String SALTED = "Salted";

    /** `containerModifier` に書いていないときの倍率。冷蔵庫と同じ「ほぼ止まる」。 */
    static final double DEFAULT_RATE = 0.01D;

    private static final ResourceLocation KEY =
        new ResourceLocation("spoiled", "salted");

    private SaltedSpoiling() {
    }

    static boolean isSalted(ItemStack stack) {
        return stack.hasTag() && stack.getTag() != null && stack.getTag().contains(SALTED);
    }

    private static double rate() {
        if (SpoiledConfigCache.containerModifier != null
            && SpoiledConfigCache.containerModifier.containsKey(KEY)) {
            return SpoiledConfigCache.containerModifier.get(KEY);
        }
        return DEFAULT_RATE;
    }

    /**
     * 塩漬けの1スタックを、低い倍率で1回ぶん進める。腐りきったかどうかを返す。
     *
     * <p>⚠ <b>塩の印を外した複製で腐敗レシピを引く。</b> 本物のまま
     * {@code getSpoilRecipe} を呼ぶと {@code canSpoil} が false を返して null になる
     * （それが saltCompat の仕組みそのもの）。複製を使うことで、
     * <b>Spoiled の中身に手を入れずに</b>「その食料の腐敗レシピ」だけを取り出せる。
     */
    static boolean tick(Level level, ItemStack stack) {
        if (!isSalted(stack)) {
            return false;
        }
        double r = rate();
        if (r <= 0) {
            return false;   // 0 と書かれたら従来どおり完全に止める
        }
        if (r != 1.0 && level.random.nextDouble() > r) {
            return false;   // この回は進めない
        }

        ItemStack probe = stack.copy();
        CompoundTag probeTag = probe.getTag();
        if (probeTag != null) {
            probeTag.remove(SALTED);
            if (probeTag.isEmpty()) {
                probe.setTag(null);
            }
        }
        SpoilRecipe recipe = SpoilHelper.getSpoilRecipe(level, probe);
        if (recipe == null) {
            return false;
        }

        // ⚠ **塩漬けの食料は最初から NBT を持つ**ので、本体の updateSpoilingStack は
        //    `tag.isEmpty()` の分岐に入れず、腐敗タグが1つも無いと何もしないまま終わる。
        //    種を撒くのはこちらの役目。
        CompoundTag tag = stack.getOrCreateTag();
        if (!tag.contains("spoiled:SpoilTimer") || !tag.contains("spoiled:SpoilMaxTime")) {
            tag.putInt("spoiled:SpoilTimer", 0);
            tag.putInt("spoiled:SpoilMaxTime", recipe.getSpoilTime());
            stack.setTag(tag);
            return false;   // 種を撒いた回は進めない（本体も初回は同じ動き）
        }

        SpoilHelper.updateSpoilingStack(stack, recipe);
        return SpoilHelper.isSpoiled(stack);
    }

    /** 腐りきった塩漬けを、レシピの結果へ置き換えるための取得口。 */
    static SpoilRecipe recipeFor(Level level, ItemStack stack) {
        ItemStack probe = stack.copy();
        CompoundTag probeTag = probe.getTag();
        if (probeTag != null) {
            probeTag.remove(SALTED);
            if (probeTag.isEmpty()) {
                probe.setTag(null);
            }
        }
        return SpoilHelper.getSpoilRecipe(level, probe);
    }
}
