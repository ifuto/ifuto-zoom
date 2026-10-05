package com.ifuto.replay.recording;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * 録画側 → 書き込みスレッドへの受け渡し箱（複数人入れ・1人取り出し）。
 *
 * <p>形は LMAX Disruptor の流れをくむ有界 MPSC キュー（Vyukov の有界 MPSC /
 * JCTools MpscArrayQueue と同じ手続き。C 移植版で正しさと速さを実測済み）。
 * 鍵（ロック）を一切使わないので、入れる側（ネット・クライアントスレッド）は
 * 書き込み側の都合で止められることがない:
 * <ul>
 *     <li>{@link #offer} はいっぱいならすぐ {@code false}（捨てるのは呼び出し側の判断）</li>
 *     <li>取り出しは書き込みスレッドの1人だけ（2人で取ると壊れる）</li>
 *     <li>よく触る3つの番号はキャッシュ行を分けて置く（偽共有よけ）</li>
 * </ul>
 *
 * <p>容量は2の累乗に切り上げる（端数の計算を {@code &} 1発にするため）。
 */
final class MpscPacketQueue extends MpscConsumerIndexPad {
	/** 配列の要素への口（取り出すときは acquire・置くときは release） */
	private static final VarHandle BUFFER = MethodHandles.arrayElementVarHandle(Object[].class);

	private static final VarHandle PRODUCER_INDEX;
	private static final VarHandle PRODUCER_LIMIT;
	private static final VarHandle CONSUMER_INDEX;

	static {
		try {
			MethodHandles.Lookup lookup = MethodHandles.lookup();
			PRODUCER_INDEX = lookup.findVarHandle(MpscProducerIndexHolder.class, "producerIndex", long.class);
			PRODUCER_LIMIT = lookup.findVarHandle(MpscProducerLimitHolder.class, "producerLimit", long.class);
			CONSUMER_INDEX = lookup.findVarHandle(MpscPacketQueue.class, "consumerIndex", long.class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	/** 空回りを何回までするか（その後は寝る。起きている間の遅れを省くため） */
	private static final int SPIN_TRIES = 200;

	/** 中身（取り出したら null に戻す。古い物を持ち続けないように） */
	private final Object[] buffer;

	/** 容量 - 1（{@code &} で番地にする） */
	private final long mask;

	/** 次に取り出す番号（取り出す側だけが進める） */
	private volatile long consumerIndex;

	/** 眠っている取り出し側（いなければ null。入れたら起こす） */
	private volatile Thread waiter;

	MpscPacketQueue(int capacity) {
		int size = 1;

		while (size < Math.max(64, capacity)) {
			size <<= 1;
		}

		this.buffer = new Object[size];
		this.mask = size - 1L;
		this.producerLimit = size;
	}

	/**
	 * 入れる。いっぱいで入らなければ {@code false}（待たない・捨てる判断は呼び出し側）。
	 *
	 * <p>手順: 取れるはずの範囲内なら番号を1個もぎ取り（CAS）、
	 * その番地へ release で置く。範囲を超えていたら取り出し側の番号を見直して、
	 * それでも超えていたら「いっぱい」。
	 */
	boolean offer(PacketTask task) {
		if (task == null) {
			return false;
		}

		long mask = this.mask;
		long capacity = mask + 1L;
		Object[] buffer = this.buffer;

		for (;;) {
			long index = (long) PRODUCER_INDEX.getVolatile(this);
			long limit = (long) PRODUCER_LIMIT.getVolatile(this);

			if (index >= limit) {
				long taken = (long) CONSUMER_INDEX.getAcquire(this);
				limit = taken + capacity;

				if (index >= limit) {
					return false;
				}

				PRODUCER_LIMIT.setRelease(this, limit);
			}

			if (PRODUCER_INDEX.compareAndSet(this, index, index + 1L)) {
				BUFFER.setRelease(buffer, (int) (index & mask), task);

				Thread waiter = this.waiter;

				if (waiter != null) {
					LockSupport.unpark(waiter);
				}

				return true;
			}
		}
	}

	/**
	 * 1個取る。無ければ {@code null}。
	 *
	 * <p>番号は取られたのに中身がまだ置かれていない瞬間だけ空回りする
	 * （入れる側は番号取りの直後に置くので、すぐ終わる）。
	 */
	PacketTask poll() {
		long taken = this.consumerIndex;
		Object[] buffer = this.buffer;
		int slot = (int) (taken & this.mask);
		Object found = BUFFER.getAcquire(buffer, slot);

		if (found == null) {
			long index = (long) PRODUCER_INDEX.getAcquire(this);

			if (taken == index) {
				return null;
			}

			do {
				found = BUFFER.getAcquire(buffer, slot);
			} while (found == null);
		}

		BUFFER.set(buffer, slot, null);
		this.consumerIndex = taken + 1L;
		return (PacketTask) found;
	}

	/**
	 * 来るまで少し待って1個取る。来なければ {@code null}。
	 *
	 * <p>最初は少しだけ空回り（取り続けているときの寝起きを省く）、
	 * それでも来なければ寝る。入れる側が起こしてくれるので、
	 * 待てる上限いっぱいまで寝るのは本当に何も来ないときだけ。
	 */
	PacketTask poll(long timeout, TimeUnit unit) {
		PacketTask task = this.poll();

		if (task != null) {
			return task;
		}

		for (int i = 0; i < SPIN_TRIES; i++) {
			Thread.onSpinWait();
			task = this.poll();

			if (task != null) {
				return task;
			}
		}

		long nanos = Math.max(0L, unit.toNanos(timeout));

		if (nanos <= 0L) {
			return null;
		}

		Thread self = Thread.currentThread();
		this.waiter = self;

		try {
			// 名乗りを上げてからもう一度見る（入れ違いの寝過ごしを防ぐ）
			task = this.poll();

			if (task != null) {
				return task;
			}

			LockSupport.parkNanos(nanos);
			return this.poll();
		} finally {
			this.waiter = null;
		}
	}

	/** だいたいの溜まり具合（表示用。厳密ではない） */
	int size() {
		long produced = (long) PRODUCER_INDEX.getVolatile(this);
		long taken = (long) CONSUMER_INDEX.getVolatile(this);
		long size = produced - taken;
		return (int) Math.max(0L, Math.min(size, Integer.MAX_VALUE));
	}
}

/** 一番下の詰め物（偽共有よけ。継承で並びを固定する） */
abstract class MpscProducerIndexPad {
	@SuppressWarnings("unused")
	private long p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15;
}

/** 入れた番号（入れる側みんなで CAS する） */
abstract class MpscProducerIndexHolder extends MpscProducerIndexPad {
	volatile long producerIndex;
}

/** 2段目の詰め物 */
abstract class MpscProducerLimitPad extends MpscProducerIndexHolder {
	@SuppressWarnings("unused")
	private long q0, q1, q2, q3, q4, q5, q6, q7, q8, q9, q10, q11, q12, q13, q14, q15;
}

/** 取れる上限（取り出し側の番号 + 容量の覚え書き） */
abstract class MpscProducerLimitHolder extends MpscProducerLimitPad {
	volatile long producerLimit;
}

/** 3段目の詰め物（consumerIndex との間） */
abstract class MpscConsumerIndexPad extends MpscProducerLimitHolder {
	@SuppressWarnings("unused")
	private long c0, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15;
}
