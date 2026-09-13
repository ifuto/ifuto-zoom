package com.ifuto.replay.recording;

/**
 * .ifreplay のファイル形式の定数。
 *
 * <p>仕様の詳細はリポジトリの {@code docs/replay-file-format.md} を参照。
 * ただAppendするだけなので、録画中はファイル先頭に戻る必要がない（= シークなし・追記専用）。
 */
public final class ReplayFormat {
	/** ファイルの先頭に置くマジック "IFRP" */
	public static final byte[] MAGIC = {'I', 'F', 'R', 'P'};

	/** フォーマットバージョン */
	public static final int VERSION = 1;

	/** 保存ファイルの拡張子 */
	public static final String FILE_EXTENSION = ".ifreplay";

	// --- フレームの種類（varint で 1 個書く） ---

	/** 録画の終わり。これ以降は読まない */
	public static final int TAG_END = 0;

	/** パケット種類の定義: index / 向き / 識別名 */
	public static final int TAG_PACKET_TYPE = 1;

	/** パケット本体: 経過時間差 / 種類index / 長さ / 格納方法 / 中身 */
	public static final int TAG_PACKET = 2;

	/** マーカー（しおり）: 経過時間差 / 名前 */
	public static final int TAG_MARKER = 3;

	/** シーク用のインデックス: 件数 / (時間, ファイル位置) * 件数 / 総時間 */
	public static final int TAG_INDEX = 4;

	// --- パケットの向き ---

	/** サーバー → クライアント */
	public static final int DIRECTION_S2C = 0;

	/** クライアント → サーバー */
	public static final int DIRECTION_C2S = 1;

	// --- ペイロードの格納方法 ---

	/** そのまま */
	public static final int METHOD_RAW = 0;

	/** deflate（中身の先頭に展開後の長さが入る） */
	public static final int METHOD_DEFLATE = 1;

	// --- ヘッダのフラグ ---

	/** C2S（自分の操作）も入っている */
	public static final int FLAG_HAS_C2S = 1;

	private ReplayFormat() {
	}
}
