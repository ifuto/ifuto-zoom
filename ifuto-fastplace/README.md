# FastPlace + Scaffold + Aim Assist + Auto Tool + ClickCrystal (ifuto-fastplace)

右クリック長押しの連続設置を**人間の連打のクセ**で行う Mod に、
**Vape v4 風の Module Select 画面**（RShift）、**God Bridge 用 Scaffold**、
**剣用 Aim Assist**、**Auto Tool**、**ClickCrystal** を追加したものです。

- `RShift` … Module Select 画面を開く
- `R` … FastPlace の ON/OFF（操作設定 → `Ifuto FastPlace` で変更できます）

## できること

### FastPlace
- ブロックの連続設置間隔を変更（20〜500ms、既定 55ms）
- 連打中だけ画面右上に CPS を表示（小数1桁・毎 tick 変動・整数にならない）
- 手に**ブロック**を持って右クリックを押しているときだけ動く（食べる・飲む等には触らない）
- 設置は **描画フレーム単位**（tick の 50ms に丸めない）。間隔は ms 単位のまま
  ばらつくので、サーバーから見て「50, 50, 50, 100…」と**同じ数字が並ばない**
- **同じ / ほぼ同じ間隔は続けない**（直近 4 回と ±6ms 以内なら必ずずらす）
- 置くときは `interactBlock` + `swingHand` という**バニラと同じ入口**。
  パケットを自前で作らないので、CPS 表示 Mod にもそのまま数えられる

### Scaffold（God Bridge）
ブロックを持って**前へ歩くだけ**で、足元の続きへ自動でブロックを置きます。

- ジャンプなし・スニークなし
- **プレイヤーの視界は前を向いたまま**。体（＝サーバーに送られる本物の向き）だけが
  ブロックへ向く。パケットの偽装はしていません
- モジュールを **OFF** にした / **ブロックを手放した** / 画面を開いた ときは、
  人間が顔を上げるのと同じ速度プロファイルで、**今見ている方向へ**なめらかに戻ります
- ブロックを持っていないとき・モジュールが OFF のときは発動しません
- 設置位置・狙い方・タイミングは人間のクセを再現（下記）

### Aim Assist（剣・近接）
**左クリック 1 回**で相手を掴み、人間の速度で寄ります（押しっぱなし不要）。

- クリックした瞬間の視線が、相手の当たり判定を **1.2 倍**に広げた箱に当たり、
  **ブロックや他のエンティティで遮られていなければ**掴む（倍率は設定で 1.0–1.6x）
- 掴んだら既定 3 秒追う（再クリックで延長）。距離・視界を外れたら手放す
- 回転は **描画フレームごと**に人間の速度で足すので、20Hz の段差（カクつき）がない
- 歩きながら狙うと **進行方向と逆向きに 1.2°** ずれる（設定 −4〜+4°、0 で無効）
- マウス入力は常にそのまま効く（視点を奪わない・パケット偽装なし）

### ClickCrystal（結晶爆破）
黒曜石（岩盤）を**左クリック**すると、数 ms でエンドクリスタルへ持ち替えて
**置く → 爆破**を連続実行します。

- 対象が黒曜石/岩盤で、上が空気、クリスタルをホットバーに持っているときだけ動く
- 攻撃 → 設置の順に送るので、サーバーでは必ず「爆破 → 設置」になる
- 間隔 (20–300ms)・連続時間 (0.1–5s)・届く距離・終了後に元のスロットへ戻すか、を設定可能
- 爆風は自分にも当たるので、距離を取って撃つこと

### Auto Tool（採掘）
適正でない道具で掘り始めたら、**人間が選び直すのと同じ時間**をかけて持ち替えます。

