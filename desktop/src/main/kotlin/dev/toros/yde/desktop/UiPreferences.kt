package dev.toros.yde.desktop

import java.util.prefs.Preferences

/** 画面の配置と表示の設定（左右の幅と開閉・ダーク・閲覧専用）を覚える場所。窓（Main.kt）と骨格（ui/shell/AppRoot.kt）が読み書きする。 */
internal val uiPreferences: Preferences = Preferences.userRoot().node("yaml-design-editor/ui")
