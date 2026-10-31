# Haas音声入力

Android 用の音声入力アプリです。**音量下キーを長押ししている間だけ音声を受け付け**、離すと日本語音声認識モデル [Haas-ja-v1.0](https://huggingface.co/0x3/Haas-ja-v1.0) で文字起こしして、**今カーソルがある入力欄に挿入**します。どのアプリでも使えます。

- 認識エンジンは **Haas-ja（端末内）** と **標準（Androidの音声認識）** をアプリの画面で切り替えられます。標準を選ぶと、モデルのダウンロードは不要で、話している途中の文字も表示されます。
- Haas-ja を使う場合、音声認識はすべて端末内で行い、音声も文字も外部に送りません（ネット接続はモデルの初回ダウンロード時だけ使います）。
- モデルは有志による ONNX 量子化版 [oboroge0/Haas-ja-v1.0-onnx-quantized](https://huggingface.co/oboroge0/Haas-ja-v1.0-onnx-quantized)（約460MB）を [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) で動かしています。
- 音声入力中は画面上部に赤い「🎤 音声入力中」、文字起こし中は「⏳ 文字起こし中…」が表示されます。
- 音量下キーを短く押したときは、いつも通り音量が下がります。
- 入力欄が見つからないときは、結果をクリップボードにコピーします。

## インストール

1. [Releases の latest](../../releases/tag/latest) から `haas-voice-input.apk` をスマホでダウンロードしてインストールします。
2. アプリを開いて、画面の ①〜③ を順に設定します。
   - ① マイクと通知を許可します。
   - ② モデルをダウンロードします（約460MB）。
   - ③ 設定 → ユーザー補助 → インストール済みのアプリ → 「Haas音声入力（音量キー）」をオンにします。オンにできないときは、アプリ情報の右上「︙」→「制限付き設定を許可」を先に押してください。
3. ④ のテスト欄で動作を確認します。

> 2026年10月31日に署名鍵を変更しました。それより前のビルドを入れている場合は、一度アンインストールしてから入れ直してください。

## 制限

- 画面がオフのときは使えません（Android の仕様で、キー入力がアプリに届かないため）。
- リアルタイム表示はなく、キーを離してから文字起こしします。長い音声は25秒ごとに区切って認識します。
- 対応端末は arm64 の Android 10 以降です。

## ビルド

GitHub Actions（`.github/workflows/build.yml`）が、sherpa-onnx のネイティブライブラリと Kotlin API を取得して APK をビルドします。

署名鍵はリポジトリに入れず、GitHub Secrets から読み込みます。フォークして自分でビルドする場合は、次の2つを登録してください（未登録だと Android の debug 鍵で署名され、Release は更新されません）。

- `SIGNING_KEYSTORE_B64`: keystore ファイルを base64 にしたもの（キーのエイリアスは `haasvoice`）
- `SIGNING_PASSWORD`: keystore とキーのパスワード

## ライセンス

- このアプリのコード: Apache License 2.0（[LICENSE](LICENSE)）
- Haas-ja-v1.0: Apache License 2.0
- sherpa-onnx: Apache License 2.0
- ONNX Runtime（sherpa-onnx のネイティブライブラリに同梱）: MIT License