- 持ち替えてから掘るのはバニラ任せ（攻撃キーは押されたままなので、そのまま掘り進む）
- 判断時間は Hick–Hyman（候補数）+ 練習のべき乗則 + drift-diffusion + 反復 + 誤り後減速
- 差が小さいときだけ、たまに隣の道具を掴む（人間の取り違え）
- **15% 以上速くならないなら持ち替えない**（掘るのが遅くならない）
- 「押している間だけ」も設定で選べる（既定 OFF = クリックで掴む方式）

### ClickGUI（The Client.）
Vape 系クライアントの見た目（濃いグレーのパネル + エメラルドのアクセント）で作り直しました。

- 背景は**プレイ画面のまま軽くブラー + 20% だけ暗く**する（真っ黒/青で塗り潰さない）。
  パネル自体も 85% の不透明さで、後ろの世界がうっすら透ける
- 2 枚のパネルは**ドラッグで移動**でき、ヘッダーに `The Client. v0.5.1` を表示
- 左パネル: カテゴリ（Combat / Movement / Player / Render）と
  下段に MISC（Keybinds / Config / Self test）。Combat には Aim Assist と ClickCrystal
- 右パネル: モジュール行（名前 / キーバインドのチップ / ON-OFF ピル）。
  行クリックでトグル、右端の `⋮` で設定画面
- 角は 45° の面取り、ホバーで 1 段明るくなる、トグルはアニメーション付き
- 設定画面も同じ意匠（2 列のスライダー / トグル、保存は自動）
- `RShift` で開閉、`ESC` で閉じる

## 人間らしさの根拠（Scaffold）

設置のテンポは**歩く速さ**で決まります（一定 CPS で連打しない）。
その上に、視線と指の統計モデルを重ねています。詳細は
[`docs/ifuto-fastplace-scaffold.md`](../docs/ifuto-fastplace-scaffold.md)。

- **視線**: 大きい移動はサッケードのメインシーケンス（持続時間 `D ≈ 2.7A + 23 ms`、
  ピーク角速度 `PV ≈ 850(1-e^(-A/6)) deg/s`）に合わせた最小ジャーク軌道。
  小さいズレは時定数 ~70ms の追従。常にわずかな振戦とドリフトが乗る
  （Bahill, Clark & Stark 1975 / Collewijn et al. 1988 / Flash & Hogan 1985 /
  Harris & Wolpert 1998）
- **設置リズム**: Wing–Kristofferson (1973) の間隔分解 + ex-Gaussian の裾、
  疲労と回復、たまの「ためらい」と「二度打ち」、1/f 的なテンポの揺れ
- **設置位置**: 本物のブリッジと同じく「今乗っているブロックの側面」を叩く。
  狙い点はブロックごとに ±0.18 ブロックばらつく（人が毎回同じ点を狙わないため）

## 人間らしさの根拠（Aim Assist）

詳細は [`docs/ifuto-fastplace-aimassist.md`](../docs/ifuto-fastplace-aimassist.md)。

- **反応時間**: 滑動性追従の立ち上がり潜時 100〜130ms（Rashbass 1961 / Lisberger et al. 1987）
- **速度の形**: メインシーケンス `PV ≈ 850(1-e^(-A/6)) deg/s` を弱めて上限 220 deg/s、
  立ち上がりは時定数 65ms（Bahill et al. 1975 / Fitts 1954 / Woodworth 1899）
- **振戦・ドリフト**: 生理的振戦 σ ≈ 0.05° + 有界ドリフト ±0.12°。
  **狙う点だけを揺らし、向きには積まない**（積むとランダムウォークで外れる）
- **予測**: 追従の遅れを速度 × 65ms × 0.70 で先読み（Barnes & Asselman 1991）
- **外さない保証**: 速度上限 + 1 tick クリップ（行き過ぎない）/ 誤差が悪化する回転は
  適用しない / 当たり判定の角度半径の 55% より外なら内側へ引き戻す

## 人間らしさの根拠（Auto Tool）

詳細は [`docs/ifuto-fastplace-autotool.md`](../docs/ifuto-fastplace-autotool.md)。

