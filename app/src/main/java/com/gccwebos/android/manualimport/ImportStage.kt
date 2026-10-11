package com.gccwebos.android.manualimport

/** Coarse stage of a running import. Carries no content: only which phase the work is in. */
enum class ImportStage(val caption: String, val step: Int) {
    Reading("正在读取订阅…", 1),
    Parsing("正在解析配置…", 2),
    Saving("正在保存…", 3);
    companion object { const val Steps = 3 }
}
