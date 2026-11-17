# Auto Tool 設計メモ

対象: `ifuto-fastplace` 0.4.0 / Minecraft 1.21.11 (Fabric)

適正でない道具でブロックを掘ろうとしているとき、**人間が道具を選び直すのと同じ時間と
同じ失敗**を経てから、ホットバーの一番速い道具へ持ち替えるモジュール。

ポイントは「持ち替えが遅い」ことではない。持ち替え自体は 1 tick で終わる。
人間らしさは **選ぶまでの時間** と **選び間違え** にある。

---

## 1. 何をするか

- 攻撃キー（掘る）が押されていて、クロスヘアがブロックを向いているときだけ動く
- いまの道具より **15% 以上**速い道具がホットバーにあれば、持ち替える
- 持ち替え先が無い / 差が小さい / すでに最適 → 何もしない
- 掘り始めはバニラに任せる（攻撃キーは押されたままなので、道具が変わればそのまま掘れる）
- 掘るのをやめた・狙いが変わった → 判断を捨てる（人間も同じ）

| 設定 | 既定 | 意味 |
| --- | --- | --- |
| 人間らしさ | ON | 判断時間・練習・たまの取り違えを使う。OFF なら即座に持ち替え |
| 掘っている間だけ | ON | OFF にすると、ブロックを狙っただけで持ち替える |
| 壊れかけを避ける | OFF | 残り耐久 10 以下の道具は使わない（最後の 1 本を守る） |

---

## 2. 選ぶまでの時間は何で決まるか

`ToolSelectModel` は 5 つの古典を足し合わせる。Minecraft 非依存なので、CI で
数値の向きと範囲を毎回検証している（`testAutoTool`）。

### 2.1 選択肢の数 — Hick–Hyman の法則

```
RT = 165 + 62 · log2(n + 1)   [ms]
```

（Hick 1952 *On the rate of gain of information*、Hyman 1953）
候補が 9 個なら 165 + 62·3.32 ≈ 371 ms。**候補が多いほど遅い**。

実装では「いまより 15% 以上速いスロットの数」を n にしている（人間も “使える選択肢”
だけを数える）。1 個なら 227 ms、9 個なら 371 ms。

### 2.2 練習 — べき乗則

```
遅れ += 150 · (n + 1)^(−0.35)   [ms]      n = このブロックを掘った回数
```

（Newell & Rosenbloom 1981 の power law of practice、指数は 0.2–0.4 が典型）

初見のブロックは +150 ms 迷い、10 回目で +65 ms、40 回目で +41 ms まで縮む。
「石はツルハシ」という知識の検索コストが練習で下がる、という解釈
（Sternberg 1969 の記憶走査）。

### 2.3 証拠の強さ — drift-diffusion

```
RT ×= clamp(1.10 − 0.22·log2(1 + gainRatio), 0.72, 1.10)
```

（Ratcliff 1978、Ratcliff & McKoon 2008）

`gainRatio = 最良の速さ ÷ いまの速さ − 1`。**明らかに速い道具があると迷わない**。
逆に「木のツルハシと石のツルハシ」のように差が小さいと遅い。
diffusion model では drift rate が小さいほど判断が遅く、期限が来ると誤る —
そのため同じ項が §2.5 の取り違え確率にも効く。

### 2.4 反復プライミングと誤り後の減速

- 直前に選んだのと同じスロットなら **×0.88**（Rogers & Monsell 1995 の反復コスト）
- 直前の判断が誤りだったら **+110〜170 ms**（Rabbitt 1966 の post-error slowing）

### 2.5 選び間違い

```
p = clamp(0.060 − 0.030·log2(1 + gainRatio), 0.004, 0.060)
```

差が小さいときだけ 5% 前後。明白なときは 0.4%。間違えたときは **2 番目に速いスロット**
を掴む（人間が隣のキーを押すのと同じ）。次 tick の判断で正しい道具に直るが、
その判断は post-error slowing で遅くなる。

