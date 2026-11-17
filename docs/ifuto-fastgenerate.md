# FastGenerate 設計メモ

対象: `fastgenerate` 0.1.0 / Minecraft 1.21.11 (Fabric)

地形生成データパック（ノイズ設定・バイオーム・feature を差し替えるもの）を使うと
生成が極端に遅くなる。この Mod は **「生成そのものを速くする」のではなく、
「まだ間に合ううちに作っておく」** ことで待ち時間を消す。

弱い CPU / 内蔵 GPU のノート PC でも壊れないように、並列度は毎 tick の
実測（サーバーの tick 時間とフレーム時間）で AIMD 制御する。強い CPU なら
ウィンドウが開いてコアを使い切る。

---

## 1. なぜデータパックだと遅いのか

バニラの生成パイプラインは `ChunkStatus` の段（structure → noise → surface →
carvers → features → light → full）をワーカープールで回し、メインスレッドが
仕上げ（ライティング確定・チャンク追加・パケット送信）を担当する。

データパックはこのうち **ノイズ段**（density function の木）と **feature 段**
（木・鉱石・カスタム feature）を重くする。しかもバニラの先読みは
「プレイヤーのチケット半径の外側を少しずつ」なので、

- 同時に走る生成の本数（L）が小さい
- 1 本あたりの所要時間（W）がデータパックで伸びる

ため、スループット `= L / W`（Little の法則）がそのまま落ちる。
**プレイヤーが到着した瞬間に生成が始まる**ので、待ち時間として見える。

---

## 2. 設計

### 2.1 先読みリング（prediction）

プレイヤーの位置と 1 tick の移動量から「これから踏む順」を計算し、
ビューディスタンスの外側リング（`leadChunks`）を先に生成する。

優先度は到着時刻の近似:

```
urgency = 距離 − cos(進行方向とのなす角) × directionBias × radius/2
          （背後はさらに +2）
```

これは最短処理時間順（Smith 1956 / Schrage 1968）を「到着予測」に置き換えたもので、
平均待ち時間を最小化する。移動方向を使うのはストライド・プリフェッチ
（Smith 1982 / Joseph &amp; Grunwald 1997）と同じ考え方。

内側（`viewDistance` 以内）はバニラのプレイヤーチケットに任せる。二重に
チケットを張ると、チケット管理そのものが重くなるため。

### 2.2 ウィンドウ（Little の法則）

同時に走らせる生成の本数を `window` として、`window / W` のスループットを狙う。
シミュレーション（`testFastgenerate`）では W=4 tick のとき:

| window | スループット [chunk/tick] |
| --- | --- |
| 2 | 0.50 |
| 4 | 1.00 |
| 8 | 2.00 |

つまり **ウィンドウを開くことが、そのまま生成速度**。ただし開けすぎると
1 tick が伸びるので、次の制御で上限を動かす。

### 2.3 AIMD 制御

```
目標超過 (mspt > targetMspt または frame > targetFrameMs):
    window ← max(minWindow, floor(window × 0.75))
余裕あり (mspt < 0.70×target かつ frame < 0.70×target):
    window ← min(maxWindow, window + 1)
変更後は HOLD_TICKS (10 tick) 様子を見る
```

加算増加・乗算減少は TCP の輻輳制御（Jacobson 1988）と同じ形で、
**速く引き締め・ゆっくり広げる**ので、弱い環境ではカクつかず、強い環境では
上限まで開く。計測は時定数 300ms の EWMA（1 回の GC スパイクで落とさない）。

フレーム時間も見るのは、内蔵 GPU のノート PC では「生成で CPU を使い切ると
描画が詰まる」ため。統合サーバーでは両者が同じ CPU を奪い合う。

### 2.4 スレッド数（preLaunch）

バニラのワーカープールは `max(1, コア数 − 1)`。2〜4 コア機では 1〜3 本しか
立たない。`max.bg.threads` を起動前に設定して:

- 4 コア以下 → **全コア**（2 コア機なら 2 倍）
- 5 コア以上 → コア数 − 1（メインスレッド + 描画用に 1 本あける）
- 16 本で頭打ち（それ以上はキャッシュを奪い合うだけ, Denning 1968）

ユーザーが `-Dmax.bg.threads=` を指定していれば触らない。

### 2.5 後片付け

遠ざかったチャンクのチケットは解放する（`radius + 2` のマージンで振動防止）。
チケットを張りっぱなしにするとメモリを食い続けるので、これは必須。

---

## 3. 使い方

```
/fastgenerate stats   … 状態（window / inFlight / 発行数 / 完了数 / mspt / frame）
/fastgenerate on|off  … ON/OFF（設定は config/ifuto-fastgenerate.json）
```

