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
	public static final int VERSION = 5;

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

	/**
	 * 圧縮が **パケットをまたいで辞書を共有している**。
	 *
	 * <p>パケット1個は数十バイトしかないので、1個ずつ圧縮してもほとんど縮まない。
	 * この印があるファイルは、先頭から順に deflate を1本つないだ状態で書かれている
	 * （パケットごとに SYNC_FLUSH で区切ってある）。読む側も **必ず先頭から順に**
	 * たどることで同じ状態を再現できる。
	 */
	public static final int FLAG_SHARED_DEFLATE = 1 << 1;

	/**
	 * パケットを **かたまり（{@link #TAG_BLOCK}）にまとめて** 圧縮してある。
	 *
	 * <p>この印があるファイルは、圧縮されている所がすべて「かたまり1個 = deflate 1本」
	 * になっている。したがって読み飛ばしても・順番が違っても壊れない
	 * （古い {@link #FLAG_SHARED_DEFLATE} はファイル全体で1本つながっていた）。
	 */
	public static final int FLAG_BLOCK_DEFLATE = 1 << 2;

	/**
	 * 動的レジストリの写し: 圧縮後の長さ / 展開後の長さ / deflate した NBT。
	 *
	 * <p>ヘッダのすぐ後ろに 1 回だけ書く。パケットを復元するにはサーバーから届いた
	 * レジストリ（バイオーム・次元など）が同じ状態で必要なので、録り始めの時点の物を
	 * そっくり保存しておく。これがあるおかげで、あとから別の世界に居ても再生できる。
	 */
	public static final int TAG_REGISTRIES = 5;

	/** パケットにならない操作（マウス・キー・画面）の差分 */
	public static final int TAG_INPUT = 6;

	/**
	 * **パケットをまとめて圧縮したかたまり**。
	 *
	 * <p>形式: 展開後の長さ / 格納方法 / 長さ / 中身。展開した中身には
	 * 「経過時間差 / 種類index / 長さ / パケットの中身」が入っている数だけ並ぶ。
	 *
	 * <p>パケットを1個ずつ圧縮すると、数十バイトの相手を相手にすることになるので
	 * ほとんど縮まない（区切りを入れる分だけ損をすることさえある）。そこで
	 * ある程度たまった所でひとまとめにして圧縮する。かたまり1個は
	 * **それだけで完結した deflate** なので、順番を気にせず扱える
	 * （= クリップの区間をつないでも壊れない）。
	 */
	public static final int TAG_BLOCK = 8;

	/**
	 * **クライアントの内側でだけ** 起きた出来事（パーティクルなど）。
	 *
	 * <p>サーバーから届く物はパケットとして残るが、クライアントが自分で湧かせる物は
	 * パケットにならない（ブロックを崩しているときの破片、足あと、Mod の演出など）。
	 * 形式: 経過時間差 / 種類 / 長さ / 中身。
	 */
	public static final int TAG_LOCAL = 7;

	// --- TAG_INPUT の種類 ---

	/** カーソルの絶対座標（ときどき入れて、欠けても崩れないようにする） */
	public static final int INPUT_MOUSE_ABS = 0;

	/** カーソルの移動量 */
	public static final int INPUT_MOUSE_DELTA = 1;

	/** 視点（F5） */
	public static final int INPUT_PERSPECTIVE = 2;

	/** デバッグ画面（F3） */
	public static final int INPUT_DEBUG = 3;

	/** 開いている画面（空欄 = 開いていない） */
	public static final int INPUT_SCREEN = 4;

	/** チャット欄の入力中テキスト（全文） */
	public static final int INPUT_CHAT_SET = 5;

	/** チャット欄の入力中テキスト（増えた分だけ） */
	public static final int INPUT_CHAT_APPEND = 6;

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
