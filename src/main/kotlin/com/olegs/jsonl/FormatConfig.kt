package com.olegs.jsonl

import java.time.ZoneId

/**
 * One-shot rendering configuration. Replaces the seven-argument `formatLine`
 * signature with a single immutable value object.
 */
data class FormatConfig(
    val zone: ZoneId = ZoneId.systemDefault(),
    val targetPrefix: String = "",
    val levelPadWidth: Int = 0,
    val targetPadWidth: Int = 0,
    val messagePadWidth: Int = 0,
    val prettifyValues: Boolean = false,
    val timestampDecimals: Int = 6,
)
