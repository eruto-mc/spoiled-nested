package net.erutobusiness.spoilednested;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

/**
 * バンドルの中身を腐らせる。
 *
 * <p><b>なぜ要るのか</b>: {@link PackageSpoiling} とまったく同じ穴が、もう1か所空いていた。
 * Spoiled も当部の {@link NestedSpoilHandler} も、入れ物の中へ潜る口は
 * {@code stack.getCapability(ForgeCapabilities.ITEM_HANDLER)} <b>ひとつだけ</b>。
 * ところがバンドルはバニラの {@code BundleItem} そのままで、中身を<b>素の NBT</b>
 * （{@code Items} という一覧）へ直接書き、capability を一切持たない。
 * だから<b>どちらの目にも映らない</b>。
 *
 * <p>⚠ <b>360本の jar を走査して確かめた</b>（2026-09-08）。バンドルに入れ物の口を付けている
 * MOD は1本も無い。つまりバンドルは<b>作った時点で永久保存の入れ物</b>だった。
 * 当部の {@code cold_storage} は氷室でも最良 0.1 倍なので、<b>糸2本とウサギの皮1枚が氷室に勝つ</b>。
 *
 * <p>⚠ <b>17個ある。</b> 無地の {@code minecraft:bundle} に加え、Vanilla Backport が
 * 16色を登録している。⚠ <b>どれもバニラの {@code BundleItem} そのもの</b>
 * （{@code ModItems} が {@code world/item/BundleItem} を参照していることを確かめた）なので、
 * 登録IDを17個並べずに {@code instanceof} 1つで捕まえる。
 * ⚠ 色付きだけ取りこぼすと、そこが抜け道として残る。
 *
 * <p><b>なぜ本体の入口へ渡すのか</b>: {@link PackageSpoiling} は
 * {@code SpoilHelper} を直に呼んでいて、⚠ <b>{@code SpoilHandler.spoilItemInHandler} を通らない</b>。
 * そのままコピーすると、入れ物ごとの倍率（{@code itemContainerModifier}）も、
 * 入れ物に付けた付与も効かない。こちらは中身を<b>入れ物の口の形に包んで</b>渡すので、
 * 本体が持っている判定がそのまま全部効く。
 */
public final class BundleSpoiling {

    /** バニラの {@code BundleItem} が中身を書くキー。 */
    private static final String TAG_ITEMS = "Items";

    private BundleSpoiling() {
    }

    /** 中身を進める処理。{@link NestedSpoilHandler#spoilInside} を渡す。 */
    @FunctionalInterface
    public interface Spoiler {
        void run(ItemStack container, IItemHandler contents);
    }

    /** そのアイテムがバンドルか（無地・16色のどれでも）。 */
    public static boolean isBundle(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BundleItem;
    }

    /**
     * バンドルの中身を1刻ぶん進める。中身に触ったら true。
     *
     * <p>⚠ 呼ぶのは Spoiled 本体と同じ tick だけ（{@link NestedSpoilHandler} が判定する）。
     * 独自の間隔で回すと同じアイテムが二重に進む。
     *
     * <p>⚠ <b>倍率の判定はここに書かない。</b> {@code spoilItemInHandler} が
     * 入れ物の登録ID（{@code minecraft:bundle} や {@code minecraft:red_bundle}）で
     * {@code itemContainerModifier} を引くので、config に1行書けば色ごとに変えられる。
     */
    public static boolean spoilContents(ItemStack bundle, Spoiler spoiler) {
        CompoundTag tag = bundle.getTag();
        if (tag == null || !tag.contains(TAG_ITEMS, Tag.TAG_LIST)) {
            return false;                       // 空のバンドル
        }
        ListTag items = tag.getList(TAG_ITEMS, Tag.TAG_COMPOUND);
        if (items.isEmpty()) {
            return false;
        }

        // ⚠ **バンドルの一覧には `Slot` が無い**（並び順がそのまま位置）。
        //    ItemStackHandler は `Slot` 付きの形で読み書きするので、
        //    `deserializeNBT` は使わず1件ずつ入れる。
        ItemStackHandler contents = new ItemStackHandler(items.size());
        ItemStack[] before = new ItemStack[items.size()];
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = ItemStack.of(items.getCompound(i));
            contents.setStackInSlot(i, stack);
            before[i] = stack.copy();
        }

        spoiler.run(bundle, contents);

        // ⚠ **変わっていなければ書かない。** 腐敗の進み具合は中身の NBT に書かれるので、
        //    触れていないバンドルまで毎回書き直すと、持ち物の同期が無駄に走る。
        boolean touched = false;
        for (int i = 0; i < before.length; i++) {
            if (!ItemStack.matches(before[i], contents.getStackInSlot(i))) {
                touched = true;
                break;
            }
        }
        if (!touched) {
            return false;
        }

        ListTag written = new ListTag();
        for (int i = 0; i < contents.getSlots(); i++) {
            ItemStack stack = contents.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;                       // 腐って消えたぶんは並びから外れる
            }
            written.add(stack.save(new CompoundTag()));
        }
        if (written.isEmpty()) {
            // ⚠ 中身が全部消えたら**キーごと消す**。空の一覧を残すと、
            //    バニラの `BundleItem` が「中身あり」と見て満杯の帯を描く
            tag.remove(TAG_ITEMS);
        } else {
            tag.put(TAG_ITEMS, written);
        }
        return true;
    }
}
