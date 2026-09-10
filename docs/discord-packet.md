# ifumods:server/discord — サーバー向けパケットガイド

ifuto 系 Mod が共通で受け取っている、Discord Rich Presence（Discord の「プレイ中」表示）更新用のカスタムペイロードの仕様です。サーバー側から送ると、プレイヤーの Discord ステータスに好きな文字列を表示できます。

## まとめ

| 項目 | 値 |
| --- | --- |
| チャンネル | `ifumods:server/discord` |
| 方向 | S2C（サーバー → クライアント）。PLAY フェーズ |
| 中身 | **Minecraft 文字列2本**（① Client ID、② 表示文）※ 旧形式の文字列1本（表示文のみ）も読めます |
| ① Client ID | 使う Discord アプリケーションの Client ID（数字のみの文字列） |
| ② 表示文 | Rich Presence の 1 行目（details）にそのまま表示 |
| Client ID が空文字 | クライアント側設定の Client ID（あれば）を使う |
| 表示文が空文字 | プレゼンスを消す |
| 推奨頻度 | 変更時のみ送るのがベスト。毎秒 1 回程度までなら OK（クライアント側で 0.8 秒間隔に間引きされます） |

## 動作の流れ

1. サーバーが `ifumods:server/discord` に `(Client ID, 表示文)` を送る
2. 受け取ったクライアント Mod（Ifuto Armor HUD など）が、ローカルの Discord IPC に `SET_ACTIVITY` を送る
3. プレイヤーの Discord の「プレイ中」欄にその文字列が出る
   - アイコンは `https://a12a12a12.pages.dev/icon.png`（Discord の外部画像プロキシ経由）
   - Client ID のアプリ名が「プレイ中」のタイトルになります（[Discord Developer Portal](https://discord.com/developers/applications) でアプリを作って名前を付けてください）
4. 空の表示文を送ると表示が消える

### プレゼンスの占有と解除

- このパケットを受け取ると、クライアント Mod は**そのサーバーから抜けるまで Rich Presence を握ります**
- 握っている間：他 Mod が Discord RPC（jagrosh DiscordIPC などの代表的なライブラリ）を操作しようとしても mixin で止めます。止めきれない実装向けにも 10 秒ごとに自前のプレゼンスを送り直しています
- サーバーから抜けると：止めるのをやめ、プレゼンスを消して他 Mod の Discord RPC に返します
- ロック中は JVM システムプロパティ **`ifumods.discordLock` が `"true"`** になります。RPC を出す予定のある ifuto Mod は、このプロパティを見て自重してください

※ パケットにも設定にも有効な Client ID が無い人には何も起きません（連携オフ。他 Mod の RPC も止めません）。

## ペイロードの中身（バイト列の構造）

カスタムペイロードのボディは、Minecraft 文字列（VarInt 長 + UTF-8）が2つ順に並んだものです。

```
+------------+----------------+------------+----------------+
| VarInt 長さ | Client ID      | VarInt 長さ | 表示文          |
|             | (UTF-8)        |             | (UTF-8)        |
+------------+----------------+------------+----------------+
```

例: Client ID `123456789012345678`、表示文 `Spawn にいるよ`。チャンネル名を含むパケット全体の構造は各実装の CustomPayload に従います。

※ **旧形式（文字列1本）との互換**: ボディが文字列1本だけの場合、受信側はそれを「表示文」として扱い、Client ID はクライアント側設定の値にフォールバックします。新しく実装する側は必ず2本で送ってください。

## 実装例

### Fabric (yarn) サーバー側

```java
public record DiscordPayload(String clientId, String text) implements CustomPayload {
	public static final Identifier CHANNEL = Identifier.of("ifumods", "server/discord");
	public static final CustomPayload.Id<DiscordPayload> ID = new CustomPayload.Id<>(CHANNEL);
	public static final PacketCodec<RegistryByteBuf, DiscordPayload> CODEC = CustomPayload.codecOf(
			(value, buf) -> {           // エンコーダは value 先・buf 後（ValueFirstEncoder）
				buf.writeString(value.clientId);
				buf.writeString(value.text);
			},
			buf -> new DiscordPayload(buf.readString(), buf.readString())
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
ServerPlayNetworking.send(player, new DiscordPayload("123456789012345678", "Spawn にいるよ"));
```

### Spigot / Paper（プラグインメッセージ）

```java
String clientId = "123456789012345678";
String text = "Spawn にいるよ";

byte[] idBytes = encodeMinecraftString(clientId); // VarInt長 + UTF-8
byte[] textBytes = encodeMinecraftString(text);

byte[] body = new byte[idBytes.length + textBytes.length];
System.arraycopy(idBytes, 0, body, 0, idBytes.length);
System.arraycopy(textBytes, 0, body, idBytes.length, textBytes.length);

player.sendPluginMessage(plugin, "ifumods:server/discord", body);
```

※ クライアントに ifuto 系 Mod が入っていない相手に送っても無視されるだけなので、安全に全員へ送って構いません。

## 複数 Mod で受けるときのお作法（衝突回避）

同じチャンネルを複数の Mod で受ける場合は、次を守ってください：

1. **型の登録は「なければ登録」** — PayloadTypeRegistry は重複登録で例外を投げます。例外を握りつぶせば、先に登録した Mod の型でバイト列を読めます
2. **受信ハンドラでパケットをキャンセルしない** — 読むだけにしてください。Ifuto Armor HUD は mixin で読むだけにしているので、他 Mod の受信を邪魔しません
3. **record は `String clientId, String text` の2コンポーネントで** — 他 Mod の型としてデコードされても、record の String コンポーネントを宣言順に読めば取り出せます（Ifuto Armor HUD はその方式で読みにいきます）。文字列が1本だけの record も旧形式として読めます

```java
public record DiscordPayload(String clientId, String text) implements CustomPayload { ... }
```

この形に揃えておけば、どちらの Mod が先に読み込まれても、どちらも同じパケットを正しく読めます。バグらず、RPC もどちらか片方だけが正常に出る状態になります。