- **選択肢の数**: `RT = 165 + 62·log2(n+1) ms`（Hick 1952 / Hyman 1953）
- **練習**: `+150·(n+1)^(-0.35) ms`（Newell & Rosenbloom 1981 のべき乗則）
- **証拠の強さ**: 差が明白なほど速い（Ratcliff 1978 / Ratcliff & McKoon 2008）。
  同じ項が取り違え確率にも効く（迷うと間違える）
- **反復**: 同じスロットなら ×0.88（Rogers & Monsell 1995）
- **誤り後**: 直後の判断は +110〜170ms（Rabbitt 1966）
- 典型条件で約 300ms、全体は 110〜560ms にクランプ

## 設定

| 場所 | 項目 |
| --- | --- |
| ClickGUI → Scaffold → `…` | ジャンプ抑止 / 視点を前のまま / 人間らしさ / 腕を振る / 設置ペース |
| ClickGUI → FastPlace → `…` | 有効 / CPS 表示 / 設置間隔 |
| ClickGUI → Aim Assist → `…` | クリックで掴む / 掴む時間 / 判定の倍率 / プレイヤーだけ / 強さ / 視野 / 滑らかさ / 進行方向のズレ / 先読み / 押している間だけ |
| ClickGUI → ClickCrystal → `…` | 間隔 / 連続時間 / 届く距離 / 元のスロットに戻す |
| ClickGUI → Auto Tool → `…` | 人間らしさ / 掘っている間だけ / 壊れかけを避ける |
| 操作設定 (Controls) | モジュール画面を開くキー、FastPlace の ON/OFF キー |

設定は `config/ifuto-fastplace.json` に保存されます。

## テスト

```bash
./gradlew -p ifuto-fastplace testFastplace   # 連打タイミングモデル
./gradlew -p ifuto-fastplace testScaffold    # 視線・設置リズム・距離トリガの安全性
./gradlew -p ifuto-fastplace testAim         # 反応時間・速度上限・当たり判定・1.2x 掴み・進行方向のズレ・滑らかさ
./gradlew -p ifuto-fastplace testAutoTool    # 道具選択の判断時間モデル
```

CI では `build` に全部ぶら下げてあるので、壊れたらビルドが落ちます。

## 構成

```
src/main/java/com/ifuto/fastplace/
├── FastPlaceMod.java          … キーバインド / tick の入口
├── FastPlaceModule.java       … 連打本体（ClickTimingModel を使用）
├── ClickTimingModel.java      … 連打の統計モデル
├── human/HumanAim.java        … 視線（サッケード・追従・振戦）
├── human/TrackingAim.java     … 近接アシストの追従モデル（反応時間・速度上限・振戦）
├── aim/AimAssistModule.java   … 相手の選択・先読み・当たり判定の安全域
├── human/ToolSelectModel.java … 道具を選ぶまでの時間（Hick・練習・誤り）
├── tool/AutoToolModule.java   … 適正ツールへの持ち替え
├── human/PlacementRhythm.java … 設置間隔（ex-Gaussian・疲労・ためらい）
├── scaffold/ScaffoldView.java … カメラと体の向きの分離 / 復帰
├── scaffold/ScaffoldModule.java … God Bridge 本体
├── module/…                   … モジュール一覧（画面に出すメタ情報）
├── gui/ModuleScreen.java      … Module Select 画面
├── gui/ScaffoldSettingsScreen.java
├── gui/AimAssistSettingsScreen.java
├── gui/AutoToolSettingsScreen.java
├── gui/FastPlaceConfigScreen.java
└── mixin/                     … Camera / Entity(changeLookDirection) / Input
```

## 注意

- ブロック自動設置・連打・エイムアシスト・自動持ち替えが禁止されているサーバーでは
  使わないでください。
  サーバーのルールと BAN リスクは自己責任で。
- `ifuto-f3` を同時に入れている場合、同じ RShift を取り合わないよう
  f3 側（0.2.1 以降）はモジュール画面と Scaffold を自動で休みます。
