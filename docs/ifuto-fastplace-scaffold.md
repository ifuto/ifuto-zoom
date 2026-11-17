# Scaffold（God Bridge）の設計と人間モデル

対象: `ifuto-fastplace` 0.3.0 / Minecraft 1.21.11 (Fabric)

## 0. 前提: 何を作ったか

- ブロックを手に持って前へ歩くだけで、足元の続きへ自動でブロックを置く（スニークなし・ジャンプなし）
- **パケットは偽装しない**。エンティティの yaw/pitch は本当にブロックへ向け、
  バニラの移動パケットでそのままサーバーへ送られる
- ただし**カメラ**（自分の視界）だけはプレイヤーの操作した向きに固定する。
  画面は前を向いたまま歩ける
- モジュール OFF / ブロックを手放した / 画面を開いた / 降りた、では
  人間が顔を上げるのと同じ速度で**今見ている方向**へ戻る
- 発動条件は「モジュール ON」かつ「メインハンドがブロック」

「バレないように」という要求に対して、ここでやっているのは
**チート対策の回避ではなく、動きの統計を人間の範囲に収めること**だけ。
パケット偽装・テレポート・リーチ超過・サーバーが拒否する設置は一切していない。
（それでもサーバーのルールで禁止されている行為には変わりないので、利用は自己責任。）

---

## 1. 視点と体の分離

Minecraft のカメラは毎フレーム `Camera#update` で注視エンティティの yaw/pitch から
組み立てられる。素直に `player.setPitch(70)` すると自分の視界も 70 度下を向く。

そこで:

| チャネル | 役割 |
| --- | --- |
| `Entity#pitch` | **本物**の向き。サーバーへ送られる。Scaffold 中はブロックを狙う |
| `Camera` の pitch | **プレイヤーの視点**。マウスで操作した値のまま |

- `CameraMixin` が `Camera#update` の RETURN で `setRotation(getYaw(), visualPitch)` を呼ぶ。
  `setRotation` はカメラ内部のクォータニオンも更新するため、描画側（`getRotation()`）まで
  正しく反映される。フィールド直接書き換えより安全。
- マウス入力の分離は 2 系統。`Entity#changeLookDirection` を mixin で横取りできれば
  その場で視点側へ加算し、yaw だけエンティティへ返す。横取りできない環境では、
  毎 tick「前回こちらが書いたピッチ」との差をマウス入力として視点側へ回収する。
  どちらか片方しか効かないので二重計上は起きない。
- なぜ横取りが必要か: 体が +70 度を向いている間にマウスを下へ振ると、
  バニラの `MathHelper.clamp(pitch, -90, 90)` で入力を捨ててしまう。
  横取りすると視点は制限なく動かせる。

体の yaw は常に視点の yaw と一致させる。Minecraft の移動方向はエンティティの yaw で
決まるので、ここをずらすと「見ている方向と歩く方向が違う」不自然な動きになる。
Scaffold が動かすのはピッチだけ。

---

## 2. 置く位置（本物のブリッジと同じ場所を狙う）

1. 足元の「歩いている面」の高さ `planeTop` を毎 tick 求める
   （足元から最大 4 ブロック下まで走査し、上面が足の高さと一致する固体ブロックを採用。
   段差・スラブ・乗り物などでズレたら前回値を保持）
2. 自分の列が空なら**緊急**にそこを埋める（＝落ちかけの救済）
3. 進行方向（WASD 入力 → ワールドベクトル。入力が無ければ速度）へ 1〜3 列先を走査し、
   **最初に空いている列**を設置対象にする
4. その列の 6 面のうち、隣が固体で、目から見えていて、リーチ内の面を選ぶ。
   スコアは「見えやすさ」＋「**今乗っているブロック**であること」＋
   「進行方向と逆向きの面」−「上面」。結果として、**足元のブロックの側面**を
   叩く＝本物の God Bridge と同じ画になる（視線も足元付近、ピッチ 70 度前後）
5. 狙い点は面の内側で ±0.18 ブロックばらつかせる。人と同じブロックを毎回同じ点で
   叩かないため。同じ対象を狙っている間は同じ点を維持する（毎 tick 振り直すと
   手ブレではなくノイズになる）

設置は `ClientPlayerInteractionManager#interactBlock` に、幾何的に正しい
`BlockHitResult`（位置・面・隣接ブロック）を渡して行う。バニラの右クリックと同じ経路。
自分の設置は 900ms のあいだ「埋まった」とみなして、確認前の二重設置を防ぐ。

---

## 3. いつ置くか（距離トリガ + 人間のリズム）

一定 CPS で連打すると機械的で、しかも歩行速度と無関係にブロックを消費する。
本物のブリッジは「次に必要になる 1 ブロック」を、**歩きながら**置く。

- 設置対象の列の中心が自分の水平位置から `trigger` ブロック以内になったら置く。
  `trigger` はブロックごとに `U(1.15, 1.70)` から引き直す（人ごと・ブロックごとのクセ）。
