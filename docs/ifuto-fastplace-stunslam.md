# Auto Stun Slam 設計メモ

対象: `ifuto-fastplace` 0.6.0 / Minecraft 1.21.11 (Fabric)

メイスを盾に吸われないために、**叩く直前に斧へ持ち替えて盾を割り、すぐメイスに戻して叩く**。

## なぜ斧を挟むのか

- 盾を構えている相手は、正面からのメイス攻撃を**盾で受ける**（＝スタンも大ダメージも入らない）
- 斧の攻撃は**盾を 5 秒間使えなくする**（バニラの仕様）
- したがって「斧 → メイス」の順で同一の相手を叩けば、メイスのスマッシュが素通りする

## 発動条件

すべて満たすときだけ横取りする（`ClientPlayerInteractionManager#attackEntity` の HEAD）。

| 条件 | 判定 |
| --- | --- |
| 手にメイス | `getMainHandStack().isOf(Items.MACE)` |
| ホットバーに斧 | 木〜ネザライトの斧（ネザライト優先） |
| 浮遊中 | `!isOnGround()` |
| 落下距離 1 以上 | `fallDistance >= 1.0` |
| 相手が盾を構えている | `isUsingItem() && getActiveItem().isOf(Items.SHIELD)` |
| 届く | 目から 3.4 ブロック以内 |
| 遮蔽なし | `AimAssistModule.clearLine` |

条件を満たすと、そのクリックの**メイス攻撃は取り消され**、次の順で実行する。

```
[クリック] → 斧スロットへ変更 →（stunSlamAxeDelayMs 待つ）→ 斧で attackEntity
          → メイスへ戻す →（stunSlamMaceDelayMs 待つ）→ メイスで attackEntity
```

## 待ち時間が要る理由

スロット変更は `ClientPlayerEntity#syncSelectedSlot` が **tick の頭**で
`UpdateSelectedSlotC2SPacket` として送る。同じ tick の中でスロットを変えて殴ると、
サーバーは**まだ前のアイテム**で攻撃を処理してしまう。だから斧とメイスの間には
最低 1 tick（既定 50ms 以上、UI では 50〜400ms）空ける。

## やらないこと

- パケットを組み立てない（`setSelectedSlot` と `attackEntity` だけ）
- 斧で殴れなかった（相手が逃げた・遮蔽された）ときは、メイスを振らずに終わる
  （盾が割れていないのにメイスを無駄打ちしない）
- メイス以外の武器では何もしない（`interceptAttack` が false を返すだけ）

## 設定

| 項目 | 既定 | 意味 |
| --- | --- | --- |
| 斧に替えてから | 60 ms | 斧スロットに変わってから殴るまで（50〜400） |
| メイスに戻してから | 70 ms | メイスに戻ってから殴るまで（50〜400） |
| 元のスロットに戻す | ON | 途中で止まったときに元のスロットへ戻す |
