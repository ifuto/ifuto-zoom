package com.ifuto.replay.recording;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import net.minecraft.nbt.NbtCompound;

/**
 * 録画スレッド → 書き込みスレッドへ渡す1件。
 *
 * <p>パケット本体は Netty のプールから借りた {@link ByteBuf} のまま渡して、
 * 書き終わった側で release() する（録画側での余計なコピーをなくすため）。
 */
final class PacketTask {
	/** パケット種類の定義 */
	static final int KIND_TYPE = 0;

	/** パケット本体 */
	static final int KIND_PACKET = 1;

	/** マーカー */
	static final int KIND_MARKER = 2;

	/** 動的レジストリの写し */
	static final int KIND_REGISTRIES = 3;

	/** パケットにならない操作（マウス・キー・画面） */
	static final int KIND_INPUT = 4;

	/** クライアントの内側でだけ起きた出来事（パーティクルなど） */
	static final int KIND_LOCAL = 5;

	/** 世界の写し（直列化が重いので書き込みスレッドでほどく。順番はキューが守る） */
	static final int KIND_SNAPSHOT = 6;

	final int kind;

	/** 録画開始からの経過ミリ秒 */
	final long timeMs;

	/** パケット種類の番号（マーカーでは未使用） */
	final int typeIndex;

	/** {@link ReplayFormat#DIRECTION_S2C} か {@link ReplayFormat#DIRECTION_C2S} */
	final int direction;

	/** 中身（パケット本体のときだけ。それ以外は null） */
	final ByteBuf payload;

	/** 種類定義の識別名 / マーカー名 */
	final String text;

	/** レジストリの写し（KIND_REGISTRIES のときだけ） */
	final NbtCompound nbt;

	/** 入力イベントの中身（KIND_INPUT のときだけ） */
	final byte[] data;

	/** 世界の写し（KIND_SNAPSHOT のときだけ） */
	final WorldSnapshot.Snapshot snapshot;

	/** 写しをほどく変換器（KIND_SNAPSHOT のときだけ） */
	final PacketEncoder encoder;

	/** 写しの各パケットの種類番号（登録に失敗した物は -1。KIND_SNAPSHOT のときだけ） */
	final int[] typeIndices;

	/** 種類ごとの「前回の大きさ」（入れ物の目安。KIND_SNAPSHOT のときだけ） */
	final int[] sizeHints;

	private PacketTask(int kind, long timeMs, int typeIndex, int direction, ByteBuf payload, String text,
					   NbtCompound nbt, byte[] data, WorldSnapshot.Snapshot snapshot, PacketEncoder encoder,
					   int[] typeIndices, int[] sizeHints) {
		this.kind = kind;
		this.timeMs = timeMs;
		this.typeIndex = typeIndex;
		this.direction = direction;
		this.payload = payload;
		this.text = text;
		this.nbt = nbt;
		this.data = data;
		this.snapshot = snapshot;
		this.encoder = encoder;
		this.typeIndices = typeIndices;
		this.sizeHints = sizeHints;
	}

	static PacketTask type(int index, int direction, String identifier) {
		return new PacketTask(KIND_TYPE, 0L, index, direction, null, identifier, null, null,
				null, null, null, null);
	}

	static PacketTask packet(long timeMs, int typeIndex, int direction, ByteBuf payload) {
		return new PacketTask(KIND_PACKET, timeMs, typeIndex, direction, payload, null, null, null,
				null, null, null, null);
	}

	static PacketTask registries(NbtCompound nbt) {
		return new PacketTask(KIND_REGISTRIES, 0L, 0, 0, null, null, nbt, null,
				null, null, null, null);
	}

	static PacketTask marker(long timeMs, String name) {
		return new PacketTask(KIND_MARKER, timeMs, 0, 0, null, name, null, null,
				null, null, null, null);
	}

	static PacketTask input(long timeMs, byte[] data) {
		return new PacketTask(KIND_INPUT, timeMs, 0, 0, null, null, null, data,
				null, null, null, null);
	}

	static PacketTask local(long timeMs, int subtype, byte[] data) {
		return new PacketTask(KIND_LOCAL, timeMs, subtype, 0, null, null, null, data,
				null, null, null, null);
	}

	static PacketTask snapshot(long timeMs, WorldSnapshot.Snapshot snapshot, PacketEncoder encoder,
			int[] typeIndices, int[] sizeHints) {
		return new PacketTask(KIND_SNAPSHOT, timeMs, 0, 0, null, null, null, null,
				snapshot, encoder, typeIndices, sizeHints);
	}

	int size() {
		if (this.payload != null) {
			return this.payload.readableBytes();
		}

		return this.data == null ? 0 : this.data.length;
	}

	/** 小さい入れ物の初期値（ふつうのパケットはこれで足りる） */
	static final int INITIAL_BUFFER_SIZE = 256;

	/** 目安どおりに取るときの上限（変な1発でプールを食いつぶさない） */
	static final int MAX_INITIAL_BUFFER_SIZE = 256 * 1024;

	/**
	 * 「前回この種類がこの大きさだった」を目安に入れ物を取る。
	 *
	 * <p>小さい入れ物から育て直すと、チャンク等のたびに十数回の作り直し＋コピーが
	 * 起きる（ネットワークスレッドで起きるので地味に効く）。目安が外れても
	 * ただ育ち直すだけなので、古い配列を見ても壊れない。
	 */
	static ByteBuf sizedBuffer(int[] hints, int index) {
		int hint = hints != null && index >= 0 && index < hints.length ? hints[index] : 0;
		int initial = Math.min(Math.max(INITIAL_BUFFER_SIZE, hint), MAX_INITIAL_BUFFER_SIZE);
		return ByteBufAllocator.DEFAULT.buffer(initial);
	}

	/** 今回の大きさを覚える（ただの目安なので、競合しても壊れない） */
	static void noteSize(int[] hints, int index, int size) {
		if (hints != null && index >= 0 && index < hints.length) {
			hints[index] = size;
		}
	}
}
