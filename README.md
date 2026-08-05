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

⚠ **エンダーチェスト側は未実測。** 検証サーバにはプレイヤーが居らず
`PlayerTickEvent` が飛ばないため。確かめるならクライアント台本で
「エンダーチェストに食料を入れる → 待つ → ツールチップを見る」を回す。

## 上流

入れ子の件は Spoiled 側の不整合なので、上流が3経路をそろえたら本MODの1番目は不要になる。
外しても害はない。
