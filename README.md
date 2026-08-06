# Spoiled Nested Containers

[Spoiled](https://modrinth.com/mod/spoiled) の腐敗判定が届いていない2箇所を埋める。
Minecraft 1.20.1 / Forge。**サーバ側専用**（`side=SERVER`）。Spoiled 本体は改変しない。

## 何を直すか

### 1. 入れ子の容器 — チェストに入れたシュルカーの中身が腐らない

Spoiled の走査経路は3つあるが、**ブロック容器の経路だけが入れ子へ潜らない**。
2026-08-05 に 2.2.1 の bytecode で位置関係を確認した:

| 経路 | `ITEM_HANDLER` を取る位置 | 入れ子へ |
| - | - | - |
| `updateInventory`（プレイヤーの持ち物） | offset 43 / 57 ＝ スロット走査の**中** | **潜る** |
| `updateContainer`（トロッコ等のエンティティ） | offset 38 / 52 ＝ **中** | **潜る** |
| `onWorldTick`（ブロック容器） | offset 151 / 344 ＝ スロット走査(377-454)の**外** | **潜らない** |

3つのうち1つだけ挙動が違うので、設計ではなく書き漏らしに見える。実測でも:

| | `SpoilTimer`（同じ箱・同じ 75 秒） |
| - | - |
| チェスト直下のパン | 5 → **7** |
| 同じチェストに入れたシュルカーの中のパン | **5 のまま** |

⇒ 「拠点ではシュルカーをチェストに入れておけば永久保存」という抜け道になっていた。

### 2. エンダーチェスト

中身はブロックエンティティではなく `Player#getEnderChestInventory()` に紐づくため、
**Spoiled のどの経路からも見えない**。config に `minecraft:ender_chest` を書いても効かない。

## どう直すか

**Spoiled の public な入口を呼ぶだけ**。mixin は使わない。

- `ChunkHelper.getBlockEntityPositions()` で本体と同じ範囲を走査する
- 倍率は `SpoiledConfigCache.containerModifier` / `spoilItemInHandler` の
  `itemContainerModifier` をそのまま使う ⇒ **config の1行で調整できる**
- 腐敗の進行と置換は `SpoilHelper` / `SpoilHandler` に任せる

### ⚠ 気をつけたこと

- **本体と同じ tick でしか動かない**（`gameTime % spoilRate == 0`）。
  独自の間隔で回すと同じアイテムが1回の更新で二重に進む
- **入れ物であるスロットだけを見る**。中身が食料そのものの場合は本体が処理済みなので、
  触ると二重に進む
- **未開封のルートチェストに触らない**。中身を読むと戦利品がその場で生成されてしまう
  （本体も同じ理由で飛ばしている）
- **オーバーワールド限定**を本体にそろえる。片方だけ広げると
  「ネザーのチェストの中のシュルカーだけ腐る」ことになる
- 外側の器の倍率（冷蔵庫 0.01 等）と内側の倍率（シュルカー 0.5）が**両方掛かる**

## 確認

`dev/verify/scenarios/spoiled-newfoods.json` の `newfoods-nested-shulker`。
導入後、入れ子のパンが 5 → **7** に進むことを確認した（本体と同じ進み）。

エンダーチェスト側は `dev/verify/scenarios/spoiled-enderchest.json`。
**クライアント台本（`--server`）でしか測れない**——検証サーバは無人で
`PlayerTickEvent` が飛ばないため。

**実測（2026-08-06・台本 `spoiled-enderchest`）**: 同じ8回分の更新（240秒）で

| 置き場所 | 倍率 | 開始 | 8回後 |
| - | - | - | - |
| **エンダーチェスト** | **0.5** | `SpoilTimer:5` | **9**（8回中 **4回**進んだ） |
| 持ち物（**陽性対照**） | 等速 | `SpoilTimer:5` | **13**（8回**全部**進んだ） |

倍率 0.5 の期待値ちょうど。判定は `probe mark` で機械化してあり **OK / NG 0**。
⚠ **survival で行うこと**——Spoiled はクリエイティブのプレイヤーを丸ごと飛ばす。
⚠ **腐敗が進んだ状態から始めること**——まっさらだと `tag.isEmpty()` 分岐でタグが付かず区別できない。

### 3. 塩漬けを「完全停止」から「倍率」へ

Spoiled の `saltCompat` は boolean で、有効にすると塩漬けの食料は
`canSpoil` が false を返して**一切腐らなくなる**。当部の方針は
「**絶対に腐らない置き場は作らない**」（冷蔵庫ですら 0.01）なのに、
塩は海水から無限に採れるうえ元の食料のまま残るので、これだけ例外的に最強だった。

**作り**: `saltCompat = true` のままにして Spoiled には塩漬けを**完全に無視させる**。
そのうえで本MODが、無視された分を**低い倍率で**進める。二重に進まないのはこのため。

- 倍率は `containerModifier` の擬似キー **`spoiled:salted`**（既定 0.01）。
  ⚠ ブロックエンティティのIDではないが、**つまみを1か所にまとめる**ため同じ表を使っている
- ⚠ **塩の印を外した複製で腐敗レシピを引く**。本物のまま `getSpoilRecipe` を呼ぶと
  `canSpoil` が false を返して null になる（それが saltCompat の仕組みそのもの）
- ⚠ **腐敗タグは自分で撒く**。塩漬けは最初から NBT を持つので、本体の
  `updateSpoilingStack` は `tag.isEmpty()` の分岐に入れず永久に何もしない

**実測**（倍率を一時的に 1.0 にした陽性対照）: まっさらな塩漬けパンに
`SpoilTimer:1` が撒かれ、`SpoilTimer:5` から始めた塩漬けは 7 まで進んだ
（改定前は 5 のまま止まっていた）。本番の 0.01 では 5 のまま留まる。

## 上流

入れ子の件は Spoiled 側の不整合なので、上流が3経路をそろえたら本MODの1番目は不要になる。
外しても害はない。
