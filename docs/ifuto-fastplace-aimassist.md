# Aim Assist（近接 / 剣）設計メモ

対象: `ifuto-fastplace` 0.3.2 / Minecraft 1.21.11 (Fabric)

弓（投射物）ではなく **剣・斧の間合い** のためのアシスト。設計の制約は 2 つ。

1. **人間と同じ動き**であること（論文の数字で決める。勘で決めない）
2. **人間を再現しすぎて外さない**こと（ノイズや遅れで当たらなくなったら意味がない）

この 2 つは普通に両立しない。だから「人間らしさは *動きの形* に入れ、*精度* は
当たり判定の内側に必ず収める」という分担にした。

---

## 1. 何をするか

`AimAssistModule` は毎 tick、視野内・間合いの少し手前にいる `LivingEntity` を 1 体選び、
`TrackingAim` が計算した **1 tick ぶんの回転量を「足す」だけ**。

- プレイヤーのマウス入力はそのまま効く。視点を奪わない（アシストが手を引き戻さない）
- パケット偽装はしない。本物の yaw/pitch が動く
- Scaffold（God Bridge）が体の向きを持っている間は休む（向きの取り合いを避ける）
- 画面を開いている / スペクテイター / 死亡 / 乗り物では休む

設定は Module Select の `Aim Assist` →「…」から。

| 設定 | 既定 | 意味 |
| --- | --- | --- |
| アシストの強さ | 0.75 | 角速度の上限と立ち上がりの係数（0.20〜1.00） |
| 視野（全角） | 70° | この視野に入った相手だけ狙う |
| 人間らしさ | ON | 反応時間・振戦・予測を使う。OFF にすると素直に詰める |
| 攻撃中だけ | ON | 左クリックを押している間だけ効かせる |
| 剣・斧のみ | ON | 近接武器を持っているときだけ |
| 先読み | ON | 相手の速度ぶん先を狙って追従の遅れを埋める |

---

## 2. 人間の動きをどこから持ってくるか

### 2.1 反応時間（動き出しの遅れ）

新しい相手を捉えてから **90〜150 ms** は動かない。

- 滑動性追従（smooth pursuit）の立ち上がり潜時は 100〜130 ms
  （Rashbass 1961、Lisberger et al. 1987）
- サッケード潜時は 180〜250 ms だが、こちらは「すでに見えている相手を追う」状況なので
  短いほうの帯を使う

### 2.2 速度の形（メインシーケンス + 慣性）

目標の角速度はメインシーケンスの飽和式

```
PV ≈ 850 (1 − e^(−A/6))  [deg/s]
```

（Bahill, Clark & Stark 1975、Collewijn, Erkelens & Steinman 1988）

を **0.35 倍に弱め**、`24〜220 deg/s` にクリップしたもの。弱めているのは、サッケードの
速さ（500〜700 deg/s）で回すと「瞬間移動」に見えるから。**上限 220 deg/s が
「スナップしない」保証**になっている。

実際の角速度は時定数 **65 ms** で指数的に立ち上がる（筋・眼球の慣性、
Woodworth 1899 の初期インパルス、Fitts 1954 の movement time）。そのため

- 小さいズレ → 遅く詰まる（Fitts の難度と運動時間）
- 大きいズレ → 速く詰まるが上限で頭打ち

1 tick の移動量は **残りの誤差でクリップ**するので、目標を通り越さない
（オーバーシュート無し = 修正サッケードを連発しない）。

### 2.3 振戦とドリフト

- 生理的振戦: AR(1)、σ ≈ 0.05°（8〜12 Hz を 20 TPS に落としたもの）
- 低周波ドリフト: 有界ランダムウォーク ±0.12°

**ここが「再現しすぎて外す」の分岐点**。振戦を *向きそのもの* に積むと
ランダムウォークになって、時間とともに狙いが外れていく。だから
**「狙う点」だけを揺らす**（向きには積まない）。結果として、見た目には
わずかな呼吸のような揺れが出るが、誤差は 0.2° 程度で有界。

### 2.4 予測（先読み）

人間は追従の遅れを予測で埋める（Barnes & Asselman 1991 の predictive pursuit、
予測ゲイン 0.6〜0.9）。

```
狙う点 += 相手の速度 × 65 ms × 0.40
```

ゲインを上げないのには理由がある。この速度プロファイル制御は、最大速度で横移動する相手
（5.6 m/s = 107 deg/s）でも角速度 190 deg/s で追えるので、**追従の遅れ自体がもう小さい**。
それ以上先読みすると、狙いが当たり判定の前縁に張り付いて、安全域ルールと毎 tick 押し引き
することになる（シミュレーションで確認済み: ゲイン 0.7 だと 3.0 ブロックで残差 4.7 度＝
安全域ぎりぎり、0.40 なら 2.8 度で押し引きゼロ）。0.40 は「通信 30 ms ぶんの先読み」程度。

---

## 3. 外さないための 4 つの仕掛け

