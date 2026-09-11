# Build Trigger

このファイルを編集して push すると **Multi Build** ワークフローが起動します。
（`.github/workflows/multi-build.yml` のセットアップが必要です。セットアップ手順は `ci/multi-build.yml` の先頭参照）

下の `key: value` 行がビルドパラメータになります。空欄なら `gradle.properties` の値が使われます。
パラメータを変えずに再実行したいときは `note` の内容を書き換えて push してください。

mc:
yarn:
loader:
fabric_api:
modmenu:
java: 21
workdir: armor-hud
artifact_path: armor-hud/build/libs/*.jar

note: bisect4-9 音だけ外して全描画
