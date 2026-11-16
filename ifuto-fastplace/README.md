# FastPlace + Scaffold (ifuto-fastplace)

右クリック長押しの連続設置を**人間の連打のクセ**で行う Mod に、
**Vape v4 風の Module Select 画面**（RShift）と、**God Bridge 用 Scaffold** を追加したものです。

- `RShift` … Module Select 画面を開く
- `R` … FastPlace の ON/OFF（操作設定 → `Ifuto FastPlace` で変更できます）

## できること

### FastPlace
- ブロックの連続設置間隔を変更（20〜500ms、既定 55ms）
- 連打中だけ画面右上に CPS を表示（小数1桁・毎 tick 変動・整数にならない）
- 手に**ブロック**を持って右クリックを押しているときだけ動く（食べる・飲む等には触らない）

### Scaffold（God Bridge）
ブロックを持って**前へ歩くだけ**で、足元の続きへ自動でブロックを置きます。

- ジャンプなし・スニークなし
- **プレイヤーの視界は前を向いたまま**。体（＝サーバーに送られる本物の向き）だけが
  ブロックへ向く。パケットの偽装はしていません
- モジュールを **OFF** にした / **ブロックを手放した** / 画面を開いた ときは、
  人間が顔を上げるのと同じ速度プロファイルで、**今見ている方向へ**なめらかに戻ります
- ブロックを持っていないとき・モジュールが OFF のときは発動しません
- 設置位置・狙い方・タイミングは人間のクセを再現（下記）

### Module Select 画面
- 左にカテゴリ（Movement / Player / Render）、右にモジュール一覧
- 行をクリックで ON/OFF（トグルはアニメーション）
- モジュール名の右に出る `…` をクリックで設定画面
- `RShift` または `ESC` で閉じる

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

## 設定

| 場所 | 項目 |
| --- | --- |
| Module Select → Scaffold → `…` | ジャンプ抑止 / 視点を前のまま / 人間らしさ / 腕を振る / 設置ペース |
| Module Select → FastPlace → `…` | 有効 / CPS 表示 / 設置間隔 |
| 操作設定 (Controls) | モジュール画面を開くキー、FastPlace の ON/OFF キー |

設定は `config/ifuto-fastplace.json` に保存されます。

## テスト

```bash
./gradlew -p ifuto-fastplace testFastplace   # 連打タイミングモデル
./gradlew -p ifuto-fastplace testScaffold    # 視線・設置リズム・距離トリガの安全性
```

CI では `build` に両方ぶら下げてあるので、壊れたらビルドが落ちます。

## 構成

```
src/main/java/com/ifuto/fastplace/
├── FastPlaceMod.java          … キーバインド / tick の入口
├── FastPlaceModule.java       … 連打本体（ClickTimingModel を使用）
├── ClickTimingModel.java      … 連打の統計モデル
├── human/HumanAim.java        … 視線（サッケード・追従・振戦）
├── human/PlacementRhythm.java … 設置間隔（ex-Gaussian・疲労・ためらい）
├── scaffold/ScaffoldView.java … カメラと体の向きの分離 / 復帰
├── scaffold/ScaffoldModule.java … God Bridge 本体
├── module/…                   … モジュール一覧（画面に出すメタ情報）
├── gui/ModuleScreen.java      … Module Select 画面
├── gui/ScaffoldSettingsScreen.java
├── gui/FastPlaceConfigScreen.java
└── mixin/                     … Camera / Entity(changeLookDirection) / Input
```

## 注意

- ブロック自動設置・連打が禁止されているサーバーでは使わないでください。
  サーバーのルールと BAN リスクは自己責任で。
- `ifuto-f3` を同時に入れている場合、同じ RShift を取り合わないよう
  f3 側（0.2.1 以降）はモジュール画面と Scaffold を自動で休みます。