1. **速度上限**（220 deg/s）と **1 tick クリップ**（残り誤差まで）で、行き過ぎが構造的に起きない
2. **悪化したら適用しない**: 回した結果、狙い（先読み込み）からも相手からも遠ざかるなら
   適用しない。片方だけが少し悪くなるのは許す（先読みぶんのずれを「悪化」と数えると
   毎 tick 押し引きになって逆に不自然）
3. **安全域への引き戻し**: すでに当たり判定の角度半径の 55% より外にいる場合だけ、
   同じ速度上限の範囲で内側へ引き戻す（`TrackingAim.pullInside`）
4. **狙う高さは胸（当たり判定の 62%）**: 上下の当たり判定には余裕があるので、
   垂直の誤差はほぼ命中に影響しない

当たり判定の角度半径は距離 3.0 ブロックで約 **8.4°**（半幅 0.4 として）。
テストでは、最大速度で横移動する相手（5.6 m/s）を追っても
**定常誤差 0.2° 程度**、瞬間最大でも 0.2° に収まることを固定している
（＝人間の速度で動きつつ、当たる）。

### 反応時間のあいだは当たらない、について

相手が *その場で* 突然最高速の横移動を始めた場合、反応時間 90〜150 ms のあいだは
こちらの狙いが遅れる（角度にして 10° 前後）。これは人間と同じで、この区間は
プレイヤー自身のマウスが担当する。アシストは「すでに戦闘が始まっている」状況で
効くようにできている（視野内に入った時点で反応時間が始まる）。

---

## 4. 対象の選び方

- `LivingEntity` のみ（自分・死亡・削除済み・透明は除外）
- 距離 4.6 ブロック以内（ロック中は 5.4 まで許す）
- 視野 70°（ロック中は ×1.35 まで許す → ちらつき防止）
- `player.canSee(target)`（壁越しは狙わない）
- スコア = 角度 + 距離 × 0.15、ロック中の相手は −6.0 のボーナス
- 500 ms 以内に同じ相手を捉え直した場合は反応時間をやり直さない

---

## 5. テスト（`testAim` / CI で毎回）

`test/AimAssistSelfTest.java`

| テスト | 内容 |
| --- | --- |
| onset | 反応時間のあいだは 1 度も動かない |
| convergence | 20° のズレが 250 ms 前後（人間の尺度）で 0.25° 以内に入る |
| no overshoot | 目標を通り越さない（< 0.4°） |
| speed cap | 1 tick の角速度が上限を超えない |
| micro noise | 狙いが合っているときの揺れが 0.35°/tick 未満 |
| strength | 強さを下げると遅くなる（0.25 でも収束はする） |
| pullInside | 安全域の外のぶんだけ内へ戻す補正が正しい |
| strafing | 5.6 m/s で横移動する相手を 3.0 ブロックから追い、常に当たり判定の内側 |
| humanize off | 人間らしさ OFF なら即動く |

---

## 6. 参考文献

1. Woodworth, R. S. (1899). *Accuracy of voluntary movement.* Psychological Review Monographs, 3(3).
2. Fitts, P. M. (1954). The information capacity of the human motor system in controlling the
   amplitude of movement. *Journal of Experimental Psychology*, 47(6), 381–391.
3. Meyer, D. E., Abrams, R. A., Kornblum, S., Wright, C. E., & Smith, J. E. K. (1988).
   Optimality in human motor performance: ideal control of rapid aimed movements.
   *Psychological Review*, 95(3), 340–370.
4. Harris, C. M., & Wolpert, D. M. (1998). Signal-dependent noise determines motor planning.
   *Nature*, 394, 780–784.
5. Bahill, A. T., Clark, M. R., & Stark, L. (1975). The main sequence, a tool for studying
   human eye movements. *Mathematical Biosciences*, 24, 191–204.
6. Collewijn, H., Erkelens, C. J., & Steinman, R. M. (1988). Binocular co-ordination of human
   horizontal saccadic eye movements. *Journal of Physiology*, 404, 157–182.
7. Rashbass, C. (1961). The relationship between saccadic and smooth tracking eye movements.
   *Journal of Physiology*, 159, 326–338.
8. Lisberger, S. G., Morris, E. J., & Tychsen, L. (1987). Visual motion processing and
   sensory-motor integration for smooth pursuit eye movements. *Annual Review of
   Neuroscience*, 10, 97–129.
9. Barnes, G. R., & Asselman, P. T. (1991). The mechanism of prediction in human smooth
   pursuit eye movements. *Journal of Physiology*, 439, 439–461.
10. Vicencio-Moreira, R., Mandryk, R. L., & Gutwin, C. (2015). Now You Can Compete: Measuring
    the Effectiveness of Aim Assistance in First-Person Shooter Games. *CHI 2015*.

---

## 7. 注意

- サーバーの規約と自己責任の範囲で使うこと。アシストである以上、対戦相手から見れば
  有利になる。この MOD は「偽のパケットを送らない」だけで、ルールを超えない保証はしない。
- この実装は **当たり判定の内側に収める** ことを最優先にしている。強さを上げても
  「速く寄る」だけで、当たらない角度から当てに行くことはない。
