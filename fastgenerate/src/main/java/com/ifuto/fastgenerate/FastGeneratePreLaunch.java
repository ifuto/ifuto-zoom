package com.ifuto.fastgenerate;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 起動前の調整。
 *
 * <p>バニラのバックグラウンド用スレッド数（{@code Util} が作るワーカープール）は
 * {@code max(1, コア数 - 1)}。2〜4 コアのノート PC では <b>1〜3 本</b>しか立たず、
 * 地形生成のような「完全に並列なタスク」には足りない（Amdahl 1967 / Gustafson 1988）。</p>
 *
 * <p>そこで 4 コア以下では常に全コアを使う。逆に 8 コア以上では 1 本あける
 * （メインスレッドと描画に 1 コア必要。超並列にしてもキャッシュを奪い合って
 * 遅くなるだけ = ワーキングセットの衝突, Denning 1968）。</p>
 *
 * <p>ユーザーが {@code -Dmax.bg.threads=...} を指定している場合は触らない。</p>
 */
public final class FastGeneratePreLaunch implements PreLaunchEntrypoint {
	private static final Logger LOGGER = LoggerFactory.getLogger("fastgenerate");
	/** これ以下ならコアを全部使う */
	public static final int SMALL_CORE_LIMIT = 4;
	/** これ以上は頭打ちにする（それ以上増やしても生成は速くならない） */
	public static final int THREAD_CAP = 16;

	@Override
	public void onPreLaunch() {
		FastGenerateConfig config = FastGenerateConfig.get();
		if (!config.tuneThreads) {
			return;
		}
		if (System.getProperty("max.bg.threads") != null) {
			return;   // ユーザー指定を尊重
		}
		int cores = Runtime.getRuntime().availableProcessors();
		int threads = bgThreads(cores);
		System.setProperty("max.bg.threads", Integer.toString(threads));
		LOGGER.info("[fastgenerate] max.bg.threads = {} (cores={}, vanilla would use {})",
			threads, cores, Math.max(1, cores - 1));
	}

	/**
	 * バックグラウンドスレッド数の決定（純関数・テスト対象）。
	 *
	 * @param cores 論理コア数
	 */
	public static int bgThreads(int cores) {
		int c = Math.max(1, cores);
		int threads = c <= SMALL_CORE_LIMIT ? c : c - 1;
		return Math.max(1, Math.min(THREAD_CAP, threads));
	}
}
