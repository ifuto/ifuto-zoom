# FastGenerate

地形生成データパックの「生成が遅い」を、**先回りして作っておく**ことで消す Mod です。
Fabric / Minecraft 1.21.11。

```
/fastgenerate stats   状態を見る
/fastgenerate on|off  切り替え
```

## 何をするか

- プレイヤーの**進行方向**を予測して、ビューディスタンスの外側リング
  （既定 +6 チャンク）を先に生成しておく
- 同時に走らせる生成の本数を、サーバーの tick 時間とフレーム時間を見ながら
  **AIMD で自動調整**（弱い CPU では控えめ、強い CPU ではコアを使い切る）
- 起動時にワーカースレッド数を CPU に合わせる（4 コア以下は全コア、8 コア以上は 1 本あける）
- 遠ざかったチャンクのチケットは解放（メモリを食い続けない）

生成結果は**バニラと完全に同一**です。生成の中身は一切いじりません。

## 効果の出方

スループットは Little の法則 `L / W`（同時実行数 ÷ 1 個あたりの所要時間）。
バニラは L が小さいので、重いデータパックではコアが遊びます。
この Mod は L を開き、さらに**到着前に作り終える**ので、待ち時間として見えません。

`/fastgenerate stats` で `window`（同時実行数）と `done`（完了数）が増えていれば効いています。

## 設定

`config/ifuto-fastgenerate.json`

| キー | 既定 | 意味 |
| --- | --- | --- |
| `leadChunks` | 6 | 先読みするリングの幅（チャンク） |
| `directionBias` | 1.0 | 進行方向の優先度（0 で同心円） |
| `minWindow` / `maxWindow` | 2 / 12 | 同時生成数の範囲 |
| `issuePerTick` | 2 | 1 tick に発行する要求数 |
| `targetMspt` | 45 | これを超えたら絞る [ms] |
| `targetFrameMs` | 30 | フレームがこれを超えたら絞る [ms] |
| `tuneThreads` | true | 起動時のスレッド数調整 |

## ビルド

```bash
./gradlew -p fastgenerate build
./gradlew -p fastgenerate testFastgenerate   # 制御・優先度・Little の法則の自己テスト
```

## 設計

[`docs/ifuto-fastgenerate.md`](../docs/ifuto-fastgenerate.md) に、
ボトルネックの分解・式・数値検証・参考文献（Amdahl / Little / Jacobson / Joseph &
Grunwald ほか）をまとめてあります。

## 注意

- サーバー側の最適化です（シングルプレイの統合サーバー、または自分のサーバー）。
- 実機での速度計測はまだです。数字は `/fastgenerate stats` で確認してください。
- 先読みしたぶんメモリ使用量は増えます（`maxTickets` で上限）。
