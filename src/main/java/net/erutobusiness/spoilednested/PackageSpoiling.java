package net.erutobusiness.spoilednested;

import com.mrbysco.spoiled.recipe.SpoilRecipe;
import com.mrbysco.spoiled.util.SpoilHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;

/**
 * Contact（郵便MOD）の小包・手紙・ぽち袋の中身を腐らせる。
 *
 * <p><b>なぜ要るのか</b>: Spoiled も当部の {@link NestedSpoilHandler} も、入れ物の中へ潜る口は
 * {@code stack.getCapability(ForgeCapabilities.ITEM_HANDLER)} <b>ひとつだけ</b>。
 * ところが Contact の小包は capability を持たず、中身を<b>素の NBT</b>（{@code parcel} という
 * 一覧）へ直接書く（{@code ParcelItem extends NormalItem extends Item} で
 * {@code initCapabilities} を上書きしていない）。だから<b>どちらの目にも映らない</b>。
 *
 * <p>⚠ <b>止まるのは「送るとき」だけではない</b>。包装紙で包んだ小包は手元に置けて、
 * 開ければ中身が戻る。つまり<b>紙5枚と糸4本で、4スタック分の携帯氷室</b>になっていた。
 * 当部の {@code cold_storage} は氷室でも最良 0.1 倍なので、<b>紙5枚が氷室に勝つ</b>。
 *
 * <p>⚠ <b>Contact への依存は持たない。</b> アイテムの登録IDの文字列で見る。
 * Contact が入っていなければ、この経路は一度も真にならないだけ。
 *
 * <p>⚠⚠ <b>覆っていない範囲が2つある</b>（どちらも Contact の内部データで、
 * 覗くには依存が要る）:
 * <ul>
 *   <li><b>配達待ちの郵便</b> … サーバ側の {@code mailList} に在り、どの入れ物にも入っていない</li>
 *   <li><b>郵便受けの中身</b> … {@code PlayerMailboxData} が UUID ごとに持つ（24枠）</li>
 * </ul>
 * ⚠ どちらも「そこへ入れっぱなしにすれば保存になる」ので、⚠ <b>塞ぐなら Contact への依存が要る</b>。
 */
public final class PackageSpoiling {

    /**
     * 中身を持つ Contact のアイテム。
     * ⚠ <b>包んだ物はどれも {@code parcel} という同じ NBT キーに入る</b>
     * （{@code PackageScreenHandler.removed()} が全部そこへ書く）。
     */
    private static final Set<String> PACKAGES = Set.of(
        "contact:parcel",
        "contact:ender_parcel",
        "contact:letter",
        "contact:red_packet"
    );

    private static final String TAG = "parcel";

    /**
     * ⚠ 小包が4枠で、手紙とぽち袋は1枠。<b>一番大きい方に合わせて読む</b>。
     * {@code SimpleContainer.fromTag} は各要素の {@code Slot} を見て入れるので、
     * 1枠の物を4枠で読んでも余りは空のまま。書き戻す {@code createTag()} は
     * <b>空の枠を書かない</b>ので、中身の形は変わらない。
     */
    private static final int MAX_SLOTS = 4;

    /** config の倍率を引くための名前。{@code containerModifier} に1行書けば速さを変えられる。 */
    public static final ResourceLocation MODIFIER_ID = new ResourceLocation("contact", "parcel");

    private PackageSpoiling() {
    }

    /** そのアイテムが Contact の「中身を持つ包み」か。 */
    public static boolean isPackage(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && PACKAGES.contains(id.toString());
    }

    /**
     * 包みの中身を1刻ぶん進める。中身に触ったら true。
     *
     * <p>⚠ 呼ぶのは Spoiled 本体と同じ tick だけ（{@link NestedSpoilHandler} が判定する）。
     * 独自の間隔で回すと同じアイテムが二重に進む。
     */
    public static boolean spoilContents(Level level, ItemStack pkg) {
        CompoundTag tag = pkg.getTag();
        if (tag == null || !tag.contains(TAG, Tag.TAG_LIST)) {
            return false;                       // まだ何も包んでいない包み
        }
        SimpleContainer contents = new SimpleContainer(MAX_SLOTS);
        contents.fromTag(tag.getList(TAG, Tag.TAG_COMPOUND));

        boolean touched = false;
        for (int i = 0; i < contents.getContainerSize(); i++) {
            ItemStack stack = contents.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            // ⚠ 包みの中に入れ物が入っていることがある（弁当箱など）。
            //    そちらは capability を持つので NestedSpoilHandler の経路で扱えるが、
            //    ⚠ **包みの中に在るあいだは誰も見ていない**ので、ここで1段だけ潜る。
            SpoilRecipe recipe = SpoilHelper.getSpoilRecipe(level, stack);
            if (recipe == null) {
                continue;                       // 食べ物ではない
            }
            SpoilHelper.updateSpoilingStack(stack, recipe);
            touched = true;
            if (SpoilHelper.isSpoiled(stack)) {
                ItemStack spoiled = recipe.getResultItem(level.registryAccess()).copy();
                int count = stack.getCount();
                if (spoiled.isEmpty()) {
                    contents.setItem(i, ItemStack.EMPTY);
                } else {
                    spoiled.setCount(count);
                    contents.setItem(i, spoiled);
                }
            }
        }
        if (touched) {
            tag.put(TAG, contents.createTag());
        }
        return touched;
    }
}
