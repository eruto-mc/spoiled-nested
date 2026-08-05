package net.erutobusiness.spoilednested;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

/**
 * Spoiled の腐敗判定が届いていない2箇所を埋める。Spoiled 本体は改変しない。
 *
 * <p>1. <b>入れ子の容器</b>（チェストに入れたシュルカーボックス等）。
 * Spoiled の走査経路は3つあるが、<b>ブロック容器の経路だけが入れ子へ潜らない</b>。
 * 2026-08-05 に bytecode で確認した位置関係:
 * <ul>
 *   <li>{@code updateInventory}（持ち物）… ITEM_HANDLER を offset 43/57＝スロット走査の<b>中</b>で取る＝潜る</li>
 *   <li>{@code updateContainer}（トロッコ等）… offset 38/52＝<b>中</b>＝潜る</li>
 *   <li>{@code onWorldTick}（ブロック容器）… offset 151/344＝スロット走査(377-454)の<b>外</b>＝<b>潜らない</b></li>
 * </ul>
 * そのため「チェストにシュルカーを入れておけば中身は一切腐らない」抜け道があった
 * （同じ75秒でチェスト直下のパンは SpoilTimer 5→7、シュルカー内は 5 のまま。実測ずみ）。
 *
 * <p>2. <b>エンダーチェスト</b>。中身はブロックエンティティではなく
 * {@code Player#getEnderChestInventory()} に紐づくため、Spoiled のどの経路からも見えない。
 *
 * <p><b>倍率は Spoiled の config をそのまま読む</b>ので、当部の設定
 * （{@code containerModifier} / {@code itemContainerModifier}）の1行で調整できる。
 */
@Mod(SpoiledNested.MOD_ID)
public class SpoiledNested {
    public static final String MOD_ID = "spoiled_nested";

    public SpoiledNested() {
        MinecraftForge.EVENT_BUS.register(new NestedSpoilHandler());
    }
}
