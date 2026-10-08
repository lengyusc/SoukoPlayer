package com.souko.soukoplayer.models

data class LrcRow(
    val time: Long, // 毫秒数
    val content: String
) {
    companion object {
        fun parseLrc(lrcText: String?): List<LrcRow> {
            if (lrcText.isNullOrEmpty()) return emptyList()
            val list = mutableListOf<LrcRow>()
            val lines = lrcText.lines()

            // 更宽松的正则：兼容 1-2 位分钟、可选的毫秒（支持 [01:23.45], [01:23], [1:23.4] 等）
            val regex = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?](.*)""")

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                val match = regex.find(trimmed)
                if (match != null) {
                    val (minStr, secStr, msStr, content) = match.destructured
                    val min = minStr.toLong()
                    val sec = secStr.toLong()
                    val ms = when {
                        msStr.length == 2 -> msStr.toLong() * 10
                        msStr.length == 1 -> msStr.toLong() * 100
                        msStr.isNotEmpty() -> msStr.toLong()
                        else -> 0L
                    }
                    val time = min * 60000 + sec * 1000 + ms
                    if (content.isNotBlank()) {
                        list.add(LrcRow(time, content.trim()))
                    }
                }
            }

            // 兜底策略：如果歌词文本很长，但一条时间轴都没匹配出来（说明是纯文本歌词/Plain Lyrics）
            if (list.isEmpty() && lrcText.isNotBlank()) {
                val plainLines = lrcText.lines().filter { it.isNotBlank() }
                for ((index, line) in plainLines.withIndex()) {
                    // 每行间隔 4 秒虚拟一个时间轴，确保纯文本也能在 LED 正常滚动显示
                    list.add(LrcRow(index * 4000L, line.trim()))
                }
            }

            return list.sortedBy { it.time }
        }
    }
}