// format-office: Excel（.xlsx）の表の読み書きと、Word（.docx）文書の中の表の読み取り（Apache POI）。拡張の 1 つで、
// 公開するのは FormatCodec の実装 XlsxFormat・DocxFormat だけ（ADR-0013・ADR-0015）。POI はこのモジュールだけに置き、中核（app-core）には入れない
plugins {
    id("yde.kotlin-module")   // 共通の設定（buildSrc）: Kotlin・toolchain 21・テストの依存と設定
}

dependencies {
    implementation(project(":app-core"))
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    constraints {
        implementation("org.apache.logging.log4j:log4j-api:2.25.5") {
            because("POI の推移依存を非有限値の JSON 化の修正版へ揃える")
        }
    }
}