- 連続設置には `PlacementRhythm` の最小間隔が乗る。基準 100ms、対数正規の揺らぎ、
  疲労で最大 +22%、まれに「ためらい」（2%・×2.0〜3.4）と「二度打ち」（2%・×0.72〜0.84）。
  実際に待つ時間は「進行方向に何列ぶん床が残っているか」から上限を計算して切り詰める
  （`maxSafeGapMs`）。人間のテンポは入れたいが、床が尽きる先では待たない。
- 最初の 1 個だけは「狙いが合ってから押すまで」の反応時間
  （ex-Gaussian、平均 ~45ms）を入れる。

距離トリガにしているので、**速く歩けば設置数も増える**。
スプリント（5.6 m/s）なら概ね 5〜6 個/秒、徒歩なら 4 個/秒前後。
God Bridge の実際の速度（約 5 ブロック/秒・12 CPS 推奨）と同程度の画になる。
余分なクリックを撒かないので、ブロックも無駄にならない。

安全率: スプリント 0.28 ブロック/tick、トリガ最小 1.15、当たり判定半幅 0.3 で、
置いてから落ち始めるまで 3 tick 以上ある（`ScaffoldModelSelfTest#testBridgeSafety`
で 100 ブロック落下しないことを検証）。

---

## 4. 視線の動かし方（サッケード + 追従 + 振戦）

`human/HumanAim` は 1 軸（ピッチ）の視線モデル。

- **大きい移動**（> 3 度）は弾道運動（サッケード）。持続時間はメインシーケンス
  `D = 2.7 A + 23 ms`（Collewijn et al. 1988, Bahill et al. 1975）、
  軌道は最小ジャーク `10t³ − 15t⁴ + 6t⁵`（Flash & Hogan 1985）。
  目標が動いたら「修正サッケード」として組み直す。
- **小さいズレ**は臨界減衰の追従系（時定数 70ms）。ピーク角速度は
  `PV ≈ 850 (1 − e^(−A/6)) deg/s` で飽和（Clark & Stark 系のモデル）。
- **振戦**: AR(1) ノイズ σ ≈ 0.045 度（生理的振戦 8–12Hz・0.1–0.5 度を 20TPS に落としたもの）
- **ドリフト**: 有界ランダムウォーク ±0.30 度
- **信号依存ノイズ**: 速く動くほど狙いが荒れる（Harris & Wolpert 1998）

20 TPS でしか更新されないが、それはサーバー／他プレイヤーが見る解像度そのもの。
復帰（顔を上げる）も同じモデルを使うので、70 度 → 0 度が約 210ms の
人間らしい速度プロファイルで戻る。

---

## 5. 状態遷移

```
OFF ──(モジュールON & ブロック & ギャップあり)──▶ AIM
AIM ──(置く必要が 520ms 無い / OFF / 手放した / 画面 / 降りた)──▶ RESTORE
RESTORE ──(視点へ到達)──▶ OFF
RESTORE ──(またギャップ)──▶ AIM
```

- `AIM` の間、カメラは視点のまま。体はブロックを狙う
- ギャップが無いあいだ（普通の地面を歩いているだけ）も、人がやるように
  しばらく下を見ていて、その後ふっと前へ戻る
- ジャンプ入力は Scaffold 中だけ落とす（設定で OFF 可）

---

## 6. 参考文献

1. Bahill A.T., Clark M.R., Stark L. (1975) *The main sequence, a tool for studying human
   eye movements.* Math. Biosci. 24:191–204.
2. Collewijn H., Erkelens C.J., Steinman R.M. (1988) *Binocular co-ordination of human
   horizontal saccadic eye movements.* J. Physiol. 404:157–182.
3. Flash T., Hogan N. (1985) *The coordination of arm movements: an experimentally
   confirmed mathematical model.* J. Neurosci. 5(7):1688–1703.
4. Harris C.M., Wolpert D.M. (1998) *Signal-dependent noise determines motor planning.*
   Nature 394:780–784.
5. Wing A.M., Kristofferson A.B. (1973) *Response delays and the timing of discrete motor
   responses.* Percept. Psychophys. 14:5–12.
6. Hohle R.H. (1965) *Inferred components of reaction times as functions of foreperiod
   duration.* J. Exp. Psychol. 69:382–386.（ex-Gaussian の原型）
7. Luce R.D. (1986) *Response Times: Their Role in Inferring Elementary Mental Organization.*
   Oxford Univ. Press.
8. Gilden D.L., Thornton T., Mallon M.W. (1995) *1/f noise in human cognition.*
   Science 267:1837–1839.
9. Fitts P.M. (1954) *The information capacity of the human motor system in controlling the
   amplitude of movement.* J. Exp. Psychol. 47:381–391.（距離が大きいほど終点のばらつきが増える）
10. Minecraft Wiki, *Tutorial:Bridging*（God Bridge の手順・速度の目安）