`stats` の見方:

- `window` — いま同時に走らせている生成の本数（AIMD が決める）
- `inFlight` — 実際に走っている数（`window` を超えない）
- `done` — 完了した数。歩き回ると増えていく
- `mspt` / `frame` — 制御が見ている実測値。目標を超えていれば自動で絞る

---

## 4. 設定（config/ifuto-fastgenerate.json）

| キー | 既定 | 意味 |
| --- | --- | --- |
| `enabled` | `true` | 有効 |
| `leadChunks` | `6` | ビューディスタンスの外に何チャンク先読みするか |
| `directionBias` | `1.0` | 進行方向の優先度（0 = 同心円） |
| `minWindow` / `maxWindow` | `2` / `12` | 同時生成数の範囲 |
| `issuePerTick` | `2` | 1 tick に発行する要求数（スパイク防止） |
| `maxTickets` | `4096` | 保持チケットの上限（メモリ保護） |
| `targetMspt` | `45` | これを超えたら絞る |
| `targetFrameMs` | `30` | フレームがこれを超えたら絞る |
| `tuneThreads` | `true` | `max.bg.threads` を CPU に合わせる |

重いデータパックで足りないなら `leadChunks` と `maxWindow` を上げる。
カクつくなら `targetMspt` を下げる（45 → 40 など）。

---

## 5. テスト（`testFastgenerate`）

Minecraft 抜きで検証できる中核ロジックを CI で毎回回す。

| テスト | 内容 |
| --- | --- |
| bgThreads | 2/4 コアで全コア、8 コアで 7 本、上限 16 |
| planner coverage | 半径内を漏れなく・重複なく・範囲外を含まない |
| planner direction | 進行方向のチャンクが前に来る（前が後ろより先） |
| planner dedupe | 同じ場所の複数プレイヤーで二重に要求しない |
| EWMA | 一定入力に数 τ で収束する |
| AIMD | 軽い負荷で上限まで開き、重い負荷で下限まで閉じる |
| hysteresis | 変化直後は 10 tick 動かさない |
| Little の法則 | window 2/4/8 で 0.5/1.0/2.0 chunk/tick |
| window bounds | 5000 tick のランダム負荷でも [min, max] を出ない |

---

## 6. 既知の制約

- **実機での効果は未計測**。設計上は「L を増やす」「先に作る」だけなので
  生成アルゴリズムは変えていない（＝結果はバニラと完全に同一）。
- チケットは `ChunkTicketType.UNKNOWN` を使う（独自チケット型を作るには
  レジストリ登録が必要で、バージョン間で壊れやすい）。万一 API が変わって
  失敗した場合は 1 回ログして自動停止する（ゲームは落ちない）。
- 先読みしたチャンクは保持されるので、メモリ使用量は
  `(viewDistance + lead)²` のオーダーで増える。`maxTickets` で上限を切っている。
- サーバー側の最適化なので、**マルチプレイの他人のサーバーでは効かない**
  （自分のサーバー / シングルプレイが対象）。
- フレーム時間の計測はクライアント側のみ。専用サーバーでは mspt だけで制御する。

---

## 7. 参考文献

1. Amdahl, G. M. (1967). Validity of the single processor approach to achieving large
   scale computing capabilities. *AFIPS Spring Joint Computer Conference*, 483–485.
2. Gustafson, J. L. (1988). Reevaluating Amdahl's law. *Communications of the ACM*,
   31(5), 532–533.
3. Little, J. D. C. (1961). A proof for the queuing formula L = λW.
   *Operations Research*, 9(3), 383–387.
4. Smith, J. E. (1982). Decoupled access/execute computer architectures.
   *ISCA '82*, 112–119.
5. Joseph, D., & Grunwald, D. (1997). Prefetching using Markov predictors.
   *ISCA '97*, 252–263.
6. Jacobson, V. (1988). Congestion avoidance and control. *SIGCOMM '88*, 314–329.
7. Hellerstein, J. L., Diao, Y., Parekh, S., & Tilbury, D. M. (2004).
   *Feedback Control of Computing Systems*. Wiley-IEEE Press.
8. Denning, P. J. (1968). The working set model for program behavior.
   *Communications of the ACM*, 11(5), 323–333.
9. Smith, W. E. (1956). Various optimizers for single-stage production.
   *Naval Research Logistics Quarterly*, 3(1), 59–66.
10. Belady, L. A. (1966). A study of replacement algorithms for a virtual-storage
    computer. *IBM Systems Journal*, 5(2), 78–101.
