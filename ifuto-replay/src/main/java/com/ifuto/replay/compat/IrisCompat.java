package com.ifuto.replay.compat;

import com.ifuto.replay.IfutoReplayClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Iris Shaders との連携（あくまで「入っているときだけ」）。
 *
 * <p>Iris の API はクラスパスに必ずあるわけではないので、ビルド時の依存には足さずに
 * 反映（リフレクション）で呼ぶ。入っていなければ何もしないだけ。
 *
 * <p>シェーダーの画面そのものは Iris に開かせているので、Iris の内部が変わっても
 * こちらは壊れにくい（API v0 の {@code openMainIrisScreenObj} を使う）。
 */
public final class IrisCompat {
	private static final String MOD_ID = "iris";
	private static final String API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";

	private static boolean resolved;
	private static @Nullable Object api;

	private IrisCompat() {
	}

	/** Iris が入っていて、API も使えるか */
	public static boolean isAvailable() {
		return resolve() != null;
	}

	/** いまシェーダーが効いているか */
	public static boolean isShaderPackInUse() {
		Object irisApi = resolve();

		if (irisApi == null) {
			return false;
		}

		try {
			Method method = irisApi.getClass().getMethod("isShaderPackInUse");
			Object result = method.invoke(irisApi);
			return result instanceof Boolean value && value;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Iris のシェーダー選択画面を開く。
	 *
	 * @return 開く画面。Iris が無い・失敗したときは null
	 */
	public static @Nullable Screen openShaderScreen(Screen parent) {
		Object irisApi = resolve();

		if (irisApi == null) {
			return null;
		}

		try {
			Method method = irisApi.getClass().getMethod("openMainIrisScreenObj", Object.class);
			Object result = method.invoke(irisApi, parent);
			return result instanceof Screen screen ? screen : null;
		} catch (Throwable t) {
			IfutoReplayClient.LOGGER.warn("[ifuto-replay] Iris の画面を開けませんでした", t);
			return null;
		}
	}

	private static @Nullable Object resolve() {
		if (resolved) {
			return api;
		}

		resolved = true;
		api = null;

		if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
			return null;
		}

		try {
			Class<?> type = Class.forName(API_CLASS);
			api = type.getMethod("getInstance").invoke(null);
		} catch (Throwable t) {
			IfutoReplayClient.LOGGER.info("[ifuto-replay] Iris の API が見つからないのでシェーダーボタンは使えません");
		}

		return api;
	}
}