### 2.6 全体

```
RT = clamp( [Hick] + [practice] × [evidence] × [repeat?] + [post-error?] + N(0, 22ms),
            110, 560 ms )
```

実測（テストの値）:

| 条件 | 平均 |
| --- | --- |
| 候補 1 / 3 / 9 | 245 / 299 / 372 ms |
| 初見 → 掘り慣れ（9 候補） | 458 → 361 ms |
| 差が小さい / 明白 | 440 / 304 ms |
| 同スロット反復 | 372 → 327 ms |
| 誤り後 | 372 → 510 ms |
| 典型（9 候補・25 回目・3 倍速） | 302 ms |

---

## 3. 「遅くしない」ための約束

1. **15% ルール**: 速くならないなら持ち替えない（持ち替えで 1 tick 失うので、
   小さな改善は差し引きゼロ以下）
2. 持ち替え先が無ければ何もしない（素手・建築中などはそのまま）
3. 判断中に掘り終わったブロックには何もしない（狙いが変われば判断を捨てる）
4. `Scaffold`（God Bridge）が体の向きを持っている間、`Aim Assist` が相手を追っている間は休む
5. 画面を開いている / スペクテイター / 死亡 では休む

---

## 4. テスト（`testAutoTool` / CI で毎回）

`test/AutoToolSelfTest.java`

| テスト | 内容 |
| --- | --- |
| Hick–Hyman | 候補 1 < 3 < 9 で単調に遅くなる（差 > 60ms） |
| practice | 初見より掘り慣れたブロックが速い（差 > 25ms） |
| evidence | 差が明白なほど速い |
| priming | 同スロット反復が速い |
| post-error | 誤り直後は 60ms 以上遅い |
| bounds | 5000 サンプルすべてが 110–560ms に収まる |
| mispick | 差が小さいほど取り違える（1–9% / 明白なら 2% 未満） |
| typical | 典型条件で 180–450ms |

---

## 5. 参考文献

1. Hick, W. E. (1952). On the rate of gain of information. *Quarterly Journal of
   Experimental Psychology*, 4(1), 11–26.
2. Hyman, R. (1953). Stimulus information as a determinant of reaction time.
   *Journal of Experimental Psychology*, 45(3), 188–196.
3. Fitts, P. M. (1954). The information capacity of the human motor system in controlling
   the amplitude of movement. *Journal of Experimental Psychology*, 47(6), 381–391.
4. Ratcliff, R. (1978). A theory of memory retrieval. *Psychological Review*, 85(2), 59–108.
5. Ratcliff, R., & McKoon, G. (2008). The diffusion decision model: theory and data for
   two-choice decision tasks. *Neural Computation*, 20(4), 873–922.
6. Rabbitt, P. M. A. (1966). Errors and error correction in choice-response tasks.
   *Journal of Experimental Psychology*, 71(2), 264–272.
7. Rogers, R. D., & Monsell, S. (1995). Costs of a predictable switch between simple
   cognitive tasks. *Journal of Experimental Psychology: General*, 124(2), 207–231.
8. Newell, A., & Rosenbloom, P. S. (1981). Mechanisms of skill acquisition and the law of
   practice. In J. R. Anderson (Ed.), *Cognitive Skills and Their Acquisition* (pp. 1–55).
9. Sternberg, S. (1969). Memory-scanning: mental processes revealed by reaction-time
   experiments. *American Scientist*, 57(4), 421–457.
10. Keele, S. W. (1968). Movement control in skilled motor performance.
    *Psychological Bulletin*, 70(6), 387–403.

---

## 6. 注意

- サーバーのルールと自己責任で。自動持ち替えを禁止している場所では使わないこと。
- パケット偽装はしていない。実際にホットバーのスロットが切り替わる（バニラの
  `UpdateSelectedSlot` がそのまま飛ぶ）。
