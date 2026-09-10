# ifumods:server/discord — サーバー向けパケットガイド

ifuto 系 Mod が共通で受け取っている、Discord Rich Presence（Discord の「プレイ中」表示）更新用のカスタムペイロードの仕様です。サーバー側から送ると、プレイヤーの Discord ステータスに好きな文字列を表示できます。

## まとめ

| 項目 | 値 |
| --- | --- |
| チャンネル | `ifumods:server/discord` |
| 方向 | S2C（サーバー → クライアント）。PLAY フェーズ |
| 中身 | **Minecraft 文字列1本**だけ（VarInt 長 + UTF-8） |
| 意味 | 文字列をそのまま Rich Presence の 1 行目（details）に表示 |
| 空文字 | プレゼンスを消す |
| 推奨頻度 | 変更時のみ送るのがベスト。毎秒 1 回程度までなら OK（クライアント側で 0.8 秒間隔に間引きされます） |

## 動作の流れ

1. サーバーが `ifumods:server/discord` に文字列を送る
2. 受け取ったクライアント Mod（Ifuto Armor HUD など）が、ローカルの Discord IPC に `SET_ACTIVITY` を送る
3. プレイヤーの Discord の「プレイ中」欄にその文字列が出る
   - アイコンは `https://a12a12a12.pages.dev/icon.png`（Discord の外部画像プロキシ経由）
4. 空文字を送ると表示が消える

※ クライアント側設定で **Discord Client ID** が入っていない人には何も起きません（無効扱い）。Client ID は [Discord Developer Portal](https://discord.com/developers/applications) でアプリケーションを作ると貰えます。

## ペイロードの中身（バイト列の構造）

カスタムペイロードのボディは「Minecraft 文字列」が1つだけです。

```
+------------+------------------+
| VarInt 長さ | UTF-8 の本文      |
+------------+------------------+
```

例: `Spawn にいるよ` を送る場合のボディは、この文字列の UTF-8 バイト数を VarInt で先頭に付けたもの。チャンネル名を含むパケット全体の構造は各実装の CustomPayload に従います。

## 実装例

### Fabric (yarn) サーバー側

```java
public record DiscordPayload(String text) implements CustomPayload {
	public static final Identifier CHANNEL = Identifier.of("ifumods", "server/discord");
	public static final CustomPayload.Id<DiscordPayload> ID = new CustomPayload.Id<>(CHANNEL);
	public static final PacketCodec<RegistryByteBuf, DiscordPayload> CODEC = CustomPayload.codecOf(
			(buf, value) -> buf.writeString(value.text),
			buf -> new DiscordPayload(buf.readString())
	);

	@Override public Id<? extends CustomPayload> getId() { return ID; }
}

// 初期化時（なければ登録。他modが先に登録済みなら例外になるので握りつぶしてOK）
try {
	PayloadTypeRegistry.playS2C().register(DiscordPayload.ID, DiscordPayload.CODEC);
} catch (IllegalArgumentException alreadyRegistered) {
	// 他の ifuto mod が登録済み。それで問題ない
}

// 送信
ServerPlayNetworking.send(player, new DiscordPayload("Spawn にいるよ"));
```

### 素バイト列で送りたい場合（Fabric）

```java
PacketByteBuf buf = PacketByteBufs.create();
buf.writeString("Spawn にいるよ");
ServerPlayNetworking.send(player, DiscordPayload.ID.id(), buf); // レガシ sender を使う場合
```

### Spigot / Paper（プラグインメッセージ）

```java
String text = "Spawn にいるよ";
byte[] body = encodeMinecraftString(text); // VarInt長 + UTF-8
player.sendPluginMessage(plugin, "ifumods:server/discord", body);
```

※ クライアントに ifuto 系 Mod が入っていない相手に送っても無視されるだけなので、安全に全員へ送って構いません。

## 複数 Mod で受けるときのお作法（衝突回避）

同じチャンネルを複数の Mod で受ける場合は、次を守ってください：

1. **型の登録は「なければ登録」** — PayloadTypeRegistry は重複登録で例外を投げます。例外を握りつぶせば、先に登録した Mod の型でバイト列を読めます
2. **受信ハンドラでパケットをキャンセルしない** — 読むだけにしてください。Ifuto Armor HUD は mixin で読むだけにしているので、他 Mod の受信を邪魔しません
3. **record は String 1 コンポーネントで** — 他 Mod の型としてデコードされても、record の String コンポーネントを読めば取り出せます（Ifuto Armor HUD はその方式で読みにいきます）

```java
public record DiscordPayload(String text) implements CustomPayload { ... }
```

この形に揃えておけば、どちらの Mod が先に読み込まれても、どちらも同じパケットを正しく読めます。
