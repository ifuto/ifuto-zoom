# ClickGUI の見た目（モックとの対応表）

対象: `ifuto-fastplace` 0.6.0

HTML のモックを Minecraft の GUI に移すとき、**CSS の値をそのまま持ってくる**ための一覧。
色・角丸・余白はすべて `gui/Ui.java` の定数 1 か所で決めている。

## トークン

| CSS 相当 | 定数 | 値 | 使いどころ |
| --- | --- | --- | --- |
| `--surface` | `Ui.SURFACE` | `#151518` / 88% | パネルの地（後ろの世界が少し透ける） |
| `--surface-2` | `Ui.SURFACE_2` | `#1B1B1F` | 一段上の面 |
| `--surface-3` | `Ui.SURFACE_3` | `#222228` | ボタン・チップ |
| `--border` | `Ui.BORDER` | 白 8% | 1px の縁 |
| `--border-strong` | `Ui.BORDER_STRONG` | 白 20% | ウィンドウ枠 |
| `--hover` | `Ui.HOVER` | 白 7% | 行のホバー |
| `--selected` | `Ui.SELECTED` | 白 12% | 選択中のカテゴリ・入力欄 |
| `--accent` | `Ui.ACCENT` | `#2ED3A0` | アクセント（エメラルド） |
| `--text` | `Ui.TEXT` | `#F1F1F4` | 本文 |
| `--muted` | `Ui.MUTED` | `#A0A0AC` | 補助 |
| `--dim` | `Ui.DIM` | `#6C6C78` | 説明文 |
| `border-radius` | `Ui.RADIUS` | 10px | パネル |
| `border-radius-sm` | `Ui.RADIUS_SM` | 6px | ボタン・行 |
| 行の高さ | `MOD_ROW` | 44px | モジュール行（名前 + 説明） |
| 余白 | `PAD` | 14px | パネル内の基本余白 |

## なぜ面取り（45°）をやめたか

以前は `fill` を 4 枚重ねて角を斜めに落としていた（アンチエイリアス無し）。
拡大するとギザギザで「カクカク」に見えるうえ、Web の `border-radius` と形が違う。
いまは `Ui.roundRect` が**行ごとに内側の幅を計算し、端の 1px を被覆率のアルファで描く**ので、
どの半径でも滑らかになる（`border-radius` と同じ見え方）。

## 文字

- バニラフォントを **等倍（scale = 1.0）・影なし**で描く
- タイトルも大文字にしない（"The Client." / "FastPlace" / "Auto Safe Anchor"）
- 影を付けると文字の輪郭が汚れて「ドットが目立つ」ので付けない
- どうしても縮めたいときだけ 0.7〜0.9 を使う（拡大はしない）

## 操作

| 操作 | 動き |
| --- | --- |
| 左クリック（行） | ON / OFF |
| 右クリック（行） | コンテキストメニュー（設定 / ON-OFF / キー確認） |
| ヘッダードラッグ | ウィンドウ移動 |
| RShift / ESC | 閉じる（開いた直後の 160ms は RShift を無視） |

## 差し替えたいとき

`Ui.java` の定数を書き換えるだけで全画面に効く。行の高さ・余白は
`ModuleScreen` / 各 `*SettingsScreen` の `ROW_H` / `PAD` にある。
HTML の値（`padding: 16px` など）をそのまま入れてよい。
