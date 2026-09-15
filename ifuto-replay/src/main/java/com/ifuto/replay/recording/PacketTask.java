package com.ifuto.replay.recording;

import io.netty.buffer.ByteBuf;
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

	private PacketTask(int kind, long timeMs, int typeIndex, int direction, ByteBuf payload, String text,
					   NbtCompound nbt, byte[] data) {
		this.kind = kind;
		this.timeMs = timeMs;
		this.typeIndex = typeIndex;
		this.direction = direction;
		this.payload = payload;
		this.text = text;
		this.nbt = nbt;
		this.data = data;
	}

	static PacketTask type(int index, int direction, String identifier) {
		return new PacketTask(KIND_TYPE, 0L, index, direction, null, identifier, null, null);
	}

	static PacketTask packet(long timeMs, int typeIndex, int direction, ByteBuf payload) {
		return new PacketTask(KIND_PACKET, timeMs, typeIndex, direction, payload, null, null, null);
	}

	static PacketTask registries(NbtCompound nbt) {
		return new PacketTask(KIND_REGISTRIES, 0L, 0, 0, null, null, nbt, null);
	}

	static PacketTask marker(long timeMs, String name) {
		return new PacketTask(KIND_MARKER, timeMs, 0, 0, null, name, null, null);
	}

	static PacketTask input(long timeMs, byte[] data) {
		return new PacketTask(KIND_INPUT, timeMs, 0, 0, null, null, null, data);
	}

	static PacketTask local(long timeMs, int subtype, byte[] data) {
		return new PacketTask(KIND_LOCAL, timeMs, subtype, 0, null, null, null, data);
	}

	int size() {
		if (this.payload != null) {
			return this.payload.readableBytes();
		}

		return this.data == null ? 0 : this.data.length;
	}
}
